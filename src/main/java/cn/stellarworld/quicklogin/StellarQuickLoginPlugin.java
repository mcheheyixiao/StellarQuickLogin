package cn.stellarworld.quicklogin;

import cn.stellarworld.quicklogin.auth.AuthMeHook;
import cn.stellarworld.quicklogin.command.StellarQuickLoginCommand;
import cn.stellarworld.quicklogin.config.StellarQuickLoginConfig;
import cn.stellarworld.quicklogin.listener.PlayerJoinListener;
import cn.stellarworld.quicklogin.realtime.RealtimeClient;
import cn.stellarworld.quicklogin.ticket.QuickLoginTicketCache;
import cn.stellarworld.quicklogin.website.WebsiteClient;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.http.HttpClient;

public final class StellarQuickLoginPlugin extends JavaPlugin {

    private StellarQuickLoginConfig pluginConfig;
    private QuickLoginTicketCache ticketCache;
    private AuthMeHook authMeHook;
    private WebsiteClient websiteClient;
    private RealtimeClient realtimeClient;
    private BukkitTask cleanupTask;
    private boolean quickLoginFunctional;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.authMeHook = new AuthMeHook(this);
        this.ticketCache = new QuickLoginTicketCache(System::currentTimeMillis, 300_000L);
        this.cleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(this, ticketCache::cleanupExpired, 1_200L, 1_200L);

        reloadPluginState();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        registerCommand();
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        shutdownRealtimeClient();
    }

    public void reloadPluginState() {
        reloadConfig();
        this.pluginConfig = StellarQuickLoginConfig.from(getConfig());
        this.ticketCache.setMaxTtlMillis(pluginConfig.security().ticketTtlMillis());
        this.ticketCache.clear();
        this.authMeHook.refreshAvailability();
        this.quickLoginFunctional = validateQuickLoginConfiguration();
        this.websiteClient = new WebsiteClient(
            HttpClient.newBuilder().connectTimeout(pluginConfig.website().timeout()).build(),
            pluginConfig.website().consumeUri(),
            pluginConfig.website().internalToken(),
            pluginConfig.website().timeout()
        );
        restartRealtimeClient();
    }

    public StellarQuickLoginConfig getPluginConfig() {
        return pluginConfig;
    }

    public QuickLoginTicketCache getTicketCache() {
        return ticketCache;
    }

    public AuthMeHook getAuthMeHook() {
        return authMeHook;
    }

    public WebsiteClient getWebsiteClient() {
        return websiteClient;
    }

    public boolean isQuickLoginFunctional() {
        return quickLoginFunctional;
    }

    public String getRealtimeStatus() {
        if (!pluginConfig.realtime().enabled()) {
            return "disabled";
        }
        if (realtimeClient == null) {
            return "not_started";
        }
        return realtimeClient.status();
    }

    private boolean validateQuickLoginConfiguration() {
        boolean enabled = true;

        if (!pluginConfig.website().hasConfiguredConsumeUrl()) {
            getLogger().warning("website.consume-url is still unset or pointing to the example placeholder. Quick login will stay disabled.");
            enabled = false;
        }

        if (pluginConfig.website().hasPlaceholderToken()) {
            getLogger().warning("website.internal-token is still CHANGE_ME. Quick login will stay disabled until a real internal token is configured.");
            enabled = false;
        }

        return enabled;
    }

    private void restartRealtimeClient() {
        shutdownRealtimeClient();
        if (!pluginConfig.realtime().enabled()) {
            return;
        }
        if (!quickLoginFunctional) {
            getLogger().warning("Realtime quick-login listener is not starting because website consume is not fully configured.");
            return;
        }
        if (!pluginConfig.realtime().isConfigured()) {
            getLogger().warning("Realtime is enabled but websocket-url or plugin-token is not configured. Skipping websocket startup.");
            return;
        }

        realtimeClient = new RealtimeClient(
            getLogger(),
            pluginConfig.realtime().websocketUri(),
            pluginConfig.realtime().pluginToken(),
            pluginConfig.serverId(),
            pluginConfig.security().ticketTtlMillis(),
            pluginConfig.realtime().reconnectDelaySeconds(),
            pluginConfig.realtime().heartbeatSeconds(),
            ticketCache
        );
        realtimeClient.start();
    }

    private void shutdownRealtimeClient() {
        if (realtimeClient != null) {
            realtimeClient.stop();
            realtimeClient = null;
        }
    }

    private void registerCommand() {
        PluginCommand command = getCommand("stellarquicklogin");
        if (command == null) {
            getLogger().warning("Failed to register /stellarquicklogin command from paper-plugin.yml.");
            return;
        }

        StellarQuickLoginCommand handler = new StellarQuickLoginCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }
}
