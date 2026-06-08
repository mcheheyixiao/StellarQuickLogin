package cn.stellarworld.quicklogin.ticket;

import java.util.Locale;
import java.util.UUID;

public record QuickLoginTicket(
    String token,
    String playerName,
    UUID playerUuid,
    Long websiteUserId,
    long expiresAtMillis,
    String requestId
) {

    public QuickLoginTicket withExpiresAtMillis(long newExpiresAtMillis) {
        return new QuickLoginTicket(token, playerName, playerUuid, websiteUserId, newExpiresAtMillis, requestId);
    }

    public boolean isExpired(long nowMillis) {
        return expiresAtMillis <= nowMillis;
    }

    public boolean matchesPlayer(UUID onlineUuid, String onlineName, boolean requirePlayerMatch) {
        if (!requirePlayerMatch) {
            return true;
        }

        if (playerUuid != null && onlineUuid != null) {
            return playerUuid.equals(onlineUuid);
        }

        if (playerName != null && onlineName != null) {
            return playerName.equalsIgnoreCase(onlineName);
        }

        return false;
    }

    public String playerUuidAsString() {
        return playerUuid == null ? null : playerUuid.toString();
    }

    public String normalizedPlayerName() {
        return playerName == null ? null : playerName.toLowerCase(Locale.ROOT);
    }
}
