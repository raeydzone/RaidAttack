package com.raeyd.raidattack.moderation;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Intercepts the vanilla moderation commands and reroutes them through our rights-gated
 * {@link ModerationService} so bans get durations, reasons get validated, and everything is
 * audit-logged. We do this by cancelling the command at the event layer (works whether the
 * vanilla command, another plugin, or a namespaced {@code minecraft:ban} form is used) rather
 * than registering competing commands in {@code plugin.yml}.
 *
 * <p>Routed: {@code /ban}, {@code /kick}, {@code /unban}, {@code /pardon} (vanilla's unban alias).
 * Permission: owners and admins; everyone else is denied. The console is always owner-level.
 */
public final class ModerationListener implements Listener {

    /** Base labels whose VANILLA (namespaced) form we hide from the client tree — our own Brigadier
     *  {@link ModerationCommand} owns the bare labels, so only the redundant {@code minecraft:*} forms
     *  (which still carry vanilla's misleading {@code <targets> [<reason>]} hint) get removed. */
    private static final Set<String> SHADOWED = Set.of("ban", "kick", "pardon", "ban-ip", "pardon-ip");

    private final HomeSystemPlugin plugin;

    public ModerationListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        if (handle(e.getPlayer(), e.getMessage())) e.setCancelled(true);
    }

    /**
     * Strip only the redundant {@code minecraft:}-namespaced vanilla moderation commands from the
     * client's command tree. The bare {@code ban}/{@code kick}/{@code unban} labels are owned by our
     * Brigadier {@link ModerationCommand} (which carries the correct {@code <player> <duration>
     * <reason>} hint + server-side completions), so we leave those alone and only remove the
     * namespaced duplicates that would otherwise still show vanilla's misleading hint.
     */
    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent e) {
        e.getCommands().removeIf(label -> {
            int colon = label.indexOf(':');
            if (colon < 0) return false;                      // keep bare labels (incl. our commands)
            String base = label.substring(colon + 1).toLowerCase(Locale.ROOT);
            return SHADOWED.contains(base);                   // drop minecraft:ban, minecraft:kick, …
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        if (handle(e.getSender(), e.getCommand())) e.setCancelled(true);
    }

    /** @return true if this line was one of our moderation commands (and we consumed it). */
    private boolean handle(CommandSender sender, String commandLine) {
        if (commandLine == null) return false;
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1).trim();
        if (line.isEmpty()) return false;

        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        int colon = cmd.indexOf(':');            // strip "minecraft:" so it can't be bypassed
        if (colon >= 0) cmd = cmd.substring(colon + 1);

        return switch (cmd) {
            case "ban"            -> doBan(sender, parts);
            case "kick"           -> doKick(sender, parts);
            case "unban", "pardon" -> doUnban(sender, parts);
            default               -> false;
        };
    }

    private boolean doBan(CommandSender sender, String[] parts) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /ban.");
            return true;
        }
        if (parts.length < 4) {
            sender.sendMessage(ChatColor.RED + "Usage: /ban <player> <duration> <reason>");
            sender.sendMessage(ChatColor.GRAY + "Duration: m=month w=week d=day h=hour M=minute "
                    + "s=second (e.g. 2w1d). Reason " + ModerationService.REASON_MIN + "-"
                    + ModerationService.REASON_MAX + " chars.");
            return true;
        }
        String target = parts[1];
        String duration = parts[2];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 3, parts.length));
        ModerationService.Result r = plugin.getModerationService().ban(sender, target, duration, reason);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
        return true;
    }

    private boolean doKick(CommandSender sender, String[] parts) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /kick.");
            return true;
        }
        if (parts.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /kick <player> <reason>");
            sender.sendMessage(ChatColor.GRAY + "Reason " + ModerationService.REASON_MIN + "-"
                    + ModerationService.REASON_MAX + " chars.");
            return true;
        }
        String target = parts[1];
        String reason = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
        ModerationService.Result r = plugin.getModerationService().kick(sender, target, reason);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
        return true;
    }

    private boolean doUnban(CommandSender sender, String[] parts) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /unban.");
            return true;
        }
        if (parts.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /unban <player>");
            return true;
        }
        ModerationService.Result r = plugin.getModerationService().unban(sender, parts[1]);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
        return true;
    }
}
