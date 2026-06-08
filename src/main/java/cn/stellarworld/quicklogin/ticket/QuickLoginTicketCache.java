package cn.stellarworld.quicklogin.ticket;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

public final class QuickLoginTicketCache {

    private final ConcurrentMap<String, QuickLoginTicket> tickets = new ConcurrentHashMap<>();
    private final LongSupplier nowSupplier;
    private volatile long maxTtlMillis;

    public QuickLoginTicketCache(LongSupplier nowSupplier, long maxTtlMillis) {
        this.nowSupplier = nowSupplier;
        this.maxTtlMillis = Math.max(1_000L, maxTtlMillis);
    }

    public void setMaxTtlMillis(long maxTtlMillis) {
        this.maxTtlMillis = Math.max(1_000L, maxTtlMillis);
    }

    public void put(QuickLoginTicket ticket) {
        String storageKey = keyFor(ticket.playerUuid(), ticket.playerName());
        if (storageKey == null) {
            return;
        }

        long now = nowSupplier.getAsLong();
        long boundedExpiresAt = Math.min(ticket.expiresAtMillis(), now + maxTtlMillis);
        if (boundedExpiresAt <= now) {
            remove(ticket);
            return;
        }

        tickets.put(storageKey, ticket.withExpiresAtMillis(boundedExpiresAt));
    }

    public Optional<QuickLoginTicket> find(UUID playerUuid, String playerName) {
        Optional<QuickLoginTicket> uuidMatch = lookup(uuidKey(playerUuid));
        if (uuidMatch.isPresent()) {
            return uuidMatch;
        }
        return lookup(nameKey(playerName));
    }

    public void remove(QuickLoginTicket ticket) {
        String uuidKey = uuidKey(ticket.playerUuid());
        if (uuidKey != null) {
            tickets.remove(uuidKey);
        }

        String nameKey = nameKey(ticket.playerName());
        if (nameKey != null) {
            tickets.remove(nameKey);
        }
    }

    public void clear() {
        tickets.clear();
    }

    public int size() {
        cleanupExpired();
        return tickets.size();
    }

    public int cleanupExpired() {
        long now = nowSupplier.getAsLong();
        int removed = 0;
        for (var entry : tickets.entrySet()) {
            if (entry.getValue().isExpired(now) && tickets.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private Optional<QuickLoginTicket> lookup(String key) {
        if (key == null) {
            return Optional.empty();
        }

        QuickLoginTicket ticket = tickets.get(key);
        if (ticket == null) {
            return Optional.empty();
        }

        if (ticket.isExpired(nowSupplier.getAsLong())) {
            tickets.remove(key, ticket);
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private static String keyFor(UUID playerUuid, String playerName) {
        String uuidKey = uuidKey(playerUuid);
        if (uuidKey != null) {
            return uuidKey;
        }
        return nameKey(playerName);
    }

    private static String uuidKey(UUID playerUuid) {
        return playerUuid == null ? null : "uuid:" + playerUuid;
    }

    private static String nameKey(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        return "name:" + playerName.toLowerCase(Locale.ROOT);
    }
}
