package com.ecom.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Plays every backend: records proxied requests and answers 200. Serves the JWKS the Gateway
 * validates against at {@value #GATEWAY_JWKS_PATH} (the Gateway's own fetch, not a proxied call).
 */
final class StubBackend {

    static final String GATEWAY_JWKS_PATH = "/internal/jwks.json";

    record Recorded(String method, String path, Map<String, List<String>> headers, byte[] body) {
        Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst();
        }
    }

    private final HttpServer server;
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();
    private volatile String jwks = "{\"keys\":[]}";

    StubBackend() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    void setJwks(String jwks) {
        this.jwks = jwks;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Recorded> requestsTo(String path) {
        return recorded.stream().filter(r -> r.path().equals(path)).toList();
    }

    Recorded single(String path) {
        List<Recorded> hits = requestsTo(path);
        if (hits.size() != 1) {
            throw new AssertionError("expected exactly one request to " + path + " but got " + hits.size());
        }
        return hits.getFirst();
    }

    private void handle(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String path = ex.getRequestURI().getPath();
        byte[] response;
        if (path.equals(GATEWAY_JWKS_PATH)) {
            response = jwks.getBytes(StandardCharsets.UTF_8);
        } else {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k, List.copyOf(v)));
            recorded.add(new Recorded(ex.getRequestMethod(), path, headers, body));
            response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, response.length);
        ex.getResponseBody().write(response);
        ex.close();
    }
}
