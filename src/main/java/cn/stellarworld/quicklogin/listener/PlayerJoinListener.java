package cn.stellarworld.quicklogin.listener;

import cn.stellarworld.quicklogin.StellarQuickLoginPlugin;
import cn.stellarworld.quicklogin.config.StellarQuickLoginConfig;
import cn.stellarworld.quicklogin.ticket.QuickLoginTicket;
import cn.stellarworld.quicklogin.website.ConsumeRequest;
import cn.stellarworld.quicklogin.website.ConsumeResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final StellarQuickLoginPlugin plugin;

    public PlayerJoinListener(StellarQuickLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        StellarQuickLoginConfig config = plugin.getPluginConfig();
        Bukkit.getScheduler().runTaskLater(plugin, () -> attemptQuickLogin(event.getPlayer()), config.authMe().joinDelayTicks());
    }

    private void attemptQuickLogin(Player player) {
        if (!player.isOnline()) {
            return;
        }

        StellarQuickLoginConfig config = plugin.getPluginConfig();
        if (!config.authMe().enabled() || !plugin.isQuickLoginFunctional()) {
            return;
        }

        plugin.getAuthMeHook().refreshAvailability();
        if (!plugin.getAuthMeHook().isAvailable()) {
            return;
        }

        if (plugin.getAuthMeHook().isAuthenticated(player)) {
            return;
        }

        if (config.authMe().requireRegistered() && !plugin.getAuthMeHook().isRegistered(player)) {
            return;
        }

        QuickLoginTicket ticket = plugin.getTicketCache().find(player.getUniqueId(), player.getName()).orElse(null);
        if (ticket != null && !ticket.matchesPlayer(player.getUniqueId(), player.getName(), config.security().requirePlayerMatch())) {
            plugin.getTicketCache().remove(ticket);
            return;
        }

        boolean directCheck = ticket == null && config.website().enabledDirectCheck();
        if (ticket == null && !directCheck) {
            return;
        }

        ConsumeRequest request = new ConsumeRequest(
            ticket == null ? null : ticket.token(),
            player.getName(),
            player.getUniqueId().toString(),
            config.serverId()
        );

        plugin.getWebsiteClient().consume(request).whenComplete((response, throwable) ->
            Bukkit.getScheduler().runTask(plugin, () -> finishAttempt(player, ticket, response, throwable != null))
        );
    }

    private void finishAttempt(Player player, QuickLoginTicket ticket, ConsumeResponse response, boolean requestFailed) {
        StellarQuickLoginConfig config = plugin.getPluginConfig();
        if (ticket != null && (config.security().clearTicketAfterAttempt() || (response != null && response.ok()))) {
            plugin.getTicketCache().remove(ticket);
        }

        if (!player.isOnline()) {
            return;
        }

        if (requestFailed || response == null) {
            player.sendMessage(config.messages().failed());
            return;
        }

        if (!response.ok()) {
            player.sendMessage(response.isExpired() ? config.messages().expired() : config.messages().failed());
            return;
        }

        if (plugin.getAuthMeHook().isAuthenticated(player)) {
            return;
        }

        boolean forced = plugin.getAuthMeHook().forceLogin(player, config.authMe().requireRegistered());
        player.sendMessage(forced ? config.messages().success() : config.messages().failed());
    }
}
