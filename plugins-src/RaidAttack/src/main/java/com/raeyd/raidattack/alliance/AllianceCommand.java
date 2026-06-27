package com.raeyd.raidattack.alliance;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.core.ChatListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * Handler for both {@code /alliance} (full management surface) and {@code /a} (alliance-only
 * chat — toggle when run bare, one-shot message when run with args). Kept in one class so the
 * tab-completion is centralised and the subcommand dispatch sits next to the related plumbing.
 *
 * <p>All state changes go through {@link AllianceManager}; this class is purely the
 * command-line surface. Error strings come back from the manager and are forwarded verbatim
 * to the player.
 */
public final class AllianceCommand implements CommandExecutor, TabCompleter {

    private final HomeSystemPlugin plugin;

    public AllianceCommand(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("a")) return handleA(p, args);
        return handleAlliance(p, args);
    }

    // -- /a -------------------------------------------------------------------

    private boolean handleA(Player p, String[] args) {
        AllianceManager mgr = plugin.getAllianceManager();
        Alliance a = mgr.getOf(p.getUniqueId());
        if (a == null) {
            p.sendMessage(ChatColor.RED + "You are not in an alliance.");
            return true;
        }
        if (args.length == 0) {
            boolean nowOn = mgr.toggleAllianceOnlyChat(p.getUniqueId());
            p.sendMessage((nowOn ? ChatColor.GRAY : ChatColor.YELLOW)
                    + (nowOn ? "Alliance-only chat ENABLED. All your messages now go to your alliance only."
                             : "Alliance-only chat disabled. Messages go to global chat again."));
            return true;
        }
        // One-shot alliance message: build the args back into a string and route via the chat
        // listener's helper so formatting stays consistent with the toggle path.
        String msg = String.join(" ", args);
        ChatListener.deliverAllianceOnly(plugin, p, a, msg);
        return true;
    }

    // -- /alliance ------------------------------------------------------------

    private boolean handleAlliance(Player p, String[] args) {
        if (args.length == 0) { sendHelp(p); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create"           -> doCreate(p, args);
            case "destruct",
                 "disband"          -> doDestruct(p);
            case "color",
                 "colour"           -> doColor(p, args);
            case "change_name",
                 "rename"           -> doRename(p, args);
            case "join"             -> doJoin(p, args);
            case "accept"           -> doAccept(p, args);
            case "reject",
                 "deny"             -> doReject(p, args);
            case "pending_accepts",
                 "pending"          -> doPending(p);
            case "leave"            -> doLeave(p);
            case "kick"             -> doKick(p, args);
            case "info"             -> doInfo(p, args);
            case "list"             -> doList(p);
            default                 -> sendHelp(p);
        }
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.GOLD + "Alliance commands:");
        p.sendMessage(ChatColor.YELLOW + " /alliance create <name> <color>");
        p.sendMessage(ChatColor.YELLOW + " /alliance destruct");
        p.sendMessage(ChatColor.YELLOW + " /alliance color <color>");
        p.sendMessage(ChatColor.YELLOW + " /alliance change_name <newname>");
        p.sendMessage(ChatColor.YELLOW + " /alliance join <name>");
        p.sendMessage(ChatColor.YELLOW + " /alliance accept <player>");
        p.sendMessage(ChatColor.YELLOW + " /alliance reject <player>");
        p.sendMessage(ChatColor.YELLOW + " /alliance pending_accepts");
        p.sendMessage(ChatColor.YELLOW + " /alliance leave");
        p.sendMessage(ChatColor.YELLOW + " /alliance kick <player>");
        p.sendMessage(ChatColor.YELLOW + " /alliance info [name]");
        p.sendMessage(ChatColor.YELLOW + " /alliance list");
        p.sendMessage(ChatColor.GRAY + " /a [message]   — toggle alliance-only chat, or one-shot.");
    }

    // -- raid lock: alliance membership is frozen while you're in an active raid ---------------

    /** True (and messages {@code p}) if the player themselves is locked by an active raid. */
    private boolean raidLockSelf(Player p) {
        if (plugin.getRaidManager() != null && plugin.getRaidManager().isInActiveRaid(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED
                    + "A raid is in progress — you can't join, leave, or change alliances until it ends.");
            return true;
        }
        return false;
    }

    /** True (and messages {@code leader}) if {@code other} is locked by an active raid. */
    private boolean raidLockOther(Player leader, UUID other, String otherName) {
        if (plugin.getRaidManager() != null && plugin.getRaidManager().isInActiveRaid(other)) {
            leader.sendMessage(ChatColor.RED + otherName
                    + " is in an active raid — their alliance membership is locked until it ends.");
            return true;
        }
        return false;
    }

    private void doCreate(Player p, String[] args) {
        if (raidLockSelf(p)) return;
        if (args.length < 3) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance create <name> <color>");
            p.sendMessage(ChatColor.GRAY + "Colors: " + String.join(", ", AllianceManager.colorNames()));
            return;
        }
        String name = args[1];
        NamedTextColor color = AllianceManager.parseColor(args[2]);
        if (color == null) {
            p.sendMessage(ChatColor.RED + "Unknown color '" + args[2] + "'. Try one of: "
                    + String.join(", ", AllianceManager.colorNames()));
            return;
        }
        String err = plugin.getAllianceManager().create(name, color, p);
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "Alliance '" + name + "' created. You are its leader.");
        Bukkit.broadcast(Component.text("A new alliance has been founded: ", NamedTextColor.GRAY)
                .append(Component.text("[" + name + "]", color))
                .append(Component.text(" by " + p.getName() + ".", NamedTextColor.GRAY)));
    }

    private void doDestruct(Player p) {
        if (raidLockSelf(p)) return;
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can disband the alliance.");
            return;
        }
        String name = a.getName();
        plugin.getAllianceManager().disband(a);
        p.sendMessage(ChatColor.GREEN + "Alliance '" + name + "' disbanded.");
    }

    private void doColor(Player p, String[] args) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can change the colour.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance color <color>");
            p.sendMessage(ChatColor.GRAY + "Colors: " + String.join(", ", AllianceManager.colorNames()));
            return;
        }
        NamedTextColor color = AllianceManager.parseColor(args[1]);
        if (color == null) { p.sendMessage(ChatColor.RED + "Unknown color '" + args[1] + "'."); return; }
        plugin.getAllianceManager().setColor(a, color);
        p.sendMessage(ChatColor.GREEN + "Alliance colour updated.");
    }

    private void doRename(Player p, String[] args) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can rename the alliance.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance change_name <newname>");
            return;
        }
        String err = plugin.getAllianceManager().rename(a, args[1]);
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "Alliance renamed to '" + a.getName() + "'.");
    }

    private void doJoin(Player p, String[] args) {
        if (raidLockSelf(p)) return;
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance join <name>");
            return;
        }
        Alliance a = plugin.getAllianceManager().getByName(args[1]);
        if (a == null) { p.sendMessage(ChatColor.RED + "No alliance named '" + args[1] + "'."); return; }
        String err = plugin.getAllianceManager().requestJoin(a, p);
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "Join request sent to '" + a.getName() + "'.");
        // Broadcast to alliance members (spec: "anyone can see the request gets made (of the alliance members)").
        Component notif = Component.text(p.getName() + " has requested to join ", NamedTextColor.GRAY)
                .append(Component.text("[" + a.getName() + "]", a.getColor()))
                .append(Component.text(". Leader: use /alliance accept " + p.getName(), NamedTextColor.GRAY));
        for (UUID member : a.getMembers()) {
            Player m = Bukkit.getPlayer(member);
            if (m != null) m.sendMessage(notif);
        }
    }

    private void doAccept(Player p, String[] args) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can accept join requests.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance accept <player>");
            return;
        }
        UUID applicant = plugin.getAllianceManager().resolvePlayerUUID(args[1]);
        if (applicant == null) {
            p.sendMessage(ChatColor.RED + "Unknown player '" + args[1] + "'.");
            return;
        }
        if (raidLockOther(p, applicant, args[1])) return;     // can't bring a raid-locked player in
        String err = plugin.getAllianceManager().acceptRequest(a, applicant);
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "Accepted " + args[1] + " into the alliance.");
        Player accepted = Bukkit.getPlayer(applicant);
        if (accepted != null) {
            accepted.sendMessage(Component.text("You have been accepted into ", NamedTextColor.GRAY)
                    .append(Component.text("[" + a.getName() + "]", a.getColor()))
                    .append(Component.text(".", NamedTextColor.GRAY)));
        }
    }

    private void doReject(Player p, String[] args) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can reject join requests.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance reject <player>");
            return;
        }
        UUID applicant = plugin.getAllianceManager().resolvePlayerUUID(args[1]);
        if (applicant == null) {
            p.sendMessage(ChatColor.RED + "Unknown player '" + args[1] + "'.");
            return;
        }
        if (!a.getPendingRequests().contains(applicant)) {
            p.sendMessage(ChatColor.YELLOW + "No pending request from that player.");
            return;
        }
        plugin.getAllianceManager().rejectRequest(a, applicant);
        p.sendMessage(ChatColor.GREEN + "Rejected " + args[1] + "'s request.");
    }

    private void doPending(Player p) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (a.getPendingRequests().isEmpty()) {
            p.sendMessage(ChatColor.GRAY + "No pending join requests.");
            return;
        }
        p.sendMessage(ChatColor.GOLD + "Pending requests for '" + a.getName() + "':");
        for (UUID id : a.getPendingRequests()) {
            String n = plugin.getClaimManager().resolveName(id);
            p.sendMessage(ChatColor.YELLOW + " - " + n);
        }
        if (a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.GRAY + "Use /alliance accept <player> or /alliance reject <player>.");
        }
    }

    private void doLeave(Player p) {
        if (raidLockSelf(p)) return;
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        String err = plugin.getAllianceManager().leave(a, p.getUniqueId());
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "You have left the alliance.");
    }

    private void doKick(Player p, String[] args) {
        Alliance a = requireOwnAlliance(p);
        if (a == null) return;
        if (!a.isLeader(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Only the leader can kick members.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /alliance kick <player>");
            return;
        }
        UUID target = plugin.getAllianceManager().resolvePlayerUUID(args[1]);
        if (target == null) {
            p.sendMessage(ChatColor.RED + "Unknown player '" + args[1] + "'.");
            return;
        }
        if (raidLockOther(p, target, args[1])) return;        // can't force a raid-locked member out
        String err = plugin.getAllianceManager().kick(a, target);
        if (err != null) { p.sendMessage(ChatColor.RED + err); return; }
        p.sendMessage(ChatColor.GREEN + "Kicked " + args[1] + " from the alliance.");
        Player kicked = Bukkit.getPlayer(target);
        if (kicked != null) kicked.sendMessage(ChatColor.YELLOW
                + "You have been kicked from alliance '" + a.getName() + "'.");
    }

    private void doInfo(Player p, String[] args) {
        Alliance a;
        if (args.length >= 2) {
            a = plugin.getAllianceManager().getByName(args[1]);
            if (a == null) { p.sendMessage(ChatColor.RED + "No alliance named '" + args[1] + "'."); return; }
        } else {
            a = plugin.getAllianceManager().getOf(p.getUniqueId());
            if (a == null) {
                p.sendMessage(ChatColor.YELLOW + "You are not in an alliance. Try /alliance info <name>.");
                return;
            }
        }
        p.sendMessage(Component.text("Alliance ", NamedTextColor.GOLD)
                .append(Component.text("[" + a.getName() + "]", a.getColor())));
        p.sendMessage(ChatColor.YELLOW + "Leader: " + ChatColor.WHITE
                + plugin.getClaimManager().resolveName(a.getLeader()));
        p.sendMessage(ChatColor.YELLOW + "Members (" + a.getMembers().size() + "):");
        for (UUID id : a.getMembers()) {
            String marker = id.equals(a.getLeader()) ? ChatColor.GOLD + " (leader)" : "";
            p.sendMessage("  " + ChatColor.WHITE + plugin.getClaimManager().resolveName(id) + marker);
        }
    }

    private void doList(Player p) {
        var all = plugin.getAllianceManager().all();
        if (all.isEmpty()) { p.sendMessage(ChatColor.GRAY + "No alliances exist yet."); return; }
        p.sendMessage(ChatColor.GOLD + "Alliances (" + all.size() + "):");
        for (Alliance a : all) {
            p.sendMessage(Component.text(" - ", NamedTextColor.GRAY)
                    .append(Component.text("[" + a.getName() + "]", a.getColor()))
                    .append(Component.text("  " + a.getMembers().size() + " member"
                            + (a.getMembers().size() == 1 ? "" : "s"), NamedTextColor.GRAY)));
        }
    }

    private Alliance requireOwnAlliance(Player p) {
        Alliance a = plugin.getAllianceManager().getOf(p.getUniqueId());
        if (a == null) {
            p.sendMessage(ChatColor.RED + "You are not in an alliance.");
            return null;
        }
        return a;
    }

    // -- tab completion -------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("a")) return Collections.emptyList();
        if (args.length == 1) {
            return filter(List.of("create", "destruct", "color", "change_name", "join",
                    "accept", "reject", "pending_accepts", "leave", "kick", "info", "list"), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "color":
                case "colour":
                    return filter(AllianceManager.colorNames(), args[1]);
                case "join":
                case "info":
                    List<String> names = new ArrayList<>();
                    for (Alliance a : plugin.getAllianceManager().all()) names.add(a.getName());
                    return filter(names, args[1]);
                case "accept":
                case "reject":
                    Alliance own = plugin.getAllianceManager().getOf(p.getUniqueId());
                    if (own == null || !own.isLeader(p.getUniqueId())) return Collections.emptyList();
                    List<String> applicants = new ArrayList<>();
                    for (UUID id : own.getPendingRequests())
                        applicants.add(plugin.getClaimManager().resolveName(id));
                    return filter(applicants, args[1]);
                case "kick":
                    Alliance ownAll = plugin.getAllianceManager().getOf(p.getUniqueId());
                    if (ownAll == null || !ownAll.isLeader(p.getUniqueId())) return Collections.emptyList();
                    List<String> mem = new ArrayList<>();
                    for (UUID id : ownAll.getMembers())
                        if (!id.equals(ownAll.getLeader()))
                            mem.add(plugin.getClaimManager().resolveName(id));
                    return filter(mem, args[1]);
            }
        }
        if (args.length == 3 && sub.equals("create")) {
            return filter(AllianceManager.colorNames(), args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) return options;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        return out;
    }
}
