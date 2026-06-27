package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.RightsManager;
import com.raeyd.raidattack.combat.ArmorDurabilityManager;
import com.raeyd.raidattack.quest.QuestManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Handler for {@code /ra} (alias {@code /raidattack}) — the umbrella for generic RaidAttack
 * utility + admin commands. Current surface:
 *
 * <pre>
 *   /ra armor_durability_indicator &lt;on|off&gt;   (per-player; default off)
 *   /ra rights list                            (owner/admin)
 *   /ra rights add admin &lt;player&gt;              (owner only)
 *   /ra rights remove &lt;player&gt;                 (owner only)
 *   /ra rights reload                          (owner only)
 *   /ra quests                                 (per-player achievements + progress)
 * </pre>
 *
 * The moderation commands ({@code /ban}, {@code /kick}, {@code /unban}) are intentionally NOT
 * under {@code /ra} — they shadow the vanilla commands and are routed by {@link ModerationListener}.
 */
public final class RaidAttackCommand implements CommandExecutor, TabCompleter {

    private final HomeSystemPlugin plugin;

    public RaidAttackCommand(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> openMenu(sender, args);
            case "armor_durability_indicator", "armor", "adi" -> handleArmorIndicator(sender, args);
            case "rights" -> handleRights(sender, args);
            case "quests", "quest", "q" -> handleQuests(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    /** Open the native Bedrock UI ({@code /ra menu} / {@code /raidattack menu}). Bedrock-only — Java
     *  players are told to use the commands directly (the menu just launches those same commands).
     *
     *  <p>{@code /ra menu dev} opens it "as the dev" (raids act as {@link HomeSystemPlugin#DEV_UUID},
     *  bypassing event locks) — gated by the SAME x2 shield as {@code /raid dev}: (1) RaidAttack owner
     *  via the rights system (not op), (2) {@code dev_mode: true}. Same messages as Java, to avoid an
     *  exploit where a non-owner sneaks into dev mode through the Bedrock menu. */
    private void openMenu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.YELLOW + "Only players can open the menu.");
            return;
        }
        boolean asDev = args.length >= 2 && args[1].equalsIgnoreCase("dev");
        if (asDev) {
            if (!plugin.getRightsManager().isOwner(p)) {
                p.sendMessage(ChatColor.RED + "You don't have permission for dev raid mode.");
                return;
            }
            if (!plugin.isDevMode()) {
                p.sendMessage(ChatColor.RED + "Dev raid mode is disabled. Set "
                        + ChatColor.WHITE + "dev_mode: true" + ChatColor.RED + " in config.yml (server off).");
                return;
            }
        }
        new com.raeyd.raidattack.core.BedrockMenu(plugin).open(p, asDev);
    }

    // -- /ra armor_durability_indicator --------------------------------------

    private void handleArmorIndicator(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Players only."); return; }
        ArmorDurabilityManager armor = plugin.getArmorDurability();
        if (args.length < 2) {
            boolean on = armor.isIndicatorOn(p.getUniqueId());
            p.sendMessage(ChatColor.YELLOW + "Armor durability indicator: "
                    + (on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            p.sendMessage(ChatColor.GRAY + "Usage: /ra armor_durability_indicator <on|off>");
            return;
        }
        Boolean on = parseOnOff(args[1]);
        if (on == null) {
            p.sendMessage(ChatColor.RED + "Usage: /ra armor_durability_indicator <on|off>");
            return;
        }
        armor.setIndicator(p.getUniqueId(), on);
        p.sendMessage(ChatColor.GREEN + "Armor durability indicator " + (on ? "enabled" : "disabled") + ".");
        if (on) {
            p.sendMessage(ChatColor.GRAY + "You'll be warned when any worn armor drops below 20% durability.");
        }
    }

    private static Boolean parseOnOff(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "true", "enable", "enabled", "yes" -> Boolean.TRUE;
            case "off", "false", "disable", "disabled", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    // -- /ra rights ----------------------------------------------------------

    private void handleRights(CommandSender sender, String[] args) {
        RightsManager rights = plugin.getRightsManager();
        if (args.length < 2) { sendRightsHelp(sender); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                if (!rights.isAdmin(sender)) { noPerm(sender); return; }
                sender.sendMessage(ChatColor.GOLD + "Owners: " + ChatColor.WHITE
                        + (rights.owners().isEmpty() ? "(none)" : String.join(", ", rights.owners())));
                sender.sendMessage(ChatColor.GOLD + "Admins: " + ChatColor.WHITE
                        + (rights.admins().isEmpty() ? "(none)" : String.join(", ", rights.admins())));
            }
            case "reload" -> {
                if (!rights.isOwner(sender)) { noPerm(sender); return; }
                rights.load();
                rights.ensureAllOnlineOwnersOp();
                sender.sendMessage(ChatColor.GREEN + "rights.yml reloaded.");
            }
            case "add" -> {
                if (!rights.isOwner(sender)) { noPerm(sender); return; }
                if (args.length < 4 || !args[2].equalsIgnoreCase("admin")) {
                    sender.sendMessage(ChatColor.RED + "Usage: /ra rights add admin <player>");
                    sender.sendMessage(ChatColor.GRAY + "(Owners can only be set by editing rights.yml.)");
                    return;
                }
                String err = rights.addAdmin(args[3]);
                if (err != null) { sender.sendMessage(ChatColor.RED + err); return; }
                sender.sendMessage(ChatColor.GREEN + "Granted admin to " + args[3] + ".");
            }
            case "remove" -> {
                if (!rights.isOwner(sender)) { noPerm(sender); return; }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "Usage: /ra rights remove <player>"); return; }
                String err = rights.removeAdmin(args[2]);
                if (err != null) { sender.sendMessage(ChatColor.RED + err); return; }
                sender.sendMessage(ChatColor.GREEN + "Removed admin from " + args[2] + ".");
            }
            default -> sendRightsHelp(sender);
        }
    }

    private void noPerm(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "You don't have permission for that.");
    }

    // -- /ra quests ----------------------------------------------------------

    private void handleQuests(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Players only."); return; }
        QuestManager quests = plugin.getQuests();
        if (quests == null) { p.sendMessage(ChatColor.RED + "Quests are unavailable right now."); return; }
        for (String line : quests.render(p.getUniqueId())) p.sendMessage(line);
    }

    // -- help ----------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "RaidAttack commands:");
        sender.sendMessage(ChatColor.YELLOW + " /ra armor_durability_indicator <on|off>"
                + ChatColor.GRAY + " - low-armor-durability warnings (per-player).");
        sender.sendMessage(ChatColor.YELLOW + " /ra rights list|add|remove|reload"
                + ChatColor.GRAY + " - manage the rights system.");
        sender.sendMessage(ChatColor.YELLOW + " /ra quests"
                + ChatColor.GRAY + " - your RaidAttack achievements + progress.");
        if (plugin.getRightsManager().canModerate(sender)) {
            sender.sendMessage(ChatColor.YELLOW + " /ban <player> <duration> <reason>"
                    + ChatColor.GRAY + "   /kick <player> <reason>   /unban <player>");
        }
    }

    private void sendRightsHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "Rights commands:");
        sender.sendMessage(ChatColor.YELLOW + " /ra rights list");
        sender.sendMessage(ChatColor.YELLOW + " /ra rights add admin <player>" + ChatColor.GRAY + " (owner only)");
        sender.sendMessage(ChatColor.YELLOW + " /ra rights remove <player>" + ChatColor.GRAY + " (owner only)");
        sender.sendMessage(ChatColor.YELLOW + " /ra rights reload" + ChatColor.GRAY + " (owner only)");
    }

    // -- tab completion ------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("menu", "armor_durability_indicator", "rights", "quests"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("armor_durability_indicator") || sub.equals("armor") || sub.equals("adi")) {
            if (args.length == 2) return filter(List.of("on", "off"), args[1]);
            return Collections.emptyList();
        }
        if (sub.equals("rights")) {
            if (args.length == 2) return filter(List.of("list", "add", "remove", "reload"), args[1]);
            String op = args[1].toLowerCase(Locale.ROOT);
            if (op.equals("add") && args.length == 3) return filter(List.of("admin"), args[2]);
            if (op.equals("add") && args.length == 4) return filter(onlineNames(), args[3]);
            if (op.equals("remove") && args.length == 3) return filter(plugin.getRightsManager().admins(), args[2]);
            return Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private static List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) return options;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
