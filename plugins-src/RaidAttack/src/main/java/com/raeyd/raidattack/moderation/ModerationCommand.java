package com.raeyd.raidattack.moderation;

import com.raeyd.raidattack.HomeSystemPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Brigadier command for {@code /ban}, {@code /kick}, {@code /unban} (one instance per {@link Type}).
 * Registered through the Paper command lifecycle so it REPLACES the vanilla command in the client's
 * command tree — meaning the client shows OUR syntax ({@code <player> <duration> <reason>}) and asks
 * the server for completions via {@link #suggest}, rather than vanilla's misleading
 * {@code <targets> [<reason>]} hint (whose free-text reason slot is completed client-side, so our
 * duration suggestions never had a chance to appear).
 *
 * <p>Execution is actually consumed earlier by {@link ModerationListener} (it intercepts the raw
 * command line and cancels it), so {@link #execute} here is a safety net that does the same thing if
 * the interception is ever bypassed. {@link #canUse} gates visibility to moderators, so non-mods
 * never even see these commands in their tree.
 */
public final class ModerationCommand implements BasicCommand {

    public enum Type { BAN, KICK, UNBAN }

    /** Example durations offered in the {@code <duration>} slot of {@code /ban}. */
    private static final List<String> DURATION_HINTS = List.of(
            "10s", "30s", "5M", "30M", "1h", "6h", "12h", "1d", "3d", "1w", "2w", "1m");

    /** Example reasons for the {@code <reason>} slot — single words ≥ the 10-char minimum, so they're
     *  valid as-is, double as a visible cue that a reason is expected, and insert cleanly (no spaces).
     *  The reason is still free text; type your own multi-word reason if you prefer. */
    private static final List<String> REASON_HINTS = List.of(
            "Harassment", "Advertising", "Disrespect", "Inappropriate", "Misconduct", "Rule-breaking");

    private final HomeSystemPlugin plugin;
    private final Type type;

    public ModerationCommand(HomeSystemPlugin plugin, Type type) {
        this.plugin = plugin;
        this.type = type;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        switch (type) {
            case BAN   -> doBan(sender, args);
            case KICK  -> doKick(sender, args);
            case UNBAN -> doUnban(sender, args);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!plugin.getRightsManager().canModerate(source.getSender())) return Collections.emptyList();
        int idx = args.length == 0 ? 0 : args.length - 1;          // the arg currently being typed
        String prefix = (args.length == 0 ? "" : args[idx]).toLowerCase(Locale.ROOT);

        List<String> options;
        if (idx == 0 && type != Type.UNBAN) {                      // <player> (online names)
            options = onlineNames();
        } else if (idx == 1 && type == Type.BAN) {                 // <duration> (ban only)
            options = DURATION_HINTS;
        } else if ((type == Type.BAN && idx == 2) || (type == Type.KICK && idx == 1)) {
            options = REASON_HINTS;                                // <reason> first word — example cue
        } else {
            return Collections.emptyList();                        // later reason words / unban — free text
        }

        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(o);
        }
        return out;
    }

    @Override
    public boolean canUse(CommandSender sender) {
        return plugin.getRightsManager().canModerate(sender);
    }

    // -- execution (safety net; normally consumed by ModerationListener) ------

    private void doBan(CommandSender sender, String[] args) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /ban.");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /ban <player> <duration> <reason>");
            sender.sendMessage(ChatColor.GRAY + "Duration: m=month w=week d=day h=hour M=minute s=second "
                    + "(e.g. 2w1d). Reason " + ModerationService.REASON_MIN + "-"
                    + ModerationService.REASON_MAX + " chars.");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        ModerationService.Result r = plugin.getModerationService().ban(sender, args[0], args[1], reason);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
    }

    private void doKick(CommandSender sender, String[] args) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /kick.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /kick <player> <reason>");
            sender.sendMessage(ChatColor.GRAY + "Reason " + ModerationService.REASON_MIN + "-"
                    + ModerationService.REASON_MAX + " chars.");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        ModerationService.Result r = plugin.getModerationService().kick(sender, args[0], reason);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
    }

    private void doUnban(CommandSender sender, String[] args) {
        if (!plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use /unban.");
            return;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /unban <player>");
            return;
        }
        ModerationService.Result r = plugin.getModerationService().unban(sender, args[0]);
        sender.sendMessage((r.ok() ? ChatColor.GREEN : ChatColor.RED) + r.message());
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }
}
