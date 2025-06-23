package org.example.auctionbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.github.ansell.jdefaultdict.JDefaultDict;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.example.auctionbot.*;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int WEBSOCKET_PORT = 8887; // Port for the WebSocket server
    private static AuctionWebSocketServer webSocketServer;
    private static ObjectMapper jsonMapper = new ObjectMapper(); // Re-use ObjectMapper

    public static void main(String[] args) {
        Path configPath = Paths.get("config.toml");
        Path defaultConfigPath = Paths.get("defaultconfig.toml");

        if (!Files.exists(configPath)) {
            try {
                Files.copy(defaultConfigPath, configPath);
                throw new ConfigError("Missing config.toml file. Default created. Please fill in your API key.");
            } catch (IOException e) {
                logger.error("Failed to create default config.toml: {}", e.getMessage());
                System.exit(1);
            }
        }

        Config config;
        try {
            TomlMapper tomlMapper = new TomlMapper();
            config = tomlMapper.readValue(configPath.toFile(), Config.class);
        } catch (IOException e) {
            logger.error("Error loading config.toml: {}", e.getMessage());
            System.exit(1);
            return;
        }

        if (config.getApi() == null || config.getApi().getHypixel() == null || config.getApi().getHypixel().isEmpty()) {
            throw new ConfigError("Ensure API keys are filled in config.toml");
        }

        // Basic API key validation
        try {
            HttpClient httpClient = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(Constants.TOKEN_TEST, config.getApi().getHypixel())))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode tokenCheck = jsonMapper.readTree(response.body());
            if (tokenCheck.has("cause") && tokenCheck.get("cause").asText().equals("Invalid API key")) {
                throw new ConfigError("Invalid Hypixel API Key");
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Error validating Hypixel API key: {}", e.getMessage());
            System.exit(1);
        }

        // Initialize AuctionGrabber dependencies
        JDefaultDict<String, List<Double>> minPrice = new JDefaultDict<>(k -> new ArrayList<>());
        List<String> notifiedUuids = new ArrayList<>();

        AuctionGrabber grabber = new AuctionGrabber(config, minPrice, notifiedUuids);

        // Initialize and start WebSocket server, binding to "0.0.0.0" to accept connections from other computers
        webSocketServer = new AuctionWebSocketServer(new InetSocketAddress("0.0.0.0", WEBSOCKET_PORT));
        webSocketServer.setReuseAddr(true); // Allow immediate reuse of the address
        webSocketServer.start();
        logger.info("WebSocket server started on port {}", WEBSOCKET_PORT);


        while (true) {
            try {
                // Receive and process auctions
                grabber.receiveAuctions().join();
                // Check for profitable flips
                List<Auction> flips = grabber.checkFlip().join();

                if (!flips.isEmpty()) {
                    logger.info("Found {} good flips:", flips.size());
                } else {
                    logger.info("No good flips found in this cycle.");
                }


                for (Auction auc : flips) {
                    // Log to console
                    logger.info("[{}] {}\nPrice: ${:,.0f} | Value: ${:,.0f} | Profit: ${:,.0f} | Ends in: {}",
                            auc.getId(), auc.getName(), auc.getPrice(), auc.getValue(), (auc.getValue() - auc.getPrice()), auc.getTimeEnding());

                    // Prepare data for WebSocket
                    ObjectNode flipData = jsonMapper.createObjectNode();
                    flipData.put("command", String.format("https://sky.lea.moe/auction/%s", auc.getId())); // Link to Sky.lea.moe auction
                    flipData.put("price", auc.getPrice());

                    // Get lowest and second lowest BIN for the item
                    List<Double> itemBinPrices = grabber.getAllCurrentBinPrices().get(auc.getName());
                    if (itemBinPrices != null && !itemBinPrices.isEmpty()) {
                        // Sort prices to easily get lowest and second lowest
                        Collections.sort(itemBinPrices);
                        flipData.put("lowest_bin", itemBinPrices.get(0));
                        flipData.put("second_lowest_bin", itemBinPrices.size() > 1 ? itemBinPrices.get(1) : itemBinPrices.get(0)); // If only one, use it as second lowest
                    } else {
                        flipData.put("lowest_bin", 0.0);
                        flipData.put("second_lowest_bin", 0.0);
                    }

                    // Broadcast the flip data to all connected WebSocket clients
                    webSocketServer.broadcast(flipData.toString());
                }

                logger.info("Checked auctions. Total flips found in this cycle: {}. Connected WebSocket clients: {}", flips.size(), webSocketServer.getConnections().size());

                // Wait before next cycle
                TimeUnit.SECONDS.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Application interrupted. Shutting down WebSocket server.");
                try {
                    webSocketServer.stop();
                } catch (InterruptedException ex) {
                    logger.error("Error stopping WebSocket server: {}", ex.getMessage());
                }
                break;
            } catch (Exception e) {
                logger.error("An unexpected error occurred in main loop: {}", e.getMessage(), e);
                try {
                    TimeUnit.SECONDS.sleep(30);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Custom WebSocketServer implementation to handle client connections and broadcasting.
     */
    private static class AuctionWebSocketServer extends WebSocketServer {
        private final Set<WebSocket> connections;

        public AuctionWebSocketServer(InetSocketAddress address) {
            super(address);
            this.connections = new HashSet<>();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            connections.add(conn);
            logger.info("New connection: {} (Total: {})", conn.getRemoteSocketAddress(), connections.size());
            // Optionally send some initial data or welcome message
            conn.send("Welcome to the Auction Bot WebSocket! Waiting for flips...");
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            connections.remove(conn);
            logger.info("Closed connection: {} (Total: {}). Reason: {}", conn.getRemoteSocketAddress(), connections.size(), reason);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // This server only sends data, it doesn't process incoming messages from clients,
            // but you could add logic here if needed for client commands.
            logger.debug("Received message from {}: {}", conn.getRemoteSocketAddress(), message);
            conn.send("Server received your message: " + message + " - I only broadcast auction flips.");
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            logger.error("WebSocket error on connection {}: {}", conn != null ? conn.getRemoteSocketAddress() : "N/A", ex.getMessage(), ex);
            if (conn != null) {
                connections.remove(conn);
            }
        }

        @Override
        public void onStart() {
            logger.info("WebSocket server started successfully on {}", getAddress());
        }

        /**
         * Sends a message to all connected WebSocket clients.
         *
         * @param message The message (JSON string) to broadcast.
         */
        public void broadcast(String message) {
            if (connections.isEmpty()) {
                logger.debug("No WebSocket clients connected to broadcast message.");
                return;
            }
            logger.debug("Broadcasting message to {} clients: {}", connections.size(), message);
            for (WebSocket conn : connections) {
                if (conn.isOpen()) {
                    conn.send(message);
                }
            }
        }
    }
}
