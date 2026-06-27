package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The "rights" registry backing the {@code /ra} permission model and the routed moderation
 * commands ({@code /ban}, {@code /kick}, {@code /unban}). Two tiers:
 *
 * <ul>
 *   <li><b>owner</b> — full authority: treated as OP (any vanilla command) <em>and</em> may use
 *       every moderation command. Owners can ONLY be set by editing {@code rights.yml} by hand;
 *       there is deliberately no in-game path to grant the owner right.</li>
 *   <li><b>admin</b> — may use {@code /ban}, {@code /kick} and {@code /unban} only. Admins can be
 *       set by editing {@code rights.yml} <em>or</em> granted in-game by an owner via
 *       {@code /ra rights add admin <player>}.</li>
 * </ul>
 *
 * <p>Entries are stored verbatim as the user typed them — either a player name (matched
 * case-insensitively) or a UUID string. The console and command blocks are always treated as
 * owner-level so server automation is never locked out.
 */
public final class RightsManager {

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;

    private final Set<String> owners = new LinkedHashSet<>();
    private final Set<String> admins = new LinkedHashSet<>();

    public RightsManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    // -- persistence (world.server_rights) ------------------------------------

    public void load() {
        owners.clear();
        admins.clear();
        owners.addAll(worldDb.loadRights("owner"));
        admins.addAll(worldDb.loadRights("admin"));
        if (owners.isEmpty()) {
            // Owners are security-sensitive and set by hand (no in-game command). With the DB move
            // that hand-edit is now SQL while the server is off, e.g.:
            //   INSERT INTO world.server_rights (role, identifier) VALUES ('owner', '<name-or-uuid>');
            plugin.getLogger().info("No RaidAttack owners set. Add one via SQL: "
                    + "INSERT INTO world.server_rights (role, identifier) VALUES ('owner', '<name-or-uuid>');");
        }
    }

    // -- permission checks ----------------------------------------------------

    public boolean isOwner(CommandSender sender) {
        if (!(sender instanceof Player p)) return true;   // console = owner-level
        return matches(owners, p);
    }

    /** Owner OR admin. */
    public boolean isAdmin(CommandSender sender) {
        if (!(sender instanceof Player p)) return true;
        return matches(admins, p) || matches(owners, p);
    }

    /** Who may run {@code /ban}, {@code /kick}, {@code /unban}: owners and admins. */
    public boolean canModerate(CommandSender sender) {
        return isAdmin(sender);
    }

    private static boolean matches(Set<String> set, Player p) {
        String name = p.getName();
        String uuid = p.getUniqueId().toString();
        for (String s : set) {
            if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(uuid)) return true;
        }
        return false;
    }

    // -- in-game admin grant/revoke (owner-gated by the caller) ----------------

    /** Add an admin by name. Returns null on success or an error string. */
    public String addAdmin(String playerName) {
        if (playerName == null || playerName.isBlank()) return "Empty player name.";
        if (containsIgnoreCase(owners, playerName)) return playerName + " is already an owner.";
        if (containsIgnoreCase(admins, playerName)) return playerName + " is already an admin.";
        admins.add(playerName);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> worldDb.addRight("admin", playerName));
        return null;
    }

    /** Remove an admin by name. Owners are untouched (they're managed by hand via SQL). */
    public String removeAdmin(String playerName) {
        String found = null;
        for (String s : admins) if (s.equalsIgnoreCase(playerName)) { found = s; break; }
        if (found == null) return playerName + " is not an admin.";
        final String target = found;
        admins.remove(target);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> worldDb.removeRight("admin", target));
        return null;
    }

    private static boolean containsIgnoreCase(Set<String> set, String value) {
        for (String s : set) if (s.equalsIgnoreCase(value)) return true;
        return false;
    }

    // -- owner OP enforcement -------------------------------------------------

    /** Owners "have OP rights" — ensure (never revoke) the vanilla op flag for an owner. */
    public void ensureOwnerOp(Player p) {
        if (matches(owners, p) && !p.isOp()) {
            p.setOp(true);
            plugin.getLogger().info("Granted OP to owner '" + p.getName() + "' (rights.yml).");
        }
    }

    public void ensureAllOnlineOwnersOp() {
        for (Player p : Bukkit.getOnlinePlayers()) ensureOwnerOp(p);
    }

    // -- read-only views ------------------------------------------------------

    public List<String> owners() { return new ArrayList<>(owners); }
    public List<String> admins() { return new ArrayList<>(admins); }
}
