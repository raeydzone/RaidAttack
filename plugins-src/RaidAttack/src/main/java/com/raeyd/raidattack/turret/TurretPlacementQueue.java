package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Defers the physical placement of a freshly-deployed turret until no player is standing on or
 * directly over its 5×5 footprint. Without this, the deploying player would get walled in by
 * layers 2–6 the instant they typed the command.
 *
 * <p>Pending state is in-memory only. If the server crashes between {@code turret deploy} and
 * the player stepping off the footprint, the turret coord persists in {@code claims.yml} but
 * no structure is built — the owner can {@code turret remove} and re-deploy. (Acceptable for
 * this dev server; a production fix would persist the pending queue too.)
 */
public final class TurretPlacementQueue {

    /** Place when no player is within this many horizontal blocks of the anchor centre. */
    private static final double CLEARANCE_RADIUS = 3.0;
    private static final double CLEARANCE_RADIUS_SQ = CLEARANCE_RADIUS * CLEARANCE_RADIUS;
    private static final long TICK_INTERVAL = 20L;   // check once per second

    private final HomeSystemPlugin plugin;
    /** turret → world UUID for that turret's claim. */
    private final Map<Turret, UUID> pending = new HashMap<>();
    private BukkitTask task;

    public TurretPlacementQueue(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        pending.clear();
    }

    /** Queue a turret for deferred placement. No-op if already queued. */
    public void queue(World world, Turret turret) {
        pending.put(turret, world.getUID());
    }

    /**
     * Drop a pending placement before it fires. Returns true if it was actually pending — used by
     * {@code turret remove} to avoid clearing a structure that was never placed.
     */
    public boolean cancel(Turret turret) {
        return pending.remove(turret) != null;
    }

    /** True if this turret is still waiting to be physically built. */
    public boolean isPending(Turret turret) {
        return pending.containsKey(turret);
    }

    private void tick() {
        if (pending.isEmpty()) return;
        Iterator<Map.Entry<Turret, UUID>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Turret, UUID> entry = it.next();
            Turret t = entry.getKey();
            World world = Bukkit.getWorld(entry.getValue());
            if (world == null) {
                // World unloaded — drop the queue entry so we don't spin forever.
                it.remove();
                continue;
            }
            if (footprintIsClear(world, t)) {
                // Carve the protected volume to air first so nothing blocks the turret or its
                // approach ring, then build the structure into the cleared shaft.
                TurretStructure.clearProtectionVolume(world, t);
                TurretStructure.place(world, t);
                int level = lookupLevel(t);
                int npcId = plugin.getTurretEntities().spawn(world, t, level);
                if (npcId >= 0) {
                    t.setNpcId(npcId);
                    plugin.getClaimManager().save();
                }
                it.remove();
            }
        }
    }

    /** Look up the level for a turret by walking the claim map. Defaults to 1 if not found. */
    private int lookupLevel(Turret t) {
        for (Claim claim : plugin.getClaimManager().all().values()) {
            if (claim.getSlotTurret(t.getSlot()) == t) return claim.getSlotLevel(t.getSlot());
        }
        return 1;
    }

    private static boolean footprintIsClear(World world, Turret t) {
        double cx = t.getX() + 0.5;
        double cz = t.getZ() + 0.5;
        for (Player p : world.getPlayers()) {
            double dx = p.getLocation().getX() - cx;
            double dz = p.getLocation().getZ() - cz;
            if (dx * dx + dz * dz <= CLEARANCE_RADIUS_SQ) return false;
        }
        return true;
    }
}
