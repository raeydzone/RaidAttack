package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.combat.WitherCombatManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Shulker;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the Shulker NPC that occupies each placed turret's layer-5 centre. Uses Citizens so the
 * mob has no vanilla AI by default (no peeking, no bullet-shooting, no teleport-on-damage). The
 * NPC is marked protected, persists across restarts via Citizens' own save file, and is
 * respawned by {@link #tick()} if it ever disappears.
 *
 * <p>Spawn position: {@code (x+0.5, y+4, z+0.5)} relative to the turret anchor — i.e. directly
 * above the layer-4 centre wall, so the shulker has a surface to attach to.
 *
 * <p>Citizens is a soft dependency. If it isn't loaded, this class no-ops cleanly and turret
 * structures still build, just without the shulker entity.
 */
public final class TurretEntityManager {

    /** How often to verify every turret's NPC still exists (ticks). */
    private static final long VALIDATION_INTERVAL_TICKS = 100L;  // 5 s

    private final HomeSystemPlugin plugin;
    private final boolean citizensAvailable;
    private BukkitTask task;

    public TurretEntityManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.citizensAvailable = Bukkit.getPluginManager().getPlugin("Citizens") != null;
        if (!citizensAvailable) {
            plugin.getLogger().warning(
                    "Citizens plugin not detected — turret shulker entities will not spawn.");
        }
    }

    public boolean isCitizensAvailable() { return citizensAvailable; }

    public void start() {
        if (task != null || !citizensAvailable) return;
        // One-time dedup of npcIds across all claims, to repair any leftover corruption from
        // earlier sessions before the dedupe-on-tick guard existed. Saves only if anything
        // actually changed.
        boolean anyDeduped = false;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            if (dedupeNpcIds(claim)) anyDeduped = true;
        }
        if (anyDeduped) plugin.getClaimManager().save();

        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, VALIDATION_INTERVAL_TICKS, VALIDATION_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        // Intentionally do NOT despawn NPCs on plugin disable — Citizens persists them across
        // restarts; destroying here would erase the registry entry permanently.
    }

    /**
     * Spawn a Shulker NPC for this turret. The {@code level} is just used for the nametag —
     * combat behaviour reads the level live from the Claim each tick. Returns the new NPC id
     * (or {@code -1} on failure). Caller persists the id onto the {@link Turret}.
     */
    public int spawn(World world, Turret turret, int level) {
        if (!citizensAvailable || world == null) return -1;
        Location loc = new Location(world,
                turret.getX() + 0.5,
                turret.getY() + 4,    // layer-5 centre (anchor + 4)
                turret.getZ() + 0.5);
        try {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.SHULKER, nameFor(turret, level));
            npc.setProtected(true);
            npc.setUseMinecraftAI(false);  // belt + suspenders: kill vanilla goals
            npc.spawn(loc);
            // Activation hook: structure-combat manager spawns the phantom HP target for this
            // turret in the same step. Idempotent — safe to call even if a phantom already exists.
            if (plugin.getWitherCombatManager() != null) {
                plugin.getWitherCombatManager().onTurretActivated(world, turret);
            }
            return npc.getId();
        } catch (Throwable t) {
            plugin.getLogger().severe("Failed to spawn turret NPC at " + turret + ": " + t.getMessage());
            return -1;
        }
    }

    /** Update the nametag of an existing NPC to reflect the given level. No-op if not spawned. */
    public void refreshName(Turret turret, int level) {
        if (!citizensAvailable || turret.getNpcId() < 0) return;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(turret.getNpcId());
            if (npc != null) npc.setName(nameFor(turret, level));
        } catch (Throwable ignored) {}
    }

    private static String nameFor(Turret turret, int level) {
        return "Turret #" + (turret.getSlot() + 1) + " (Level " + level + ")";
    }

    /**
     * Find and destroy every Citizens NPC that looks like one of ours (Shulker entity with a
     * {@code Turret #N (Level L)} name) but isn't referenced by any current turret slot.
     * Returns the number destroyed. Used by {@code /HomeSystem dev wipeorphans} to clean up
     * NPCs left over from old test deployments or from claims that were removed without
     * cleanup before the cleanup hook existed.
     */
    public int wipeOrphanNPCs() {
        if (!citizensAvailable) return 0;
        // Collect all live-known npcIds across claims so we don't destroy our own.
        java.util.Set<Integer> live = new java.util.HashSet<>();
        for (Claim claim : plugin.getClaimManager().all().values()) {
            for (Turret t : claim.getTurrets()) {
                if (t.getNpcId() >= 0) live.add(t.getNpcId());
            }
        }
        int destroyed = 0;
        try {
            java.util.List<NPC> toRemove = new java.util.ArrayList<>();
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                String name = npc.getName();
                if (name == null || !name.startsWith("Turret #")) continue;
                if (live.contains(npc.getId())) continue;
                // Same Shulker guard as destroyOrphansAtAnchor: a spawned non-Shulker carrying a
                // "Turret #" name is a corrupted raider, not an orphan turret — leave it alone.
                if (npc.isSpawned() && npc.getEntity() != null
                        && !(npc.getEntity() instanceof Shulker)) continue;
                toRemove.add(npc);
            }
            for (NPC npc : toRemove) {
                Location stored = npc.getStoredLocation();
                if (stored != null && stored.getWorld() != null) {
                    int anchorX = stored.getBlockX();
                    int anchorY = stored.getBlockY() - 4;     // shulker layer-5 → anchor.y - 4
                    int anchorZ = stored.getBlockZ();
                    // CRITICAL: only raze the structure if its anchor doesn't match any
                    // currently-tracked turret slot. A destroyed turret in respawn downtime has
                    // npcId = -1, so its old (now-stale) Citizens entry could get caught here as
                    // an "orphan" — but the slot still owns those coordinates and the structure
                    // is meant to stay visible until respawn. Clearing it caused the user-reported
                    // "structure disappears after restart" bug. Match by exact int coordinates.
                    boolean slotOwnsAnchor = false;
                    java.util.UUID worldId = stored.getWorld().getUID();
                    for (Claim claim : plugin.getClaimManager().all().values()) {
                        if (!claim.getWorldId().equals(worldId)) continue;
                        for (Turret t : claim.getTurrets()) {
                            if (t.getX() == anchorX && t.getY() == anchorY && t.getZ() == anchorZ) {
                                slotOwnsAnchor = true;
                                break;
                            }
                        }
                        if (slotOwnsAnchor) break;
                    }
                    if (!slotOwnsAnchor) {
                        TurretStructure.clearAt(stored.getWorld(), anchorX, anchorY, anchorZ);
                    }
                }
                npc.destroy();
                destroyed++;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("wipeOrphanNPCs scan failed: " + t.getMessage());
        }
        return destroyed;
    }

    /**
     * Validate a single turret without sweeping the entire world. Used by
     * {@link TurretChunkListener} so a chunk-load event for one chunk doesn't trigger N full
     * forceValidateAll calls (which was creating duplicate NPCs when multiple turrets shared a
     * chunk). Dedupes the claim first, then runs the same alive/respawn/recreate logic as the
     * periodic tick.
     */
    public void validateSingleTurret(Claim claim, Turret t) {
        if (!citizensAvailable) return;
        if (t.isDestroyed()) return;       // structure-combat owns the respawn timer
        World world = Bukkit.getWorld(claim.getWorldId());
        if (world == null) return;
        boolean changed = dedupeNpcIds(claim);

        int cx = t.getX() >> 4, cz = t.getZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) world.loadChunk(cx, cz, true);

        destroyOrphansAtAnchor(world, t, t.getNpcId());

        int level = claim.getSlotLevel(t.getSlot());
        Location expected = new Location(world,
                t.getX() + 0.5, t.getY() + 4, t.getZ() + 0.5);
        NPC existing = t.getNpcId() >= 0
                ? CitizensAPI.getNPCRegistry().getById(t.getNpcId()) : null;
        if (disownForeignNpc(t, existing)) { existing = null; changed = true; }
        boolean alive = existing != null && existing.isSpawned()
                && existing.getEntity() instanceof Shulker
                && existing.getEntity().isValid()
                && !existing.getEntity().isDead();
        if (!alive) {
            // Only destroy a stale entry that is genuinely ours (a Shulker). Foreign NPCs were
            // already disowned above without destroying, so existing here is null or a turret NPC.
            if (existing != null) {
                try { existing.destroy(); } catch (Throwable ignored) {}
            }
            int newId = spawn(world, t, level);
            if (newId >= 0) {
                t.setNpcId(newId);
                changed = true;
                plugin.getLogger().info("chunk-load: spawned NPC #" + newId
                        + " at " + t + " (slot #" + (t.getSlot() + 1) + ")");
            }
        } else {
            existing.setName(nameFor(t, level));
            if (existing.getEntity().getLocation().distanceSquared(expected) > 4.0) {
                try { existing.getEntity().teleport(expected); } catch (Throwable ignored) {}
            }
        }
        if (changed) plugin.getClaimManager().save();
    }

    /**
     * Destroy any Citizens NPC named {@code Turret #...} sitting within ~2 blocks of this slot's
     * anchor whose id is not {@code keepId}. Catches two failure modes that id-only dedup misses:
     *
     * <ol>
     *   <li><b>Upgrade-time orphans.</b> An old {@code Turret #N (Level L)} NPC was left in the
     *       world when a new one with the updated nametag was spawned for the same slot → two
     *       overlapping nametags at the layer-5 centre.</li>
     *   <li><b>Cross-slot stragglers.</b> A former duplicate npcId was cleared on this slot,
     *       leaving the actual NPC entity inhabiting another slot's anchor. That slot's recorded
     *       NPC then drifts elsewhere (drift-correction loses to the orphan) or appears dead, so
     *       combat-tick can't find it to fire from — the "one specific turret won't shoot" bug.</li>
     * </ol>
     *
     * <p>Returns true if anything was destroyed (caller can log / set anyChange).
     */
    private boolean destroyOrphansAtAnchor(World world, Turret t, int keepId) {
        if (!citizensAvailable || world == null) return false;
        double ax = t.getX() + 0.5, ay = t.getY() + 4, az = t.getZ() + 0.5;
        java.util.UUID worldId = world.getUID();
        java.util.List<NPC> kill = new java.util.ArrayList<>();
        try {
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (npc.getId() == keepId) continue;
                String name = npc.getName();
                if (name == null || !name.startsWith("Turret #")) continue;
                // Never destroy a spawned non-Shulker that merely carries a "Turret #" name —
                // that's a raider whose name was corrupted by an old id-hijack. Only real turret
                // Shulkers (or despawned entries, entity unavailable) are eligible for the sweep.
                if (npc.isSpawned() && npc.getEntity() != null
                        && !(npc.getEntity() instanceof Shulker)) continue;
                Location loc = (npc.isSpawned() && npc.getEntity() != null)
                        ? npc.getEntity().getLocation() : npc.getStoredLocation();
                if (loc == null || loc.getWorld() == null) continue;
                if (!loc.getWorld().getUID().equals(worldId)) continue;
                double dx = loc.getX() - ax, dy = loc.getY() - ay, dz = loc.getZ() - az;
                if (dx * dx + dy * dy + dz * dz <= 4.0) kill.add(npc);
            }
            for (NPC npc : kill) {
                plugin.getLogger().info("Destroying orphan NPC #" + npc.getId()
                        + " (" + npc.getName() + ") at slot #" + (t.getSlot() + 1)
                        + " anchor (keeping #" + keepId + ")");
                try { npc.destroy(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ex) {
            plugin.getLogger().warning("destroyOrphansAtAnchor failed: " + ex.getMessage());
        }
        return !kill.isEmpty();
    }

    /**
     * Guard against Citizens id hijack. A turret slot's persisted {@code npcId} can end up
     * resolving to a NON-Shulker NPC — most commonly a raid raider (a PLAYER-type NPC) whose
     * Citizens id collided with the stored turret id after a registry reload / id reuse. The
     * liveness checks below would then treat that raider as a live turret: rename it to
     * {@code Turret #N (Level L)} and teleport it to the anchor. That is the reported
     * "a turret had the model of a raider" bug (and it simultaneously corrupts the raider).
     *
     * <p>When the resolved NPC is spawned with a live entity that is not a Shulker, we
     * <b>disown</b> it from this slot — clear the slot's id so a fresh Shulker spawns — but we
     * do <b>not</b> destroy it, because the entity belongs to its real owner (the raid engine).
     * Returns true if the slot was disowned (caller should treat {@code existing} as gone and
     * persist the cleared id).
     */
    private boolean disownForeignNpc(Turret t, NPC existing) {
        if (existing == null) return false;
        if (!existing.isSpawned() || existing.getEntity() == null) return false;
        if (existing.getEntity() instanceof Shulker) return false;
        plugin.getLogger().warning("Turret slot #" + (t.getSlot() + 1) + " npcId "
                + existing.getId() + " resolved to a non-Shulker NPC ("
                + existing.getEntity().getType() + ", \"" + existing.getName()
                + "\") — disowning (not destroying) and respawning a fresh turret.");
        t.setNpcId(-1);
        return true;
    }

    /**
     * Detect and break duplicate {@code npcId} references within a claim. If two turret slots
     * share an id, only the first one keeps it; the rest are cleared (-1) so the next
     * validation pass spawns fresh NPCs for them. Returns true if anything changed.
     *
     * <p>Why this matters: Citizens NPC IDs are unique by construction (each {@code createNPC}
     * gives a new id), so duplicates only arise via persistence-time corruption. The
     * symptom is one slot perpetually appearing empty — drift-correction ping-pongs the single
     * shared NPC between the two slots' anchor positions every validation tick.
     */
    private boolean dedupeNpcIds(Claim claim) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        boolean changed = false;
        for (Turret t : claim.getTurrets()) {
            int id = t.getNpcId();
            if (id < 0) continue;
            if (!seen.add(id)) {
                plugin.getLogger().warning("Duplicate npcId " + id
                        + " on slot #" + (t.getSlot() + 1) + " of claim "
                        + claim.getOwner() + " — destroying shared NPC + clearing for re-spawn");
                // Destroy the shared NPC entirely. Both slots will respawn a fresh NPC on the
                // next validation pass. (If we only cleared this slot's reference, the other
                // slot would keep the entity at the WRONG position and drift-correct forever.)
                try {
                    NPC shared = CitizensAPI.getNPCRegistry().getById(id);
                    if (shared != null) shared.destroy();
                } catch (Throwable ignored) {}
                t.setNpcId(-1);
                // Also clear the prior-seen slot's reference so it respawns too.
                for (Turret other : claim.getTurrets()) {
                    if (other != t && other.getNpcId() == id) other.setNpcId(-1);
                }
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Force-validate every turret in every claim, ignoring the chunk-loaded gate. Used by
     * {@code /HomeSystem dev fixall} to repair turrets in unloaded chunks (which the periodic
     * tick skips). Returns the number of NPCs created / re-spawned.
     */
    public int forceValidateAll() {
        if (!citizensAvailable) return 0;
        int touched = 0;
        boolean anyChange = false;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World world = Bukkit.getWorld(claim.getWorldId());
            if (world == null) continue;
            if (dedupeNpcIds(claim)) anyChange = true;
            for (Turret t : claim.getTurrets()) {
                // Make sure the chunk is loaded so spawn() can succeed.
                if (t.isDestroyed()) continue;     // managed by WitherCombatManager
                int cx = t.getX() >> 4, cz = t.getZ() >> 4;
                if (!world.isChunkLoaded(cx, cz)) world.loadChunk(cx, cz, true);

                if (destroyOrphansAtAnchor(world, t, t.getNpcId())) anyChange = true;

                int level = claim.getSlotLevel(t.getSlot());
                Location expected = new Location(world,
                        t.getX() + 0.5, t.getY() + 4, t.getZ() + 0.5);
                NPC existing = t.getNpcId() >= 0
                        ? CitizensAPI.getNPCRegistry().getById(t.getNpcId()) : null;
                if (disownForeignNpc(t, existing)) { existing = null; anyChange = true; }
                boolean alive = existing != null && existing.isSpawned()
                        && existing.getEntity() instanceof Shulker
                        && existing.getEntity().isValid()
                        && !existing.getEntity().isDead();
                if (alive) {
                    existing.setName(nameFor(t, level));
                    if (existing.getEntity().getLocation().distanceSquared(expected) > 4.0) {
                        try { existing.getEntity().teleport(expected); } catch (Throwable ignored) {}
                    }
                    continue;
                }
                if (existing != null) {
                    try { existing.destroy(); } catch (Throwable ignored) {}
                }
                int newId = spawn(world, t, level);
                if (newId >= 0) {
                    t.setNpcId(newId);
                    anyChange = true;
                    touched++;
                    plugin.getLogger().info("fixall: created NPC #" + newId + " at " + t);
                }
            }
        }
        if (anyChange) plugin.getClaimManager().save();
        return touched;
    }

    /** Destroy the NPC registry entry for this turret, if one exists. */
    public void despawn(Turret turret) {
        // Removal hook — drop the phantom alongside the NPC. Must run even when Citizens is
        // absent so phantom armor stands aren't orphaned by a Citizens reload mid-session.
        if (plugin.getWitherCombatManager() != null) {
            plugin.getWitherCombatManager().onTurretRemoved(turret);
        }
        if (!citizensAvailable || turret.getNpcId() < 0) return;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(turret.getNpcId());
            if (npc != null) npc.destroy();
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to despawn turret NPC #" + turret.getNpcId()
                    + ": " + t.getMessage());
        }
        turret.setNpcId(-1);
    }

    /**
     * Periodic sweep. Handles three cases per turret:
     * <ul>
     *   <li><b>Has NPC, NPC exists</b> — no-op.</li>
     *   <li><b>Has NPC, registry lost it</b> — respawn, update id, save.</li>
     *   <li><b>No NPC (npcId = -1)</b> — check the layer-5 centre block. If it's a stale
     *       {@code PURPLE_SHULKER_BOX} from before the entity feature, replace it with air,
     *       spawn the NPC, and persist. Otherwise leave the row alone (it's either an orphan
     *       with no structure on the ground, or still pending placement via the queue, which
     *       will spawn the NPC itself when it places the structure).</li>
     * </ul>
     *
     * <p>Unloaded chunks are skipped — they'll be revisited on a future tick once a player
     * loads the area. We never force-load a chunk just to migrate.
     */
    private void tick() {
        if (!citizensAvailable) return;
        // SELF-HEAL: every validation pass, sweep Citizens for any "Turret #..." NPC that no
        // slot currently references and destroy it. This is the safety net for the edge case
        // the user flagged: turret killed by wither during downtime → /HomeSystem unclaim runs
        // while npcId is still -1, the destroyed turret's old Citizens entry has already been
        // removed, but other (alive) turret NPCs from the same claim should have been despawned
        // too. Any rare drift (Citizens reload hiccup, partial crash mid-cleanup, legacy data
        // from before the data-folder rename) is caught here within 5 s and removed cleanly.
        // Quiet by design — only log if we actually wiped >5 in one pass (that's anomalous, would
        // indicate a real corruption event worth surfacing). Routine 0-1 wipes are silent.
        int orphans = wipeOrphanNPCs();
        if (orphans > 5) plugin.getLogger().info("Periodic sweep: wiped " + orphans + " orphan turret NPC(s).");
        boolean anyChange = false;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World world = Bukkit.getWorld(claim.getWorldId());
            if (world == null) continue;
            // Dedupe npcIds within this claim BEFORE checking any turret. If two slots reference
            // the same Citizens NPC (a corruption that originated in an earlier session), the
            // drift-correction in the alive path will ping-pong the NPC back and forth between
            // the two slots forever — one slot always looks empty. Clear the duplicate so it
            // gets a fresh NPC in the loop below.
            if (dedupeNpcIds(claim)) anyChange = true;
            for (Turret t : claim.getTurrets()) {
                if (!world.isChunkLoaded(t.getX() >> 4, t.getZ() >> 4)) continue;
                // Skip turrets currently in their post-destruction downtime — the structure-combat
                // manager owns the respawn timing for these, and re-spawning the NPC here would
                // shortcut the 2-minute window.
                if (t.isDestroyed()) continue;

                // Purge any unrelated turret-NPC squatting at this slot's anchor (upgrade
                // orphans / cross-slot stragglers). Must run BEFORE the alive check so the
                // alive/drift logic doesn't confuse a duplicate for the real one.
                destroyOrphansAtAnchor(world, t, t.getNpcId());

                int level = claim.getSlotLevel(t.getSlot());

                if (t.getNpcId() < 0) {
                    // Migration/self-heal path. Active turrets must always own a Citizens
                    // shulker; modern structures have AIR here, not PURPLE_SHULKER_BOX.
                    Block center = world.getBlockAt(t.getX(), t.getY() + 4, t.getZ());
                    if (center.getType() == Material.PURPLE_SHULKER_BOX) center.setType(Material.AIR, false);
                    int newId = spawn(world, t, level);
                    if (newId >= 0) {
                        t.setNpcId(newId);
                        anyChange = true;
                        plugin.getLogger().info("Created missing turret NPC #" + newId
                                + " → Citizens NPC #" + newId);
                    }
                    continue;
                }

                // Strict liveness check. Citizens' isSpawned() can lie — it returns true even
                // when the underlying entity has been killed/removed but the registry entry
                // hasn't been cleaned up. So we also verify the entity itself is valid + not
                // dead. Plus: confirm the entity is at the expected position; some Citizens
                // edge cases (chunk unload during write, etc.) can leave entities adrift.
                NPC existing = CitizensAPI.getNPCRegistry().getById(t.getNpcId());
                if (disownForeignNpc(t, existing)) { existing = null; anyChange = true; }
                Location expected = new Location(world,
                        t.getX() + 0.5, t.getY() + 4, t.getZ() + 0.5);
                boolean alive = existing != null
                        && existing.isSpawned()
                        && existing.getEntity() instanceof Shulker
                        && existing.getEntity().isValid()
                        && !existing.getEntity().isDead();

                if (alive) {
                    // Sync nametag (in case the level was upgraded) and snap back to position
                    // if the entity has drifted more than 2 blocks from its slot.
                    existing.setName(nameFor(t, level));
                    if (existing.getEntity().getLocation().distanceSquared(expected) > 4.0) {
                        try { existing.getEntity().teleport(expected); } catch (Throwable ignored) {}
                    }
                    continue;
                }

                // Dead or missing — try to revive the existing registry entry first
                // (preserves the npcId so saved data stays valid).
                if (existing != null) {
                    try {
                        if (existing.isSpawned()) existing.despawn();
                        boolean spawned = existing.spawn(expected);
                        if (!spawned || existing.getEntity() == null
                                || !existing.getEntity().isValid()
                                || existing.getEntity().isDead()) {
                            throw new IllegalStateException("Citizens returned no live entity");
                        }
                        existing.setName(nameFor(t, level));
                        if (plugin.getWitherCombatManager() != null) {
                            plugin.getWitherCombatManager().onTurretActivated(world, t);
                        }
                        plugin.getLogger().info("Re-spawned dormant turret NPC #" + t.getNpcId()
                                + " at " + t);
                        continue;
                    } catch (Throwable ex) {
                        plugin.getLogger().warning("Failed to re-spawn NPC #" + t.getNpcId()
                                + ", destroying + recreating: " + ex.getMessage());
                        try { existing.destroy(); } catch (Throwable ignored) {}
                    }
                }
                // Last resort: brand-new NPC.
                int newId = spawn(world, t, level);
                if (newId >= 0) {
                    t.setNpcId(newId);
                    anyChange = true;
                    plugin.getLogger().info("Created fresh turret NPC #" + newId
                            + " at " + t + " (previous entry was missing or dead)");
                }
            }
        }
        if (anyChange) plugin.getClaimManager().save();
    }
}
