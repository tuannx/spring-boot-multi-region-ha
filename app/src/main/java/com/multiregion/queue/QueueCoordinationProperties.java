package com.multiregion.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "queues")
public class QueueCoordinationProperties {

    private boolean enabled = true;
    private long pollIntervalMs = 5000;
    private long takeoverPollIntervalMs = 60_000;
    private long takeoverMaxDurationMs = 1_800_000;
    private List<String> names = new ArrayList<>(List.of("orders"));
    private List<String> regions = new ArrayList<>(List.of("us-east-1", "eu-west-1"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public long getTakeoverPollIntervalMs() {
        return takeoverPollIntervalMs;
    }

    public void setTakeoverPollIntervalMs(long takeoverPollIntervalMs) {
        this.takeoverPollIntervalMs = takeoverPollIntervalMs;
    }

    public long getTakeoverMaxDurationMs() {
        return takeoverMaxDurationMs;
    }

    public void setTakeoverMaxDurationMs(long takeoverMaxDurationMs) {
        this.takeoverMaxDurationMs = takeoverMaxDurationMs;
    }

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public List<String> getRegions() {
        return regions;
    }

    public void setRegions(List<String> regions) {
        this.regions = regions;
    }
}
