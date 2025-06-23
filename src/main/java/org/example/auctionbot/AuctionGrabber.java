package org.example.auctionbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dewy.nbt.Nbt;
import dev.dewy.nbt.api.Tag;
import dev.dewy.nbt.io.CompressionType;
import dev.dewy.nbt.tags.collection.CompoundTag;
import dev.dewy.nbt.tags.collection.ListTag;
import dev.dewy.nbt.tags.primitive.ByteTag;
import dev.dewy.nbt.tags.primitive.IntTag;
import dev.dewy.nbt.tags.primitive.NumericalTag;
import com.github.ansell.jdefaultdict.JDefaultDict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

import static org.example.auctionbot.Constants.*; // Ensure Constants is imported for AUCTION_ENDPOINT

public class AuctionGrabber {
    private static final Logger logger = LoggerFactory.getLogger(AuctionGrabber.class);
    private final Config config;
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper jsonMapper;
    private final Nbt nbtDecoder;

    // Stores the lowest BIN prices seen for each item name (used for profit calculation)
    private JDefaultDict<String, List<Double>> allCurrentBinPrices;
    private final JDefaultDict<String, List<Double>> minPrice;
    private final List<String> notifiedUuids; // For tracking already notified auctions

    // Map to store the current active auctions by their UUID for efficient lookup
    private Map<String, JsonNode> previousAuctionSnapshot;
    // This will hold the newly fetched auctions before processing
    private List<JsonNode> latestFetchedAuctions;

    private List<JsonNode> newly_added_auctions;

    private long lastUpdate = 0;
    private int runCounter = 5; // Used for periodic full market price re-indexing (initially 5 to trigger immediately)

    public AuctionGrabber(Config config, JDefaultDict<String, List<Double>> minPrice, List<String> notifiedUuids) {
        this.config = config;
        this.apiKey = config.getApi().getHypixel();
        this.minPrice = minPrice;
        this.notifiedUuids = notifiedUuids;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.jsonMapper = new ObjectMapper();
        this.nbtDecoder = new Nbt();
        this.allCurrentBinPrices = new JDefaultDict<>(k -> new ArrayList<>());
        this.previousAuctionSnapshot = new HashMap<>(); // Initialize the snapshot map
        this.latestFetchedAuctions = new ArrayList<>(); // Initialize the list for new data
        this.newly_added_auctions = new ArrayList<>(); // Initialize the new list
    }

