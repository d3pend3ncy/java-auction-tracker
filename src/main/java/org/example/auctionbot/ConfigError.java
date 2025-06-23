package org.example.auctionbot;

public class ConfigError extends RuntimeException {
    public ConfigError(String message) {
        super(message);
        System.err.println("*----- Please configure the bot in 'config.toml' before running -----*");
        System.err.println(message);
    }
}