package org.example.auctionbot;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Config {
    private ApiConfig api;
    private OptionsConfig options;

    public ApiConfig getApi() {
        return api;
    }

    public void setApi(ApiConfig api) {
        this.api = api;
    }

    public OptionsConfig getOptions() {
        return options;
    }

    public void setOptions(OptionsConfig options) {
        this.options = options;
    }

    public static class ApiConfig {
        private String hypixel;
        @JsonProperty("page-fetch-delay-ms") // ADDED: Mapping for kebab-case TOML key
        private long pageFetchDelayMs;

        public String getHypixel() {
            return hypixel;
        }

        public void setHypixel(String hypixel) {
            this.hypixel = hypixel;
        }

        public long getPageFetchDelayMs() {
            return pageFetchDelayMs;
        }

        public void setPageFetchDelayMs(long pageFetchDelayMs) {
            this.pageFetchDelayMs = pageFetchDelayMs;
        }
    }

    public static class OptionsConfig {
        @JsonProperty("min-time")
        private int minTime;
        @JsonProperty("add-recombobulator")
        private boolean addRecombobulator;
        @JsonProperty("min-profit")
        private int minProfit;
        @JsonProperty("max-price-cap")
        private double maxPriceCap;

        public int getMinTime() {
            return minTime;
        }

        public void setMinTime(int minTime) {
            this.minTime = minTime;
        }

        public boolean isAddRecombobulator() {
            return addRecombobulator;
        }

        public void setAddRecombobulator(boolean addRecombobulator) {
            this.addRecombobulator = addRecombobulator;
        }

        public int getMinProfit() {
            return minProfit;
        }

        public void setMinProfit(int minProfit) {
            this.minProfit = minProfit;
        }

        public double getMaxPriceCap() {
            return maxPriceCap;
        }

        public void setMaxPriceCap(double maxPriceCap) {
            this.maxPriceCap = maxPriceCap;
        }
    }
}