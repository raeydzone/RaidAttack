package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitTask;

/**
 * Central registry for every {@link CustomRaider} and {@link CustomRavager} the plugin spawns.
 *
 * <p>Purpose:
 * <ul>
 *   <li><b>Lifecycle ownership.</b> Spawn callers hand the wrapper here; the manager despawns
 *       every live one on plugin disable. Without this, a {@code /reload} or crash would
 *       orphan Citizens registry entries the same way it does for turret shulkers.</li>
 *   <li><b>Reaping.</b> Periodic sweep prunes entries whose underlying entity has died or
 *       whose Citizens NPC has been destroyed externally, so callers querying "is X still
 *       alive?" don't trip over stale wrappers.</li>
 *   <li><b>Orphan cleanup.</b> Independent of any per-wrapper state, scans the Citizens
 *       registry for NPCs carrying our {@code raid-role} data key but no live wrapper, and
 *       destroys them. Catches Citizens entries that survived a server crash when our state
 *       didn't — analogous to {@link TurretEntityManager#wipeOrphanNPCs()}.</li>
 * </ul>
 *
 * <p>Indexed by entity {@link UUID} (Bukkit-level), not Citizens NPC id, because the AI tick
 * always has an {@code Entity} in hand and looking up by entity-id is the common path.
 */
public final class RaidEntityManager {

    /** How often to reap dead wrappers + orphan NPCs (ticks). */
    private static final long SWEEP_INTERVAL_TICKS = 100L;  // 5 s

    private final HomeSystemPlugin plugin;
    private final Map<UUID, CustomRaider>  raiders  = new LinkedHashMap<>();
    private final Map<UUID, CustomRavager> ravagers = new LinkedHashMap<>();
    private BukkitTask sweepTask;

