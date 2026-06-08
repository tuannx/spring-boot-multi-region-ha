package com.multiregion.model;

/**
 * DTO for health check responses.
 */
public class HealthResponse {

    private String status;
    private String region;
    private String role;
    private String writerNode;
    private boolean dbConnected;
    private boolean active;
    private String timestamp;

    public HealthResponse() {
        this.timestamp = java.time.Instant.now().toString();
    }

    public HealthResponse(String status, String region, String role,
                          String writerNode, boolean dbConnected, boolean active) {
        this.status = status;
        this.region = region;
        this.role = role;
        this.writerNode = writerNode;
        this.dbConnected = dbConnected;
        this.active = active;
        this.timestamp = java.time.Instant.now().toString();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getWriterNode() {
        return writerNode;
    }

    public void setWriterNode(String writerNode) {
        this.writerNode = writerNode;
    }

    public boolean isDbConnected() {
        return dbConnected;
    }

    public void setDbConnected(boolean dbConnected) {
        this.dbConnected = dbConnected;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
}
