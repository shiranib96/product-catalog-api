package nl.casus.catalog.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

// Fake source API for the tests, on a random port (the mock server for manual testing is mock-source/server.py)
public final class SourceStub {

    private static HttpServer server;
    private static volatile List<String> products = List.of();
    private static volatile int productsStatus = 200;
    private static volatile int webhooksStatus = 201;
    private static final List<String> webhookRegistrations = new CopyOnWriteArrayList<>();

    private SourceStub() {
    }

    public static synchronized String baseUrl() {
        if (server == null) {
            start();
        }
        return "http://localhost:" + server.getAddress().getPort();
    }

    public static void reset() {
        products = List.of();
        productsStatus = 200;
        webhooksStatus = 201;
        webhookRegistrations.clear();
    }

    public static void failWebhookRegistrationsWith(int status) {
        webhooksStatus = status;
    }

    public static void setProducts(String... productJson) {
        products = List.of(productJson);
    }

    public static void failProductRequestsWith(int status) {
        productsStatus = status;
    }

    public static List<String> webhookRegistrations() {
        return List.copyOf(webhookRegistrations);
    }

    private static void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/products", SourceStub::handleProducts);
        server.createContext("/api/webhooks", SourceStub::handleWebhooks);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    private static void handleProducts(HttpExchange exchange) throws IOException {
        if (productsStatus != 200) {
            respond(exchange, productsStatus, "{}");
            return;
        }
        Map<String, String> query = query(exchange);
        int page = Integer.parseInt(query.getOrDefault("page", "0"));
        int size = Integer.parseInt(query.getOrDefault("size", "50"));
        List<String> all = products;
        List<String> items = all.stream().skip((long) page * size).limit(size).toList();
        int totalPages = (all.size() + size - 1) / size;
        respond(exchange, 200, """
                {"items": [%s], "page": %d, "size": %d, "totalItems": %d, "totalPages": %d}
                """.formatted(String.join(",", items), page, size, all.size(), totalPages));
    }

    private static void handleWebhooks(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        if (webhooksStatus == 201) {
            webhookRegistrations.add(body);
        }
        respond(exchange, webhooksStatus, "{}");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], parts.length > 1 ? parts[1] : "");
            }
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
