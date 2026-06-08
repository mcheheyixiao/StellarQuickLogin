package cn.stellarworld.quicklogin.website;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void consumeSendsBearerHeaderAndParsesSuccessfulResponse() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"ok\":true,\"status\":\"consumed\",\"message\":\"accepted\"}");
        });

        WebsiteClient client = new WebsiteClient(
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/quick-login/consume"),
            "internal-secret",
            Duration.ofSeconds(3)
        );

        ConsumeResponse response = client.consume(new ConsumeRequest("ticket-123", "Alice", "uuid-1", "survival-1")).join();

        assertTrue(response.ok());
        assertEquals("consumed", response.status());
        assertEquals("accepted", response.message());
        assertEquals("Bearer internal-secret", authHeader.get());
        assertTrue(body.get().contains("\"token\":\"ticket-123\""));
        assertTrue(body.get().contains("\"playerName\":\"Alice\""));
    }

    @Test
    void directConsumeFallbackOmitsTokenWhenNotPresent() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 401, "{\"ok\":false,\"status\":\"unauthorized\",\"message\":\"bad token\"}");
        });

        WebsiteClient client = new WebsiteClient(
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/quick-login/consume"),
            "internal-secret",
            Duration.ofSeconds(3)
        );

        ConsumeResponse response = client.consume(new ConsumeRequest(null, "Alice", "uuid-1", "survival-1")).join();

        assertFalse(response.ok());
        assertEquals("unauthorized", response.status());
        assertFalse(body.get().contains("\"token\""));
    }

    private void startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/quick-login/consume", handler);
        server.start();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
