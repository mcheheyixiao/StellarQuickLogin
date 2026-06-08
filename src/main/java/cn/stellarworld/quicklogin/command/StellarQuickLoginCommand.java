package cn.stellarworld.quicklogin.command;

import cn.stellarworld.quicklogin.StellarQuickLoginPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

public final class StellarQuickLoginCommand implements CommandExecutor, TabCompleter {

    private final StellarQuickLoginPlugin plugin;

    public StellarQuickLoginCommand(StellarQuickLoginPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("stellarquicklogin.admin")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sender.sendMessage("§e[StellarQuickLogin] Status");
            sender.sendMessage("§7- AuthMe hook: §f" + plugin.getAuthMeHook().status());
            sender.sendMessage("§7- Realtime: §f" + plugin.getRealtimeStatus());
            sender.sendMessage("§7- Cached tickets: §f" + plugin.getTicketCache().size());
            sender.sendMessage("§7- Website consume configured: §f" + plugin.getPluginConfig().website().isConfigured());
            sender.sendMessage("§7- Direct consume fallback: §f" + plugin.getPluginConfig().website().enabledDirectCheck());
            sender.sendMessage("§7- Quick login functional: §f" + plugin.isQuickLoginFunctional());
            return true;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            plugin.reloadPluginState();
            sender.sendMessage("§aStellarQuickLogin configuration reloaded.");
            return true;
        }

        sender.sendMessage("§cUsage: /" + label + " <status|reload>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "reload").stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
        }
        return List.of();
    }
}
