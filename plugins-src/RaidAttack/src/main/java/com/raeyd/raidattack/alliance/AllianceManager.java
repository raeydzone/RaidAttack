package com.raeyd.raidattack.alliance;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.core.ChatListener;
import com.raeyd.raidattack.data.WorldDatabase;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Server-wide alliance registry. Owns:
 * <ul>
 *   <li>The canonical {@code lowerName → Alliance} map.</li>
 *   <li>A reverse index {@code playerUUID → Alliance} for O(1) "what alliance is this player in?"
 *       lookups (critical path: chat formatter, PvP cancel, every tick of buffs).</li>
 *   <li>Persistence to {@code alliances.yml} in the plugin data folder.</li>
 *   <li>The vanilla {@link Scoreboard} teams that render the {@code [Tag]} prefix above each
 *       member's head. One team per alliance, named {@code rasally_<lowerName>}; rebuilt from
 *       scratch on rename so the prefix component stays consistent.</li>
 *   <li>Toggle state for {@code /a} alliance-only chat (per-player flag, in-memory only — the
 *       toggle is intentionally session-scoped so a relog resets it).</li>
 * </ul>
 *
 * <p>This class is the single mutation entrypoint for alliances. Holders of an {@link Alliance}
 * reference can read fields, but any state change (rename, recolor, add/remove member, leader
 * change, disband) goes through here so the reverse index, scoreboard teams, and yml file stay
 * in lockstep.
 */
public final class AllianceManager {

    private static final String TEAM_PREFIX = "rasally_";

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    private final Map<String, Alliance> byLowerName = new HashMap<>();
    /** Reverse index. Concurrent because the async chat renderer ({@link ChatListener}) reads it
     *  off the main thread via {@link #getOf(UUID)} while the main thread mutates it on
     *  create/join/leave/kick/disband. */
    private final Map<UUID, Alliance> byMember = new ConcurrentHashMap<>();
    /** Players who toggled /a — their normal chat is treated as alliance-only until untoggled.
     *  Concurrent for the same reason as {@link #byMember}: read from the async chat thread. */
    private final Set<UUID> allianceOnlyChat = ConcurrentHashMap.newKeySet();

    public AllianceManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    // -- lookup ---------------------------------------------------------------

    public Alliance getByName(String name) {
        if (name == null) return null;
        return byLowerName.get(name.toLowerCase(Locale.ROOT));
    }

    public Alliance getOf(UUID playerId) {
        if (playerId == null) return null;
        return byMember.get(playerId);
    }

    public Collection<Alliance> all() { return byLowerName.values(); }

    public boolean isAllianceOnlyChat(UUID id) { return allianceOnlyChat.contains(id); }

    /** Returns the new toggle state. */
    public boolean toggleAllianceOnlyChat(UUID id) {
        if (allianceOnlyChat.contains(id)) { allianceOnlyChat.remove(id); return false; }
        allianceOnlyChat.add(id); return true;
    }

    // -- creation / removal ---------------------------------------------------

    /**
     * Try to create a new alliance. Returns {@code null} on success, or an error string the
     * caller can show the user. Validation: name length, charset, uniqueness (case-insensitive),
     * and that the leader isn't already in another alliance.
     */
    public String create(String displayName, NamedTextColor color, Player leader) {
        if (displayName == null || displayName.isBlank()) return "Name cannot be empty.";
        if (displayName.length() > Alliance.MAX_NAME_LENGTH)
            return "Name must be at most " + Alliance.MAX_NAME_LENGTH + " characters.";
        if (!displayName.matches("[A-Za-z0-9_]+"))
            return "Name may only contain letters, digits, and underscores.";
        if (color == null) return "Unknown colour.";
        if (byLowerName.containsKey(displayName.toLowerCase(Locale.ROOT)))
            return "An alliance named '" + displayName + "' already exists.";
        if (byMember.containsKey(leader.getUniqueId()))
            return "You are already in an alliance. Leave or disband it first.";

        Alliance a = new Alliance(displayName, color, leader.getUniqueId(), System.currentTimeMillis());
        byLowerName.put(displayName.toLowerCase(Locale.ROOT), a);
        byMember.put(leader.getUniqueId(), a);
        // Persist first so the alliance gets its stable id (the leader member row is inserted too).
        a.setId(worldDb.insertAlliance(displayName, displayName.toLowerCase(Locale.ROOT),
                color.toString(), leader.getUniqueId(), a.getCreatedAtMillis()));
        rebuildTeam(a);
        applyTeamToOnlineMembers(a);
        return null;
    }

