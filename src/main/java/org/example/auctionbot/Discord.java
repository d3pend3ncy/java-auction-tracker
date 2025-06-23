package org.example.auctionbot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Discord {

    // You should replace this with your actual Discord webhook URL
    private static final String WEBHOOK_URL = "";

    public static void sendWebhookRequest(int price, int estimatedValue, String command) {
        // Create an HttpClient instance
        HttpClient client = HttpClient.newHttpClient();

        // Calculate profit and percentage
        int profit = estimatedValue - price;
        double profitPercentage = (double) profit / price * 100;

        // Construct the JSON payload for the Discord embed using string concatenation for Java 11
        String json = "{"
                + "\"content\": \"Potential flip found!\","
                + "\"embeds\": ["
                + "{"
                + "\"title\": \"Auction Details\","
                + "\"color\": 3066993," // A shade of green for success/positive
                + "\"fields\": ["
                + "{"
                + "\"name\": \"Selling Price\","
                + "\"value\": \"$" + price + "\","
                + "\"inline\": true"
                + "},"
                + "{"
                + "\"name\": \"Estimated Value\","
                + "\"value\": \"$" + estimatedValue + "\","
                + "\"inline\": true"
                + "},"
                + "{"
                + "\"name\": \"Potential Profit\","
                + "\"value\": \"$" + profit + "\","
                + "\"inline\": true"
                + "},"
                + "{"
                + "\"name\": \"Profit Percentage\","
                + "\"value\": \"" + String.format("%.2f%%", profitPercentage) + "\","
                + "\"inline\": true"
                + "},"
                + "{"
                + "\"name\": \"Command\","
                + "\"value\": \"`" + command + "`\","
                + "\"inline\": false"
                + "}"
                + "]"
                + "}"
                + "]"
                + "}";

        // Build the HTTP POST request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WEBHOOK_URL)) // Set the request URI
                .header("Content-Type", "application/json") // Set the Content-Type header
                .POST(HttpRequest.BodyPublishers.ofString(json)) // Set the request body
                .build();

        try {
            // Send the request and get the response
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Check if the request was successful (2xx status code)
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("Webhook sent successfully!");
            } else {
                System.err.println("Failed to send webhook: " + response.statusCode() + " " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Error sending webhook request: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static String getCommand(String uuid) {
        // Remove all dashes from the UUID
        String cleanedUuid = uuid.replaceAll("-", "");
        return "/veiwauction " + cleanedUuid;
    }
}