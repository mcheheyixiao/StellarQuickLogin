package cn.stellarworld.quicklogin.realtime;

import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeMessageParserTest {

    @Test
    void parseSupportsCommandTypeAndClampsExpiry() {
        AtomicLong now = new AtomicLong(1_000L);
        String rawMessage = """
            {
              "requestId": "req-1",
              "serverId": "survival-1",
              "command": {
                "type": "quicklogin.preauthorize"
              },
              "payload": {
                "playerName": "Alice",
                "playerUuid": "123e4567-e89b-12d3-a456-426614174000",
                "websiteUserId": 12,
                "token": "ticket-123",
                "ttlSeconds": 999
              }
            }
            """;

        Optional<ParsedPreauthorizeMessage> parsed = RealtimeMessageParser.parse(
            rawMessage,
            "survival-1",
            300_000L,
            () -> now.get()
        );

        assertTrue(parsed.isPresent());
        QuickLoginTicket ticket = parsed.get().ticket();
        assertEquals("req-1", ticket.requestId());
        assertEquals(301_000L, ticket.expiresAtMillis());
    }

    @Test
    void parseRejectsServerMismatch() {
        AtomicLong now = new AtomicLong(1_000L);
        String rawMessage = """
            {
              "type": "quicklogin.preauthorize",
              "requestId": "req-1",
              "serverId": "creative-1",
              "payload": {
                "playerName": "Alice",
                "token": "ticket-123",
                "ttlSeconds": 60
              }
            }
            """;

        Optional<ParsedPreauthorizeMessage> parsed = RealtimeMessageParser.parse(
            rawMessage,
            "survival-1",
            300_000L,
            () -> now.get()
        );

        assertTrue(parsed.isPresent());
        assertEquals(ParseStatus.SERVER_MISMATCH, parsed.get().status());
    }

    @Test
    void parseAcceptsUndashedUuidFromRealtimeBridge() {
        AtomicLong now = new AtomicLong(1_000L);
        String rawMessage = """
            {
              "type": "quicklogin.preauthorize",
              "requestId": "req-quicklogin-undashed",
              "serverId": "survival-1",
              "payload": {
                "playerName": "Alice",
                "playerUuid": "123e4567e89b12d3a456426614174000",
                "websiteUserId": 12,
                "token": "ticket-123456789012345678901234567890",
                "ttlSeconds": 60
              }
            }
            """;

        Optional<ParsedPreauthorizeMessage> parsed = RealtimeMessageParser.parse(
            rawMessage,
            "survival-1",
            300_000L,
            () -> now.get()
        );

        assertTrue(parsed.isPresent());
        assertEquals(ParseStatus.OK, parsed.get().status());
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), parsed.get().ticket().playerUuid());
    }
}
