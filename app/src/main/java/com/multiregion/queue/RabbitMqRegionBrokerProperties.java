package com.multiregion.queue;

public class RabbitMqRegionBrokerProperties {

    private String host = "localhost";
    private int port = 5672;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}
