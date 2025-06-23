package org.example.auctionbot;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.Duration;

public class Auction {
    private String name;
    private double price;
    private double value;
    private double extraValue;
    private String id;
    private String image;
    private JsonNode jsonObject; // Using JsonNode for flexibility

    public Auction(String name, double price, double value, double extraValue, String id, String image, JsonNode jsonObject) {
        this.name = name;
        this.price = price;
        this.value = value;
        this.extraValue = extraValue;
        this.id = id;
        this.image = image;
        this.jsonObject = jsonObject;
    }

    // Getters
    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public double getValue() {
        return value;
    }

    public double getExtraValue() {
        return extraValue;
    }

    public String getId() {
        return id;
    }

    public String getImage() {
        return image;
    }

    public JsonNode getJsonObject() {
        return jsonObject;
    }

    // Custom property equivalent for time_ending
    public String getTimeEnding() {
        long endTimeMillis = jsonObject.get("end").asLong();
        Instant endTime = Instant.ofEpochMilli(endTimeMillis);
        Instant now = Instant.now();

        if (endTime.isBefore(now)) {
            return "Ended";
        }

        Duration duration = Duration.between(now, endTime);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        return String.format("%dm %ds", minutes, seconds);
    }
}