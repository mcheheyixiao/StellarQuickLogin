package cn.stellarworld.quicklogin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class AuthMeHook {

    private final JavaPlugin plugin;
    private volatile Object apiInstance;
    private volatile Method isAuthenticatedMethod;
    private volatile Method isRegisteredMethod;
    private volatile Method forceLoginMethod;
    private volatile String status = "unchecked";
    private boolean warnedMissing;
    private boolean warnedUnavailable;

    public AuthMeHook(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void refreshAvailability() {
        Plugin authMePlugin = Bukkit.getPluginManager().getPlugin("AuthMe");
        if (authMePlugin == null || !authMePlugin.isEnabled()) {
            clearApi("missing");
            if (!warnedMissing) {
                plugin.getLogger().warning("AuthMe is not installed or not enabled. StellarQuickLogin will stay passive until AuthMe becomes available.");
                warnedMissing = true;
            }
            return;
        }

        try {
            Class<?> apiClass = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            Object api = getInstanceMethod.invoke(null);
            if (api == null) {
                clearApi("api_unavailable");
                warnUnavailableOnce("AuthMe API returned null from getInstance().");
                return;
            }

            this.isAuthenticatedMethod = apiClass.getMethod("isAuthenticated", Player.class);
            this.isRegisteredMethod = apiClass.getMethod("isRegistered", String.class);
            this.forceLoginMethod = apiClass.getMethod("forceLogin", Player.class);
            this.apiInstance = api;
            this.status = "enabled";
            this.warnedMissing = false;
            this.warnedUnavailable = false;
        } catch (ReflectiveOperationException | LinkageError exception) {
            clearApi("api_unavailable");
            warnUnavailableOnce("Failed to initialize AuthMe API compatibility layer: " + exception.getClass().getSimpleName());
        }
    }

    public boolean isAvailable() {
        return apiInstance != null;
    }

    public String status() {
        return status;
    }

    public boolean isAuthenticated(Player player) {
        if (!ensureApi()) {
            return false;
        }

        try {
            return (boolean) isAuthenticatedMethod.invoke(apiInstance, player);
        } catch (ReflectiveOperationException exception) {
            clearApi("api_unavailable");
            warnUnavailableOnce("AuthMe isAuthenticated() invocation failed.");
            return false;
        }
    }

    public boolean isRegistered(Player player) {
        if (!ensureApi()) {
            return false;
        }

        try {
            return (boolean) isRegisteredMethod.invoke(apiInstance, player.getName());
        } catch (ReflectiveOperationException exception) {
            clearApi("api_unavailable");
            warnUnavailableOnce("AuthMe isRegistered() invocation failed.");
            return false;
        }
    }

    public boolean forceLogin(Player player, boolean requireRegistered) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("Refusing to call AuthMe forceLogin off the main thread.");
            return false;
        }

        if (!player.isOnline() || !ensureApi()) {
            return false;
        }

        if (isAuthenticated(player)) {
            return false;
        }

        if (requireRegistered && !isRegistered(player)) {
            return false;
        }

        try {
            forceLoginMethod.invoke(apiInstance, player);
            return true;
        } catch (ReflectiveOperationException exception) {
            clearApi("api_unavailable");
            warnUnavailableOnce("AuthMe forceLogin() invocation failed.");
            return false;
        }
    }

    private boolean ensureApi() {
        if (apiInstance == null) {
            refreshAvailability();
        }
        return apiInstance != null;
    }

    private void clearApi(String newStatus) {
        this.apiInstance = null;
        this.isAuthenticatedMethod = null;
        this.isRegisteredMethod = null;
        this.forceLoginMethod = null;
        this.status = newStatus;
    }

    private void warnUnavailableOnce(String message) {
        if (!warnedUnavailable) {
            plugin.getLogger().warning(message + " StellarQuickLogin will not bypass AuthMe until this is fixed.");
            warnedUnavailable = true;
        }
    }
}
