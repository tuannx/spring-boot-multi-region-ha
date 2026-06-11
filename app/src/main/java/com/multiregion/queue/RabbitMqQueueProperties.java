package com.multiregion.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "queues.rabbitmq")
public class RabbitMqQueueProperties {

    private String username = "appuser";
    private String password = "apppass";
    private String queueNamePattern = "%s.%s";
    private String retryQueueSuffix = ".retry";
    private String dlqSuffix = ".dlq";
    private long retryDelayMs = 5000;
    private long visibilityTimeoutMs = 30000;
    private long recoveryIntervalMs = 5000;
    private int prefetchCount = 1;
    private boolean recreateTopology = true;
    private Map<String, RabbitMqRegionBrokerProperties> brokers = new HashMap<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getQueueNamePattern() {
        return queueNamePattern;
    }

    public void setQueueNamePattern(String queueNamePattern) {
        this.queueNamePattern = queueNamePattern;
    }

    public String getRetryQueueSuffix() {
        return retryQueueSuffix;
    }

    public void setRetryQueueSuffix(String retryQueueSuffix) {
        this.retryQueueSuffix = retryQueueSuffix;
    }

    public String getDlqSuffix() {
        return dlqSuffix;
    }

    public void setDlqSuffix(String dlqSuffix) {
        this.dlqSuffix = dlqSuffix;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    public long getVisibilityTimeoutMs() {
        return visibilityTimeoutMs;
    }

    public void setVisibilityTimeoutMs(long visibilityTimeoutMs) {
        this.visibilityTimeoutMs = visibilityTimeoutMs;
    }

    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public int getPrefetchCount() {
        return prefetchCount;
    }

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    public boolean isRecreateTopology() {
        return recreateTopology;
    }

    public void setRecreateTopology(boolean recreateTopology) {
        this.recreateTopology = recreateTopology;
    }

    public Map<String, RabbitMqRegionBrokerProperties> getBrokers() {
        return brokers;
    }

    public void setBrokers(Map<String, RabbitMqRegionBrokerProperties> brokers) {
        this.brokers = brokers;
    }

    public String queueName(String logicalQueueName, String region) {
        return String.format(queueNamePattern, logicalQueueName, region);
    }

    public String retryQueueName(String logicalQueueName, String region) {
        return queueName(logicalQueueName, region) + retryQueueSuffix;
    }

    public String dlqName(String logicalQueueName, String region) {
        return queueName(logicalQueueName, region) + dlqSuffix;
    }
}
