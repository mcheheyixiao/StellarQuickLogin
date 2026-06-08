package cn.stellarworld.quicklogin.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class WebsiteClient {

    private final HttpClient httpClient;
    private final URI consumeUri;
    private final String internalToken;
    private final Duration timeout;

    public WebsiteClient(HttpClient httpClient, URI consumeUri, String internalToken, Duration timeout) {
        this.httpClient = httpClient;
        this.consumeUri = consumeUri;
        this.internalToken = internalToken;
        this.timeout = timeout;
    }

    public CompletableFuture<ConsumeResponse> consume(ConsumeRequest request) {
        JsonObject requestBody = new JsonObject();
        if (request.token() != null && !request.token().isBlank()) {
            requestBody.addProperty("token", request.token());
        }
        requestBody.addProperty("playerName", request.playerName());
        requestBody.addProperty("playerUuid", request.playerUuid());
        requestBody.addProperty("serverId", request.serverId());

        HttpRequest httpRequest = HttpRequest.newBuilder(consumeUri)
            .timeout(timeout)
            .header("Authorization", "Bearer " + internalToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
            .build();

        return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .handle((response, throwable) -> {
                if (throwable != null) {
                    return ConsumeResponse.failure("request_failed", "Failed to reach StellarWorld consume API");
                }
                return parseResponse(response.statusCode(), response.body());
            });
    }

    private ConsumeResponse parseResponse(int statusCode, String body) {
        try {
            JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
            boolean ok = jsonObject.has("ok") && jsonObject.get("ok").getAsBoolean();
            String status = jsonObject.has("status") ? jsonObject.get("status").getAsString() : defaultStatus(statusCode, ok);
            String message = jsonObject.has("message") ? jsonObject.get("message").getAsString() : defaultMessage(statusCode, ok);
            return new ConsumeResponse(ok, status, message);
        } catch (RuntimeException exception) {
            boolean ok = statusCode >= 200 && statusCode < 300;
            return new ConsumeResponse(ok, defaultStatus(statusCode, ok), defaultMessage(statusCode, ok));
        }
    }

    private static String defaultStatus(int statusCode, boolean ok) {
        return ok ? "consumed" : "http_" + statusCode;
    }

    private static String defaultMessage(int statusCode, boolean ok) {
        return ok ? "StellarWorld consume accepted the request" : "HTTP " + statusCode;
    }
}
