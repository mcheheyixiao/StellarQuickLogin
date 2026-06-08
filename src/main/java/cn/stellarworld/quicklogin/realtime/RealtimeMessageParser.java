package cn.stellarworld.quicklogin.realtime;

import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class RealtimeMessageParser {

    private RealtimeMessageParser() {
    }

    public static Optional<ParsedPreauthorizeMessage> parse(
        String rawMessage,
        String expectedServerId,
        long maxTtlMillis,
        LongSupplier nowSupplier
    ) {
        JsonObject root;
        try {
            root = JsonParser.parseString(rawMessage).getAsJsonObject();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }

        if (!"quicklogin.preauthorize".equalsIgnoreCase(readMessageType(root))) {
            return Optional.empty();
        }

        String requestId = stringValue(root.get("requestId"));
        JsonObject payload = objectValue(root.get("payload"));
        if (payload == null) {
            JsonObject commandObject = objectValue(root.get("command"));
            payload = commandObject == null ? null : objectValue(commandObject.get("payload"));
        }

        if (payload == null) {
            return Optional.of(new ParsedPreauthorizeMessage(ParseStatus.INVALID_PAYLOAD, requestId, null, "Missing payload"));
        }

        String messageServerId = firstNonBlank(
            stringValue(root.get("serverId")),
            stringValue(payload.get("serverId"))
        );
        if (messageServerId == null || !messageServerId.equalsIgnoreCase(expectedServerId)) {
            return Optional.of(new ParsedPreauthorizeMessage(ParseStatus.SERVER_MISMATCH, requestId, null, "serverId does not match this plugin"));
        }

        String playerName = blankToNull(stringValue(payload.get("playerName")));
        UUID playerUuid = parseUuid(blankToNull(stringValue(payload.get("playerUuid"))));
        String token = blankToNull(stringValue(payload.get("token")));
        Long websiteUserId = parseLong(payload.get("websiteUserId"));
        if (token == null || (playerUuid == null && playerName == null)) {
            return Optional.of(new ParsedPreauthorizeMessage(ParseStatus.INVALID_PAYLOAD, requestId, null, "Missing player identity or token"));
        }

        long now = nowSupplier.getAsLong();
        long expiresAtMillis = resolveExpiresAtMillis(payload, now, maxTtlMillis);
        if (expiresAtMillis <= now) {
            return Optional.of(new ParsedPreauthorizeMessage(ParseStatus.EXPIRED, requestId, null, "Ticket is already expired"));
        }

        QuickLoginTicket ticket = new QuickLoginTicket(
            token,
            playerName,
            playerUuid,
            websiteUserId,
            Math.min(expiresAtMillis, now + maxTtlMillis),
            requestId
        );
        return Optional.of(new ParsedPreauthorizeMessage(ParseStatus.OK, requestId, ticket, null));
    }

    private static String readMessageType(JsonObject root) {
        String direct = firstNonBlank(
            stringValue(root.get("type")),
            stringValue(root.get("event")),
            stringValue(root.get("action"))
        );
        if (direct != null) {
            return direct;
        }

        JsonObject command = objectValue(root.get("command"));
        return command == null ? null : stringValue(command.get("type"));
    }

    private static long resolveExpiresAtMillis(JsonObject payload, long now, long maxTtlMillis) {
        Long expiresAt = parseTimestamp(payload.get("expiresAt"));
        if (expiresAt != null) {
            return expiresAt;
        }

        Long ttlSeconds = parseLong(payload.get("ttlSeconds"));
        if (ttlSeconds != null) {
            return now + Math.min(ttlSeconds * 1_000L, maxTtlMillis);
        }

        return now + maxTtlMillis;
    }

    private static Long parseTimestamp(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }

        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            long raw = element.getAsLong();
            return raw < 10_000_000_000L ? raw * 1_000L : raw;
        }

        String raw = stringValue(element);
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                long parsed = Long.parseLong(raw);
                return parsed < 10_000_000_000L ? parsed * 1_000L : parsed;
            } catch (NumberFormatException ignoredAgain) {
                return null;
            }
        }
    }

    private static Long parseLong(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static UUID parseUuid(String rawUuid) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(rawUuid);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static JsonObject objectValue(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String stringValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
