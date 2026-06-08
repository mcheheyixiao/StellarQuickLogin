package cn.stellarworld.quicklogin.ticket;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickLoginTicketCacheTest {

    @Test
    void putClampsExpiryToConfiguredMaximumTtl() {
        AtomicLong now = new AtomicLong(1_000L);
        QuickLoginTicketCache cache = new QuickLoginTicketCache(() -> now.get(), 5_000L);
        UUID uuid = UUID.randomUUID();

        cache.put(new QuickLoginTicket("secret-token", "Alice", uuid, 42L, 12_000L, "req-1"));

        Optional<QuickLoginTicket> stored = cache.find(uuid, "Alice");
        assertTrue(stored.isPresent());
        assertEquals(6_000L, stored.get().expiresAtMillis());
    }

    @Test
    void findRemovesExpiredTickets() {
        AtomicLong now = new AtomicLong(1_000L);
        QuickLoginTicketCache cache = new QuickLoginTicketCache(() -> now.get(), 10_000L);
        UUID uuid = UUID.randomUUID();
        cache.put(new QuickLoginTicket("secret-token", "Alice", uuid, 42L, 1_500L, "req-1"));

        now.set(1_600L);

        assertTrue(cache.find(uuid, "Alice").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void findFallsBackToLowercasePlayerNameWhenUuidMissing() {
        AtomicLong now = new AtomicLong(1_000L);
        QuickLoginTicketCache cache = new QuickLoginTicketCache(() -> now.get(), 10_000L);
        cache.put(new QuickLoginTicket("secret-token", "Alice", null, 42L, 2_000L, "req-1"));

        Optional<QuickLoginTicket> stored = cache.find(null, "ALICE");

        assertTrue(stored.isPresent());
        assertEquals("Alice", stored.get().playerName());
    }

    @Test
    void cleanupExpiredRemovesOnlyExpiredEntries() {
        AtomicLong now = new AtomicLong(1_000L);
        QuickLoginTicketCache cache = new QuickLoginTicketCache(() -> now.get(), 10_000L);
        UUID aliceUuid = UUID.randomUUID();
        UUID bobUuid = UUID.randomUUID();
        cache.put(new QuickLoginTicket("token-1", "Alice", aliceUuid, 1L, 1_500L, "req-1"));
        cache.put(new QuickLoginTicket("token-2", "Bob", bobUuid, 2L, 5_000L, "req-2"));

        now.set(2_000L);

        assertEquals(1, cache.cleanupExpired());
        assertEquals(1, cache.size());
        assertFalse(cache.find(aliceUuid, "Alice").isPresent());
        assertTrue(cache.find(bobUuid, "Bob").isPresent());
    }
}