    public CompletableFuture<Void> receiveAuctions() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Fetch page 0 first to get totalPages and lastUpdated timestamp
                HttpResponse<String> response = httpClient.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create(String.format(AUCTION_ENDPOINT, apiKey, 0)))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                ).join();

                JsonNode page0 = jsonMapper.readTree(response.body());

                if (!page0.get("success").asBoolean()) {
                    logger.error("Hypixel API returned an error: {}", page0.get("cause").asText());
                    return;
                }

                long currentLastUpdated = page0.get("lastUpdated").asLong();
                // Check if the auction data has been updated since the last check
                // If it hasn't and we already have a previous snapshot (meaning it's not the very first run)
                if (currentLastUpdated == lastUpdate && !previousAuctionSnapshot.isEmpty()) {
                    logger.info("Auction data not updated since last check. Skipping full processing.");
                    // No new data, so newly_added_auctions will remain empty, which is correct
                    this.newly_added_auctions.clear(); // Ensure it's cleared if no new data
                    return;
                }
                this.lastUpdate = currentLastUpdated;

                int totalPages = page0.get("totalPages").asInt();
                logger.info("Fetching {} auction pages...", totalPages + 1);

                List<CompletableFuture<List<JsonNode>>> pageFutures = IntStream.rangeClosed(0, totalPages)
                        .mapToObj(this::getPage)
                        .collect(Collectors.toList());

                // Wait for all page fetches to complete
                CompletableFuture.allOf(pageFutures.toArray(new CompletableFuture[0])).join();

                // Aggregate all fetched auctions into a single list
                this.latestFetchedAuctions = pageFutures.stream()
                        .flatMap(future -> future.join().stream())
                        .collect(Collectors.toList());

                // --- Real-time comparison logic ---
                Map<String, JsonNode> newSnapshotMap = new HashMap<>();
                for (JsonNode auction : latestFetchedAuctions) {
                    // CRITICAL: Only add active BIN auctions to the snapshot
                    if (auction.has("bin") && auction.get("bin").asBoolean() && auction.get("end").asLong() > System.currentTimeMillis()) {
                        newSnapshotMap.put(auction.get("uuid").asText(), auction);
                    }
                }

                this.newly_added_auctions.clear(); // Clear for the current cycle

                // Identify newly added BIN auctions by comparing with the previous snapshot
                for (Map.Entry<String, JsonNode> entry : newSnapshotMap.entrySet()) {
                    if (!previousAuctionSnapshot.containsKey(entry.getKey())) {
                        this.newly_added_auctions.add(entry.getValue());
                    }
                }

                // Identify ended auctions (useful for internal tracking, though not directly used for flips here)
                Set<String> endedAuctionUuids = new HashSet<>();
                for (Map.Entry<String, JsonNode> entry : previousAuctionSnapshot.entrySet()) {
                    if (!newSnapshotMap.containsKey(entry.getKey())) {
                        endedAuctionUuids.add(entry.getKey());
                    }
                }

                logger.info("Detected {} new BIN auctions and {} ended BIN auctions.",
                        newly_added_auctions.size(), endedAuctionUuids.size());

                // Update the previous snapshot to the current one for the next cycle
                this.previousAuctionSnapshot = newSnapshotMap;

                // --- End of Real-time comparison logic ---

                // Periodic market price re-indexing and allCurrentBinPrices update
                // This section effectively re-indexes ALL active BINs and updates minPrice.
                // This ensures minPrice is always based on the latest available market data.
                if (runCounter == 5) {
                    logger.info("Performing periodic market price re-index...");
                    this.allCurrentBinPrices.clear(); // Clear previous BIN prices for re-calculation

                    // Populate allCurrentBinPrices and update minPrice for all other items from current AH data
                    for (JsonNode auction : latestFetchedAuctions) {
                        // Only consider active BIN auctions for market price indexing
                        if (auction.has("bin") && auction.get("bin").asBoolean() && auction.get("end").asLong() > System.currentTimeMillis()) {
                            try {
                                ItemInfo itemInfo = decodeItem(auction.get("item_bytes").asText());
                                String name = getName(itemInfo.nbtData); // Get the normalized item name
                                // Add the current BIN price (adjusted for count if stackable)
                                allCurrentBinPrices.get(name).add(auction.get("starting_bid").asDouble() / itemInfo.count);
                            } catch (Exception e) {
                                logger.warn("Error decoding item for auction {} during market re-index: {}", auction.get("uuid").asText(), e.getMessage());
                            }
                        }
                    }

                    // Update minPrice for all indexed items
                    allCurrentBinPrices.forEach((k, v) -> {
                        if (!v.isEmpty()) {
                            minPrice.get(k).clear(); // Clear old min price
                            minPrice.get(k).add(v.stream().min(Double::compare).orElse(0.0)); // Set new min price
                        }
                    });

                    // Handle DRAGON_SLAYER hardcoded price (consider making this dynamic if possible)
                    minPrice.get("DRAGON_SLAYER").clear();
                    minPrice.get("DRAGON_SLAYER").add(1_000_000.0); // Example, adjust as per market or remove if dynamic

                    logger.info("Market price re-index complete. Current min prices: {}", minPrice);
                }
                // Cycle the runCounter
                runCounter = (runCounter == 5) ? 0 : runCounter + 1;

            } catch (IOException e) {
                logger.error("Error receiving auctions: {}", e.getMessage(), e);
            } catch (Exception e) {
                logger.error("An unexpected error occurred during auction reception: {}", e.getMessage(), e);
            }
        });
    }

    private CompletableFuture<List<JsonNode>> getPage(int page) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Introduce a delay to avoid hitting API rate limits
                Thread.sleep(config.getApi().getPageFetchDelayMs());
                HttpResponse<String> response = httpClient.sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create(String.format(AUCTION_ENDPOINT, apiKey, page)))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                ).join();
                JsonNode auctionsJson = jsonMapper.readTree(response.body());

                if (!auctionsJson.get("success").asBoolean()) {
                    logger.warn("API Error on page {}: {}", page, auctionsJson.get("cause").asText());
                    return Collections.emptyList();
                }

                List<JsonNode> auctions = new ArrayList<>();
                if (auctionsJson.has("auctions")) {
                    for (JsonNode auction : auctionsJson.get("auctions")) {
                        // CRITICAL: Only add active BIN auctions to the list for processing
                        if (auction.has("bin") && auction.get("bin").asBoolean() && auction.get("end").asLong() > System.currentTimeMillis()) {
                            auctions.add(auction);
                        }
                    }
                }
                return auctions;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Page {} fetch interrupted: {}", page, e.getMessage());
                return Collections.emptyList();
            } catch (IOException e) {
                logger.warn("Error fetching page {}: {}", page, e.getMessage());
                return Collections.emptyList();
            } catch (Exception e) {
                logger.warn("An unexpected error occurred while fetching page {}: {}", page, e.getMessage(), e);
                return Collections.emptyList();
            }
        });
    }

    public CompletableFuture<List<Auction>> checkFlip() {
        return CompletableFuture.supplyAsync(() -> {
            List<Auction> flips = new ArrayList<>();
            // RENAMED: Use maxPriceCap from config
            double maxAllowedPrice = config.getOptions().getMaxPriceCap();

            // Iterate over only the newly added auctions identified in receiveAuctions
            for (JsonNode item : newly_added_auctions) {
                String auctionUuid = item.get("uuid").asText();
                if (notifiedUuids.contains(auctionUuid)) {
                    logger.debug("Skipping already notified auction: {}", auctionUuid);
                    continue;
                }

                double price = item.get("starting_bid").asDouble(); // For BIN, this is the purchase price

                // CRITICAL: Check against the configured max purchase price
                if (price > maxAllowedPrice) {
                    logger.debug("Skipping auction {} ({} coins) - exceeds max purchase price ({} coins).", auctionUuid, price, maxAllowedPrice);
                    continue;
                }

                try {
                    ItemInfo itemInfo = decodeItem(item.get("item_bytes").asText());
                    CompoundTag nbtData = itemInfo.nbtData;

                    // Ensure essential NBT data is present for processing
                    if (nbtData.contains("tag") && nbtData.getCompound("tag") != null &&
                            nbtData.getCompound("tag").contains("ExtraAttributes") && nbtData.getCompound("tag").getCompound("ExtraAttributes") != null) {

                        CompoundTag extraAttributes = nbtData.getCompound("tag").getCompound("ExtraAttributes");
                        String name = getName(nbtData); // Get the normalized item name

                        double addedValue = 0;

                        // Hot Potato Book and Fuming Potato Book value calculation
                        if (extraAttributes.contains("hot_potato_count") && extraAttributes.getInt("hot_potato_count") != null) {
                            int hpbCount = extraAttributes.getInt("hot_potato_count").getValue();
                            if (hpbCount > 0) {
                                double hpbPrice = minPrice.get("HOT_POTATO_BOOK").stream().findFirst().orElse(0.0);
                                double fumingPrice = minPrice.get("FUMING_POTATO_BOOK").stream().findFirst().orElse(0.0);

                                if (hpbCount <= 10) {
                                    addedValue += hpbPrice * hpbCount;
                                } else {
                                    addedValue += hpbPrice * 10;
                                    addedValue += fumingPrice * (hpbCount - 10);
                                }
                            }
                        }

                        // Recombobulator value
                        if (config.getOptions().isAddRecombobulator() && extraAttributes.contains("rarity_upgrades") && extraAttributes.getInt("rarity_upgrades") != null && extraAttributes.getInt("rarity_upgrades").getValue() > 0) {
                            addedValue += minPrice.get("RECOMBOBULATOR_3000").stream().findFirst().orElse(0.0);
                        }

                        // Wood Singularity value
                        if (extraAttributes.contains("wood_singularity_count") && extraAttributes.getInt("wood_singularity_count") != null &&
                                extraAttributes.getInt("wood_singularity_count").getValue() == 1) {
                            addedValue += minPrice.get("WOOD_SINGULARITY").stream().findFirst().orElse(0.0);
                        }

                        // Enchantment value
                        if (extraAttributes.contains("enchantments") && extraAttributes.getCompound("enchantments") != null &&
                                !itemInfo.rawName.equals("ENCHANTED_BOOK") && // Avoid double counting for books themselves
                                extraAttributes.contains("originTag") && extraAttributes.getString("originTag") != null &&
                                !extraAttributes.getString("originTag").getValue().equals("UNKNOWN") &&
                                !item.get("item_lore").asText().contains("DUNGEON")) { // Exclude dungeon items which may have altered values

                            CompoundTag enchantments = extraAttributes.getCompound("enchantments");
                            for (String enchKey : enchantments.keySet()) {
                                // Check if the enchantment is in our predefined list and its level is sufficient
                                if (ENCHANTS.containsKey(enchKey) && enchantments.contains(enchKey) && enchantments.getInt(enchKey) != null) {
                                    int enchantLevel = enchantments.getInt(enchKey).getValue();
                                    if (ENCHANTS.get(enchKey) <= enchantLevel) {
                                        double enchantPrice = minPrice.get(enchKey.toUpperCase()).stream().findFirst().orElse(0.0);
                                        if (enchKey.equals("dragon_hunter") || enchKey.startsWith("ultimate")) {
                                            addedValue += Math.pow(2, enchantLevel - 1) * enchantPrice;
                                        } else {
                                            addedValue += enchantPrice;
                                        }
                                    }
                                }
                            }
                        }

                        List<Double> itemBinPrices = allCurrentBinPrices.get(name);
                        double baseItemLowestBin = itemBinPrices.isEmpty() ? 0.0 : itemBinPrices.stream().min(Double::compare).orElse(0.0);
                        double itemTotalEstimatedValue = baseItemLowestBin + addedValue;

                        // Check for profit condition
                        if (price < itemTotalEstimatedValue - config.getOptions().getMinProfit()) {
                            Auction auctionObj = new Auction(
                                    name,
                                    price,
                                    itemTotalEstimatedValue,
                                    addedValue,
                                    formatUuid(auctionUuid),
                                    getImageUrl(nbtData, itemInfo.rawName, jsonMapper),
                                    item
                            );
                            flips.add(auctionObj);
                            notifiedUuids.add(auctionUuid);
                            logger.info("Found potential flip! Item: {}, Price: {}, Estimated Value: {}, Profit: {}",
                                    name, price, itemTotalEstimatedValue, (itemTotalEstimatedValue - price));
                            Discord.sendWebhookRequest((int) price, (int) itemTotalEstimatedValue, Discord.getCommand(auctionUuid));
                        }
                    } else {
                        logger.debug("Skipping auction {} due to missing or invalid 'tag' or 'ExtraAttributes' in NBT data (likely not a relevant item).", auctionUuid);
                    }
                } catch (Exception e) {
                    logger.warn("Error processing auction {} for flip check: {}", auctionUuid, e.getMessage(), e);
                }
            }
            return flips;
        });
    }

    private static class ItemInfo {
        CompoundTag nbtData;
        String rawName;
        int count;

        public ItemInfo(CompoundTag nbtData, String rawName, int count) {
            this.nbtData = nbtData;
            this.rawName = rawName;
            this.count = count;
        }
    }

    private ItemInfo decodeItem(String itemBytesBase64) throws IOException {
        byte[] decodedBytes = Base64.getDecoder().decode(itemBytesBase64);
        CompoundTag rootTag = null;
        Exception firstAttemptException = null;

        // Attempt 1: GZIP decompression + NBT decoding
        try (ByteArrayInputStream bais = new ByteArrayInputStream(decodedBytes);
             GZIPInputStream gis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            byte[] decompressedBytes = baos.toByteArray();

            rootTag = nbtDecoder.fromByteArray(decompressedBytes);

        } catch (ZipException e) {
            firstAttemptException = e;
            logger.debug("First NBT decoding attempt (GZIP, length {}) failed. Not a valid GZIP stream. Error: {}. Trying uncompressed...", decodedBytes.length, e.getMessage());
        } catch (IOException e) {
            firstAttemptException = e;
            logger.debug("First NBT decoding attempt (GZIP, length {}) failed due to IO. Error: {}. Trying uncompressed...", decodedBytes.length, e.getMessage());
        } catch (Exception e) { // Catch any other exceptions during GZIP+NBT
            firstAttemptException = e;
            logger.debug("First NBT decoding attempt (GZIP, length {}) failed unexpectedly. Error: {}. Trying uncompressed...", decodedBytes.length, e.getMessage());
        }


        // Attempt 2: Direct NBT decoding (uncompressed) if first attempt failed or was skipped
        if (rootTag == null) {
            try {
                rootTag = nbtDecoder.fromByteArray(decodedBytes);
                logger.debug("NBT decoding successful on second attempt (uncompressed).");
            } catch (Exception e) {
                String errorDetails = "Failed to decode NBT data after trying both gzipped and uncompressed methods. ";
                if (firstAttemptException != null) {
                    errorDetails += "First attempt error (" + firstAttemptException.getClass().getSimpleName() + "): " + firstAttemptException.getMessage();
                }
                errorDetails += " Second attempt error (" + e.getClass().getSimpleName() + "): " + e.getMessage();

                IOException combinedException = new IOException(errorDetails, e);
                if (firstAttemptException != null) {
                    combinedException.addSuppressed(firstAttemptException);
                }
                throw combinedException;
            }
        }

        if (rootTag == null) {
            throw new IOException("NBT root tag could not be decoded by any method (internal safeguard for length " + decodedBytes.length + "). This indicates a critical issue with the NBT data.");
        }

        // Validate NBT structure
        if (!rootTag.contains("i") || !(rootTag.getList("i") instanceof ListTag)) {
            throw new IOException("NBT data (length " + decodedBytes.length + ") does not contain a 'i' ListTag as expected. Root type: " + rootTag.getClass().getSimpleName());
        }
        ListTag itemList = rootTag.getList("i");

        if (itemList.isEmpty() || !(itemList.get(0) instanceof CompoundTag)) {
            throw new IOException("Item list 'i' (length " + decodedBytes.length + ") is empty or its first element is not a CompoundTag.");
        }
        CompoundTag file = (CompoundTag) itemList.get(0);

        // Deeper validation for ExtraAttributes.id
        if (!file.contains("tag") || !(file.get("tag") instanceof CompoundTag) ||
                !file.getCompound("tag").contains("ExtraAttributes") || !(file.getCompound("tag").get("ExtraAttributes") instanceof CompoundTag) ||
                !file.getCompound("tag").getCompound("ExtraAttributes").contains("id") || !(file.getCompound("tag").getCompound("ExtraAttributes").get("id") instanceof dev.dewy.nbt.tags.primitive.StringTag)) {
            throw new IOException("NBT data (length " + decodedBytes.length + ") is missing expected 'tag.ExtraAttributes.id' structure. Cannot determine item ID.");
        }

        int count;
        if (file.contains("Count")) {
            Tag countTag = file.get("Count");
            if (countTag instanceof ByteTag) {
                count = ((ByteTag) countTag).getValue();
            } else if (countTag instanceof IntTag) { // Added explicit IntTag check
                count = ((IntTag) countTag).getValue();
            }
            else if (countTag instanceof NumericalTag) { // Fallback for other numerical types
                count = ((NumericalTag) countTag).getValue().intValue();
            } else {
                logger.warn("Unsupported NBT tag type for 'Count' field: {}. Defaulting to 1.", countTag.getClass().getSimpleName());
                count = 1;
            }
        } else {
            logger.warn("NBT data is missing 'Count' field. Defaulting to 1 for length {}.", decodedBytes.length);
            count = 1;
        }

        String rawName = file.getCompound("tag").getCompound("ExtraAttributes").getString("id").getValue();
        return new ItemInfo(file, rawName, count);
    }

    private String formatUuid(String uuid) {
        if (uuid == null || uuid.length() != 32) {
            return uuid; // Return as is if not a standard UUID format
        }
        return String.format("%s-%s-%s-%s-%s", uuid.substring(0, 8), uuid.substring(8, 12), uuid.substring(12, 16), uuid.substring(16, 20), uuid.substring(20, 32));
    }

    private String getName(CompoundTag nbtData) {
        CompoundTag tag = nbtData.getCompound("tag");
        if (tag == null || !tag.contains("ExtraAttributes")) {
            return "UNKNOWN_ITEM";
        }
        CompoundTag extraAttributes = tag.getCompound("ExtraAttributes");
        if (extraAttributes == null || !extraAttributes.contains("id") || extraAttributes.getString("id") == null) {
            return "UNKNOWN_ITEM";
        }

        String name = extraAttributes.getString("id").getValue();

        // Special handling for ENCHANTED_BOOK to get the enchantment name
        if (name.equals("ENCHANTED_BOOK")) {
            if (extraAttributes.contains("enchantments") && extraAttributes.getCompound("enchantments") != null) {
                CompoundTag enchantments = extraAttributes.getCompound("enchantments");
                if (!enchantments.keySet().isEmpty()) {
                    // Get the first enchantment key (assuming enchanted books usually have one main enchantment)
                    String enchKey = enchantments.keySet().iterator().next();
                    // Ensure the enchantment is known and has a level
                    if (ENCHANTS.containsKey(enchKey) && enchantments.contains(enchKey) && enchantments.getInt(enchKey) != null) {
                        return enchKey.toUpperCase(); // Return the enchantment name (e.g., "ULTIMATE_WISE")
                    }
                }
            }
        } else if (name.equals("PET")) {
            try {
                if (extraAttributes.contains("petInfo") && extraAttributes.getString("petInfo") != null) {
                    JsonNode petJson = jsonMapper.readTree(extraAttributes.getString("petInfo").getValue());
                    String rarity = petJson.get("tier").asText();
                    // Adjust rarity if it's an EPIC pet holding a TIER_BOOST item
                    if (petJson.has("heldItem") && "PET_ITEM_TIER_BOOST".equals(petJson.get("heldItem").asText()) && "EPIC".equals(rarity)) {
                        rarity = "LEGENDARY";
                    }
                    return String.format("%s_%s_PET", rarity, petJson.get("type").asText());
                }
            } catch (IOException e) {
                logger.warn("Error parsing petInfo for NBT to determine name: {}", e.getMessage());
            }
        }
        return name; // Default to the raw ID
    }

    private String getImageUrl(CompoundTag nbtData, String rawName, ObjectMapper jsonMapper) {
        // Default image URL
        String imageUrl = String.format("https://sky.lea.moe/item/%s", rawName);

        if (nbtData.contains("tag") && nbtData.getCompound("tag") != null) {
            CompoundTag tag = nbtData.getCompound("tag");
            // Check for SkullOwner for custom heads
            if (tag.contains("SkullOwner") && tag.getCompound("SkullOwner") != null) {
                CompoundTag skullOwnerTag = tag.getCompound("SkullOwner");
                if (skullOwnerTag.contains("Properties") && skullOwnerTag.getList("properties") != null) {
                    ListTag texturesList = skullOwnerTag.getList("properties");
                    if (!texturesList.isEmpty() && texturesList.get(0) instanceof CompoundTag) {
                        CompoundTag textureCompound = (CompoundTag) texturesList.get(0);
                        if (textureCompound.contains("Value") && textureCompound.getString("Value") != null) {
                            String base64Texture = textureCompound.getString("Value").getValue();
                            try {
                                String decodedJsonString = new String(Base64.getDecoder().decode(base64Texture), "UTF-8");
                                JsonNode skullOwnerJson = jsonMapper.readTree(decodedJsonString);
                                if (skullOwnerJson.has("textures") && skullOwnerJson.get("textures").has("SKIN")) {
                                    String skinUrl = skullOwnerJson.get("textures").get("SKIN").get("url").asText();
                                    String[] parts = skinUrl.split("texture/", 2);
                                    if (parts.length > 1) {
                                        imageUrl = "https://sky.lea.moe/head/" + parts[1]; // Use Sky.lea.moe head viewer
                                    }
                                }
                            } catch (IOException e) {
                                logger.warn("Error decoding or parsing SkullOwner texture for image URL: {}", e.getMessage());
                            }
                        }
                    }
                }
            }
        }
        return imageUrl;
    }

    public JDefaultDict<String, List<Double>> getAllCurrentBinPrices() {
        return allCurrentBinPrices;
    }

    public List<JsonNode> getNewlyAddedAuctions() {
        return newly_added_auctions;
    }
}