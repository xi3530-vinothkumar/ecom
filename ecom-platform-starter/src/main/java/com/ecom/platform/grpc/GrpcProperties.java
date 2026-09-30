package com.ecom.platform.grpc;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <pre>
 * ecom.grpc.server.port: 9090
 * ecom.grpc.clients.catalog.address: catalog-service:9090
 * ecom.grpc.clients.catalog.deadline: 2s
 * </pre>
 */
@ConfigurationProperties("ecom.grpc")
public class GrpcProperties {

    private final Server server = new Server();
    private final Map<String, Client> clients = new HashMap<>();

    public Server getServer() {
        return server;
    }

    public Map<String, Client> getClients() {
        return clients;
    }

    public static class Server {
        private boolean enabled = true;
        private int port = 9090;
        private Duration shutdownGrace = Duration.ofSeconds(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public Duration getShutdownGrace() {
            return shutdownGrace;
        }

        public void setShutdownGrace(Duration shutdownGrace) {
            this.shutdownGrace = shutdownGrace;
        }
    }

    public static class Client {
        private String address;
        private Duration deadline = Duration.ofSeconds(2);

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public Duration getDeadline() {
            return deadline;
        }

        public void setDeadline(Duration deadline) {
            this.deadline = deadline;
        }
    }
}