    /** Disband — removes the alliance, scrubs every member from the reverse index and team. */
    public void disband(Alliance a) {
        // Snapshot members before clearing — the detach loop below walks the same set.
        List<UUID> members = new ArrayList<>(a.getMembers());
        byLowerName.remove(a.getName().toLowerCase(Locale.ROOT));
        for (UUID id : members) byMember.remove(id);

        // Detach online members from the team FIRST, while it still exists. removeEntity pushes a
        // per-player nameplate/tab refresh, so the [Tag] prefix actually clears on their client —
        // mirroring leave()/kick(). The previous order (unregister, THEN detach) made this loop a
        // no-op against an already-removed team, which left the scoreboard prefix stuck until the
        // member relogged even though their chat tag (read live from byMember) had already cleared.
        for (UUID id : members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) detachFromAllTeams(p);
        }
        // Tear down the now-empty team.
        Team team = teamOf(a);
        if (team != null) {
            try { team.unregister(); } catch (Throwable ignored) {}
        }
        worldDb.deleteAlliance(a.getId());   // cascades members + join-requests
    }

    /** Returns null on success or an error message. */
    public String rename(Alliance a, String newDisplayName) {
        if (newDisplayName == null || newDisplayName.isBlank()) return "Name cannot be empty.";
        if (newDisplayName.length() > Alliance.MAX_NAME_LENGTH)
            return "Name must be at most " + Alliance.MAX_NAME_LENGTH + " characters.";
        if (!newDisplayName.matches("[A-Za-z0-9_]+"))
            return "Name may only contain letters, digits, and underscores.";
        String newLower = newDisplayName.toLowerCase(Locale.ROOT);
        String oldLower = a.getName().toLowerCase(Locale.ROOT);
        if (newLower.equals(oldLower)) {
            // Casing-only change — allow.
            a.setName(newDisplayName);
            rebuildTeam(a);
            applyTeamToOnlineMembers(a);
            worldDb.renameAlliance(a.getId(), newDisplayName, newLower);
            return null;
        }
        if (byLowerName.containsKey(newLower))
            return "An alliance named '" + newDisplayName + "' already exists.";

        // Tear down the old team (its name encodes the old alliance name) and re-register.
        Team old = teamOf(a);
        if (old != null) {
            try { old.unregister(); } catch (Throwable ignored) {}
        }
        byLowerName.remove(oldLower);
        a.setName(newDisplayName);
        byLowerName.put(newLower, a);
        rebuildTeam(a);
        applyTeamToOnlineMembers(a);
        worldDb.renameAlliance(a.getId(), newDisplayName, newLower);
        return null;
    }

    public void setColor(Alliance a, NamedTextColor color) {
        a.setColor(color);
        rebuildTeam(a);
        applyTeamToOnlineMembers(a);
        worldDb.setAllianceColor(a.getId(), color.toString());
    }

    // -- membership -----------------------------------------------------------

    /** Returns null on success or an error string. */
    public String requestJoin(Alliance a, Player applicant) {
        if (byMember.containsKey(applicant.getUniqueId()))
            return "You are already in an alliance. Leave it first.";
        if (a.getPendingRequests().contains(applicant.getUniqueId()))
            return "You already have a pending request to '" + a.getName() + "'.";
        a.getPendingRequests().add(applicant.getUniqueId());
        worldDb.addAllianceRequest(a.getId(), applicant.getUniqueId());
        return null;
    }

    /**
     * Accept a pending request. Caller must be the leader. Returns null on success or an error.
     * Side effects: applicant becomes a member, reverse index updates, scoreboard team applied.
     */
    public String acceptRequest(Alliance a, UUID applicant) {
        if (!a.getPendingRequests().remove(applicant))
            return "No pending request from that player.";
        if (byMember.containsKey(applicant)) {
            // They joined another alliance after requesting — clean up but don't add.
            worldDb.removeAllianceRequest(a.getId(), applicant);
            return "That player has joined a different alliance in the meantime.";
        }
        a.getMembers().add(applicant);
        byMember.put(applicant, a);
        Player p = Bukkit.getPlayer(applicant);
        if (p != null) applyTeamTo(a, p);
        worldDb.removeAllianceRequest(a.getId(), applicant);
        worldDb.addAllianceMember(a.getId(), applicant);
        return null;
    }

    public void rejectRequest(Alliance a, UUID applicant) {
        a.getPendingRequests().remove(applicant);
        worldDb.removeAllianceRequest(a.getId(), applicant);
    }

    /**
     * Leave the alliance. Returns null on success or an error. The leader cannot leave — they
     * must {@link #disband(Alliance)} instead (the spec doesn't define leadership transfer).
     */
    public String leave(Alliance a, UUID memberId) {
        if (a.isLeader(memberId))
            return "Leaders cannot leave — use /alliance destruct to disband instead.";
        if (!a.getMembers().remove(memberId))
            return "You are not in that alliance.";
        byMember.remove(memberId);
        Player p = Bukkit.getPlayer(memberId);
        if (p != null) detachFromAllTeams(p);
        worldDb.removeAllianceMember(a.getId(), memberId);
        return null;
    }

    /**
     * Kick a member. Leader-only. Returns null on success or error. Same effects as
     * {@link #leave(Alliance, UUID)}, just initiated by the leader.
     */
    public String kick(Alliance a, UUID memberId) {
        if (a.isLeader(memberId)) return "Cannot kick the leader.";
        if (!a.getMembers().remove(memberId)) return "That player is not in your alliance.";
        byMember.remove(memberId);
        Player p = Bukkit.getPlayer(memberId);
        if (p != null) detachFromAllTeams(p);
        worldDb.removeAllianceMember(a.getId(), memberId);
        return null;
    }

    // -- scoreboard team ------------------------------------------------------

    private String teamNameOf(Alliance a) {
        return TEAM_PREFIX + a.getName().toLowerCase(Locale.ROOT);
    }

    private Team teamOf(Alliance a) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        return sb.getTeam(teamNameOf(a));
    }

    /**
     * (Re)create the scoreboard team for this alliance with the current name and colour. Must
     * be called after any rename or recolour. Adds existing members to the new team.
     */
    public void rebuildTeam(Alliance a) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String teamName = teamNameOf(a);
        Team team = sb.getTeam(teamName);
        if (team == null) team = sb.registerNewTeam(teamName);

        team.prefix(Component.text("[" + a.getName() + "] ", a.getColor()));
        // Player NAME stays white — only the prefix wears the alliance colour. Team#color sets
        // the colour of the name itself, so we force it to WHITE here regardless of the alliance.
        team.color(NamedTextColor.WHITE);
        // We don't rely on Team#friendlyFire — it only suppresses direct melee. Alliance-PvP
        // cancellation lives in AlliancePvpListener so projectiles and AoE are covered too.
        team.setAllowFriendlyFire(true);

        // (Re)add every online member. Offline members are added when they next join the server.
        for (UUID id : a.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) team.addEntity(p);
        }
    }

    /** Apply this alliance's team to the given (online) player. */
    public void applyTeamTo(Alliance a, Player p) {
        Team team = teamOf(a);
        if (team == null) {
            rebuildTeam(a);
            team = teamOf(a);
            if (team == null) return;
        }
        // Remove from any other alliance team first (defensive: shouldn't normally happen).
        detachFromAllTeams(p);
        team.addEntity(p);
    }

    /**
     * Apply this alliance's team to every currently-online member. Called after a rebuild — the
     * team has already been (re)registered, this just makes sure entity attachments are fresh.
     */
    private void applyTeamToOnlineMembers(Alliance a) {
        Team team = teamOf(a);
        if (team == null) return;
        for (UUID id : a.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) team.addEntity(p);
        }
    }

    /** Remove the player from any {@code rasally_*} team. Idempotent. */
    public void detachFromAllTeams(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith(TEAM_PREFIX) && t.hasEntity(p)) {
                t.removeEntity(p);
            }
        }
    }

    /**
     * Drop any {@code rasally_*} scoreboard team that no longer maps to a live alliance. The vanilla
     * server persists the main scoreboard (teams + entries) to {@code scoreboard.dat}, so a team can
     * survive a disband/rename/crash and keep rendering a dead {@code [Tag]}. Run on load so the
     * scoreboard always reflects the current alliance registry.
     */
    private void pruneOrphanTeams() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        Set<String> live = new HashSet<>();
        for (Alliance a : byLowerName.values()) live.add(teamNameOf(a));
        for (Team t : new ArrayList<>(sb.getTeams())) {
            if (t.getName().startsWith(TEAM_PREFIX) && !live.contains(t.getName())) {
                try { t.unregister(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Called from PlayerJoinEvent so a relogging member's nameplate prefix is restored even if
     * the scoreboard team entity attachment was lost (offline players aren't reliably persisted
     * across restarts in older versions of Bukkit's scoreboard impl).
     */
    public void onPlayerJoin(Player p) {
        Alliance a = byMember.get(p.getUniqueId());
        if (a != null) applyTeamTo(a, p);
    }

    /**
     * Parse a colour from a user-supplied string. Accepts the 16 vanilla named colours
     * (case-insensitive, with or without underscores), e.g. {@code red}, {@code Dark_Blue},
     * {@code lightpurple}. Returns null on unknown input.
     */
    public static NamedTextColor parseColor(String input) {
        if (input == null) return null;
        String norm = input.trim().toLowerCase(Locale.ROOT).replace(" ", "_");
        // Try direct lookup first.
        NamedTextColor direct = NamedTextColor.NAMES.value(norm);
        if (direct != null) return direct;
        // Also accept underscore-stripped forms ("darkblue", "lightpurple").
        switch (norm) {
            case "darkblue":    return NamedTextColor.DARK_BLUE;
            case "darkgreen":   return NamedTextColor.DARK_GREEN;
            case "darkaqua":    return NamedTextColor.DARK_AQUA;
            case "darkred":     return NamedTextColor.DARK_RED;
            case "darkpurple":  return NamedTextColor.DARK_PURPLE;
            case "darkgray":
            case "darkgrey":    return NamedTextColor.DARK_GRAY;
            case "lightpurple":
            case "pink":        return NamedTextColor.LIGHT_PURPLE;
            case "grey":        return NamedTextColor.GRAY;
            default:            return null;
        }
    }

    public static List<String> colorNames() {
        return List.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red",
                "dark_purple", "gold", "gray", "dark_gray", "blue", "green",
                "aqua", "red", "light_purple", "yellow", "white");
    }

    // -- persistence ----------------------------------------------------------

    public void load() {
        byLowerName.clear();
        byMember.clear();
        for (WorldDatabase.AllianceRow row : worldDb.loadAlliances()) {
            NamedTextColor color = parseColor(row.color());
            if (color == null) color = NamedTextColor.WHITE;
            Alliance a = new Alliance(row.name(), color, row.leader(),
                    new LinkedHashSet<>(row.members()), new LinkedHashSet<>(row.pending()), row.createdAtMillis());
            a.setId(row.id());
            byLowerName.put(row.name().toLowerCase(Locale.ROOT), a);
            for (UUID m : a.getMembers()) byMember.put(m, a);
            rebuildTeam(a);
        }
        pruneOrphanTeams();   // drop any leftover rasally_* team with no live alliance
    }

    /** Resolve a name to an online or offline player UUID. Returns null if unknown. */
    public UUID resolvePlayerUUID(String name) {
        if (name == null || name.isBlank()) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        // Fall back to the claim manager's name cache (also fed by every player who joins).
        UUID cached = plugin.getClaimManager().resolveUUID(name);
        if (cached != null) return cached;
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore() || off.isOnline()) return off.getUniqueId();
        return null;
    }
}