    public RaidEntityManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (sweepTask != null) return;
        // Wipe every raid NPC the Citizens registry persisted from a previous session.
        // We can't trust the in-memory wrapper maps to identify orphans — Citizens saves NPCs
        // across restarts but our raid-id/raid-role data tags don't persist (they're set via
        // data().set, not setPersistent). So we fall back to the name pattern that we DO
        // control at spawn time: "<attacker>'s Raider" / "<attacker>'s Ravager". Anything
        // matching is from a prior run; recovery in RaidSpawnEngine will re-spawn the correct
        // alive count fresh.
        int wiped = wipeAllRaidNpcsByName();
        if (wiped > 0) {
            plugin.getLogger().info("RaidEntityManager: wiped " + wiped
                    + " stale raid NPC(s) from previous session at startup.");
        }
        sweepTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sweep, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }

    public void stop() {
        if (sweepTask != null) { sweepTask.cancel(); sweepTask = null; }
        despawnAll();
    }

    // -- spawn registration ---------------------------------------------------

    /** Register a freshly-spawned raider so it'll be cleaned up on plugin disable / sweep. */
    public void register(CustomRaider r) {
        var e = r.getEntity();
        if (e == null) return;
        raiders.put(e.getUniqueId(), r);
    }

    public void register(CustomRavager r) {
        var e = r.getEntity();
        if (e == null) return;
        ravagers.put(e.getUniqueId(), r);
    }

    /** Forget about + despawn this raider. Idempotent. */
    public void destroy(CustomRaider r) {
        var e = r.getEntity();
        if (e != null) raiders.remove(e.getUniqueId());
        r.despawn();
    }

    public void destroy(CustomRavager r) {
        var e = r.getEntity();
        if (e != null) ravagers.remove(e.getUniqueId());
        r.despawn();
    }

    public CustomRaider  getRaider(UUID entityId)  { return raiders.get(entityId); }
    public CustomRavager getRavager(UUID entityId) { return ravagers.get(entityId); }

    /** Snapshot of every currently-tracked raider/ravager wrapper. Safe to iterate over. */
    public List<CustomRaider>  allRaiders()  { return new ArrayList<>(raiders.values()); }
    public List<CustomRavager> allRavagers() { return new ArrayList<>(ravagers.values()); }

    /** Destroy every tracked NPC and clear the registry. Called on plugin disable. */
    public void despawnAll() {
        for (CustomRaider  r : new ArrayList<>(raiders.values()))  r.despawn();
        for (CustomRavager r : new ArrayList<>(ravagers.values())) r.despawn();
        raiders.clear();
        ravagers.clear();
    }

    /**
     * Scan the Citizens registry for any NPC whose stripped-of-color name ends with
     * {@code 's Raider} or {@code 's Ravager} and destroy them. Used at boot to nuke
     * orphans Citizens persisted from a prior session, and exposed via
     * {@code /HomeSystem dev cleartest} so the user can clean state mid-session without
     * relying on the in-memory wrapper maps (which are empty after a fresh boot).
     *
     * <p>Returns the number of NPCs destroyed. Also clears the in-memory wrappers so the
     * next sweep tick won't try to despawn entities that no longer exist.
     */
    public int wipeAllRaidNpcsByName() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return 0;
        int count = 0;
        try {
            List<NPC> kill = new ArrayList<>();
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                String name = npc.getName();
                if (name == null) continue;
                String stripped = ChatColor.stripColor(name);
                if (stripped == null) continue;
                if (stripped.endsWith("'s Raider") || stripped.endsWith("'s Ravager")) {
                    kill.add(npc);
                }
            }
            for (NPC npc : kill) {
                try { npc.destroy(); count++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("wipeAllRaidNpcsByName failed: " + t.getMessage());
        }
        raiders.clear();
        ravagers.clear();
        return count;
    }

    /**
     * Destroy every NPC belonging to a specific raid (by {@link UUID}). Used when a raid ends
     * (all attackers dead, or owner unclaimed the zone mid-raid). Returns the count destroyed.
     */
    public int despawnByRaid(UUID raidId) {
        if (raidId == null) return 0;
        int n = 0;
        for (Iterator<Map.Entry<UUID, CustomRaider>> it = raiders.entrySet().iterator(); it.hasNext(); ) {
            CustomRaider r = it.next().getValue();
            if (raidId.equals(r.getRaidId())) { r.despawn(); it.remove(); n++; }
        }
        for (Iterator<Map.Entry<UUID, CustomRavager>> it = ravagers.entrySet().iterator(); it.hasNext(); ) {
            CustomRavager r = it.next().getValue();
            if (raidId.equals(r.getRaidId())) { r.despawn(); it.remove(); n++; }
        }
        return n;
    }

    /** How many raid-role NPCs (any kind) are currently alive for this raid. */
    public int aliveCountForRaid(UUID raidId) {
        if (raidId == null) return 0;
        int n = 0;
        for (CustomRaider r : raiders.values())  if (raidId.equals(r.getRaidId()) && r.isAlive()) n++;
        for (CustomRavager r : ravagers.values()) if (raidId.equals(r.getRaidId()) && r.isAlive()) n++;
        return n;
    }

    // -- sweeping -------------------------------------------------------------

    /**
     * Drop wrappers whose entity has died (so {@link #aliveCountForRaid} stays accurate) and
     * destroy any "raid-role" tagged Citizens NPC the registry doesn't know about (an orphan
     * left over from a crash, /reload, or a wrapper losing track of its NPC due to a Citizens
     * persistence quirk).
     */
    private void sweep() {
        raiders.entrySet().removeIf(e -> !e.getValue().isAlive());
        ravagers.entrySet().removeIf(e -> !e.getValue().isAlive());
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return;
        int orphans = 0;
        try {
            List<NPC> kill = new ArrayList<>();
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                Object role = npc.data().get("raid-role");
                if (role == null) continue;
                UUID entityId = npc.isSpawned() && npc.getEntity() != null
                        ? npc.getEntity().getUniqueId() : null;
                if (entityId != null
                        && (raiders.containsKey(entityId) || ravagers.containsKey(entityId))) continue;
                kill.add(npc);
            }
            for (NPC npc : kill) {
                try { npc.destroy(); orphans++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("RaidEntityManager sweep failed: " + t.getMessage());
        }
        if (orphans > 0) {
            plugin.getLogger().info("RaidEntityManager: wiped " + orphans + " orphan raid NPC(s).");
        }
    }
}
