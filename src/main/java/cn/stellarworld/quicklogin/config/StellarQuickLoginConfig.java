package cn.stellarworld.quicklogin.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.net.URI;
import java.time.Duration;

public record StellarQuickLoginConfig(
    String serverId,
    AuthMeSettings authMe,
    WebsiteSettings website,
    RealtimeSettings realtime,
    SecuritySettings security,
    MessageSettings messages
) {

    public static StellarQuickLoginConfig from(FileConfiguration config) {
        return new StellarQuickLoginConfig(
            config.getString("server-id", "survival-1"),
            new AuthMeSettings(
                config.getBoolean("authme.enabled", true),
                Math.max(0L, config.getLong("authme.join-delay-ticks", 20L)),
                config.getBoolean("authme.require-registered", true)
            ),
            new WebsiteSettings(
                config.getBoolean("website.enabled-direct-check", true),
                config.getString("website.consume-url", "https://www.example.com/internal/quick-login/consume"),
                config.getString("website.internal-token", "CHANGE_ME"),
                Math.max(500, config.getInt("website.timeout-ms", 3_000))
            ),
            new RealtimeSettings(
                config.getBoolean("realtime.enabled", true),
                config.getString("realtime.websocket-url", "ws://127.0.0.1:3001/ws/plugin"),
                config.getString("realtime.plugin-token", "CHANGE_ME"),
                Math.max(1, config.getInt("realtime.reconnect-delay-seconds", 5)),
                Math.max(5, config.getInt("realtime.heartbeat-seconds", 30))
            ),
            new SecuritySettings(
                Math.max(30, config.getInt("security.ticket-ttl-seconds", 300)),
                config.getBoolean("security.require-player-match", true),
                config.getBoolean("security.clear-ticket-after-attempt", true),
                config.getBoolean("security.log-sensitive-values", false)
            ),
            new MessageSettings(
                config.getString("messages.success", "§a已通过 StellarWorld 网站免密登录。"),
                config.getString("messages.expired", "§c免密登录已过期，请重新在网站开启。"),
                config.getString("messages.failed", "§c免密登录失败，请使用 /login 密码登录。")
            )
        );
    }

    public record AuthMeSettings(
        boolean enabled,
        long joinDelayTicks,
        boolean requireRegistered
    ) {
    }

    public record WebsiteSettings(
        boolean enabledDirectCheck,
        String consumeUrl,
        String internalToken,
        int timeoutMs
    ) {

        public boolean hasPlaceholderToken() {
            return internalToken == null || internalToken.isBlank() || "CHANGE_ME".equalsIgnoreCase(internalToken);
        }

        public URI consumeUri() {
            try {
                return URI.create(consumeUrl);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        public boolean hasConfiguredConsumeUrl() {
            URI uri = consumeUri();
            if (uri == null || uri.getHost() == null) {
                return false;
            }
            return !"example.com".equalsIgnoreCase(uri.getHost()) && !"www.example.com".equalsIgnoreCase(uri.getHost());
        }

        public Duration timeout() {
            return Duration.ofMillis(timeoutMs);
        }

        public boolean isConfigured() {
            return !hasPlaceholderToken() && hasConfiguredConsumeUrl();
        }
    }

    public record RealtimeSettings(
        boolean enabled,
        String websocketUrl,
        String pluginToken,
        int reconnectDelaySeconds,
        int heartbeatSeconds
    ) {

        public boolean hasPlaceholderToken() {
            return pluginToken == null || pluginToken.isBlank() || "CHANGE_ME".equalsIgnoreCase(pluginToken);
        }

        public URI websocketUri() {
            try {
                return URI.create(websocketUrl);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        public boolean isConfigured() {
            return enabled && websocketUri() != null && !hasPlaceholderToken();
        }
    }

    public record SecuritySettings(
        int ticketTtlSeconds,
        boolean requirePlayerMatch,
        boolean clearTicketAfterAttempt,
        boolean logSensitiveValues
    ) {

        public long ticketTtlMillis() {
            return ticketTtlSeconds * 1_000L;
        }
    }

    public record MessageSettings(
        String success,
        String expired,
        String failed
    ) {
    }
}
