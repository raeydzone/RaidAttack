package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import com.raeyd.raidattack.turret.Turret;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the in-memory claim state and persists it to disk.
 *
 * <p>State is persisted to the {@code world} schema: claims (+ slots/turrets/friends), the
 * saved-slot-levels (unclaim→reclaim memory), the UUID→name cache, and the "held in adventure"
 * saved gamemode. The bulk claim state is mirrored on a short debounce off the main thread (turret
 * HP changes call {@link #save()} during combat, so it must never block the tick); the hot
 * name/gamemode paths write per-change.
 */
public final class ClaimManager {

    /** How often (ticks) the debounced flush mirrors dirty claim state to the DB. */
    private static final long FLUSH_INTERVAL_TICKS = 100L; // 5s

    private final Plugin plugin;
    private final WorldDatabase worldDb;
    private volatile boolean dirty = false;
    private BukkitTask flushTask;

    /** owner UUID → claim. One claim per player. */
    private final Map<UUID, Claim> claimsByOwner = new HashMap<>();

    /** player UUID → gamemode they had before a foreign zone forced them to Adventure. */
    private final Map<UUID, GameMode> savedGamemodes = new HashMap<>();

    /**
     * Per-owner snapshot of turret slot levels at the moment their claim was removed. Restored
     * the next time the same owner creates a fresh claim, so {@code /HomeSystem unclaim} followed
     * by re-claiming preserves the player's upgrade investment (diamonds + netherite). Cleared
     * once consumed by {@link #addClaim(Claim)}.
     */
    private final Map<UUID, int[]> savedSlotLevels = new HashMap<>();

    /**
     * Last-known player name per UUID. Populated on join and whenever a name resolves through
     * commands (e.g. {@code add}). Survives the player going offline / being purged from the
     * server's user cache, so {@code current info} can still show "owned by Steve" on a zone
     * whose owner hasn't logged in this boot.
     */
    private final Map<UUID, String> lastKnownNames = new HashMap<>();

    public ClaimManager(Plugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    public void load() {
        claimsByOwner.clear();
        savedGamemodes.clear();
        lastKnownNames.clear();
        savedSlotLevels.clear();
        // Seed the dev test-actor with a readable label so resolveName(DEV_UUID) returns "dev".
        lastKnownNames.put(HomeSystemPlugin.DEV_UUID, "dev");

        for (WorldDatabase.ClaimRow row : worldDb.loadClaims()) {
            java.util.Set<UUID> friends = new java.util.HashSet<>(row.friends());
            List<Turret> turrets = new ArrayList<>();
            for (WorldDatabase.TurretRow tr : row.turrets()) {
                Turret t = new Turret(tr.slot(), tr.x(), tr.y(), tr.z());
                t.setNpcId(tr.npcId());
                t.setStructureHp(tr.structureHp());
                t.setRespawnAtMillis(tr.respawnAtMillis());
                turrets.add(t);
            }
            Claim claim = new Claim(row.owner(), row.world(), row.minX(), row.minZ(), row.maxX(), row.maxZ(),
                    friends, row.levels(), turrets);
            claim.setAttacksHostileMobs(row.attackMobs());
            for (int i = 0; i < Claim.MAX_TURRETS; i++) claim.setSlotRespawnAt(i, row.slotRespawn()[i]);
            claimsByOwner.put(row.owner(), claim);
        }
        savedSlotLevels.putAll(worldDb.loadSavedSlotLevels());

        // Saved gamemodes — prune NPC/non-player leftovers (Citizens UUIDs used to leak in).
        for (Map.Entry<UUID, String> e : worldDb.loadPlayerGamemodes().entrySet()) {
            UUID id = e.getKey();
            if (!Bukkit.getOfflinePlayer(id).hasPlayedBefore()) {
                worldDb.deleteGamemode(id);   // drop the stale row
                continue;
            }
            try { savedGamemodes.put(id, GameMode.valueOf(e.getValue())); } catch (IllegalArgumentException ignored) {}
        }

        for (Map.Entry<UUID, String> e : worldDb.loadPlayerNames().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) lastKnownNames.put(e.getKey(), e.getValue());
        }

        plugin.getLogger().info("Loaded " + claimsByOwner.size() + " claim(s), "
                + savedGamemodes.size() + " saved gamemode(s), "
                + lastKnownNames.size() + " known name(s).");
    }

    /** Begin the debounced flush loop. Call once after {@link #load()}. */
    public void start() {
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!dirty) return;
            dirty = false;
            List<WorldDatabase.ClaimRow> snap = snapshotClaims();
            Map<UUID, int[]> levels = snapshotSavedLevels();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> worldDb.saveClaims(snap, levels));
        }, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    /** Stop the loop and flush synchronously (server shutdown), so no claim state is lost. */
    public void stop() {
        if (flushTask != null) { flushTask.cancel(); flushTask = null; }
        worldDb.saveClaims(snapshotClaims(), snapshotSavedLevels());
        dirty = false;
    }

    /** Mark claim state dirty; the debounced flush mirrors it to the DB. */
    public void save() {
        dirty = true;
    }

    private List<WorldDatabase.ClaimRow> snapshotClaims() {
        List<WorldDatabase.ClaimRow> out = new ArrayList<>();
        for (Claim c : claimsByOwner.values()) {
            long[] respawn = new long[Claim.MAX_TURRETS];
            for (int i = 0; i < Claim.MAX_TURRETS; i++) respawn[i] = c.getSlotRespawnAt(i);
            List<WorldDatabase.TurretRow> turrets = new ArrayList<>();
            for (Turret t : c.getTurrets()) {
                turrets.add(new WorldDatabase.TurretRow(t.getSlot(), t.getX(), t.getY(), t.getZ(),
                        t.getNpcId(), t.getStructureHp(), t.getRespawnAtMillis()));
            }
            out.add(new WorldDatabase.ClaimRow(c.getOwner(), c.getWorldId(), c.getMinX(), c.getMinZ(),
                    c.getMaxX(), c.getMaxZ(), c.attacksHostileMobs(), c.getSlotLevels(), respawn,
                    turrets, new ArrayList<>(c.getFriends())));
        }
        return out;
    }

    private Map<UUID, int[]> snapshotSavedLevels() {
        Map<UUID, int[]> m = new HashMap<>();
        for (Map.Entry<UUID, int[]> e : savedSlotLevels.entrySet()) m.put(e.getKey(), e.getValue().clone());
        return m;
    }

    // ---- claims ----------------------------------------------------------

    public Claim getClaimOf(UUID owner) {
        return claimsByOwner.get(owner);
    }

    /**
     * Return the claim whose protection applies at this location. Includes the implicit
     * <b>nether mirror</b> of every overworld claim: a player standing in the Nether at
     * {@code (x, z)} is treated as being in the overworld claim that covers {@code (x*8, z*8)}.
     *
     * <p>Rationale: without this, an outsider could go to the Nether at the matching scaled
     * coords, build a portal, and either bombard the claim from above (cheaped via portal travel)
     * or land a return portal directly inside it. Mirroring the claim into the Nether means the
     * Nether mirror zone is just as Adventure-locked / build-locked as the overworld original,
     * so the staging area for that attack doesn't exist.
     *
     * <p>The End is intentionally NOT mirrored — there's no End↔Overworld coordinate scaling and
     * portal travel back from End always lands at the obsidian platform at spawn.
     */
    public Claim getClaimAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        // Direct hit in the literal world the location is in.
        for (Claim c : claimsByOwner.values()) {
            if (c.contains(loc)) return c;
        }
        // Nether-mirror lookup: project the nether coords to overworld scale (×8) and re-check
        // against overworld claims linked to this nether world.
        World w = loc.getWorld();
        if (w.getEnvironment() == World.Environment.NETHER) {
            World overworld = findLinkedOverworld(w);
            if (overworld != null) {
                UUID owId = overworld.getUID();
                int ox = loc.getBlockX() * 8;
                int oz = loc.getBlockZ() * 8;
                for (Claim c : claimsByOwner.values()) {
                    if (!c.getWorldId().equals(owId)) continue;
                    if (ox >= c.getMinX() && ox <= c.getMaxX()
                            && oz >= c.getMinZ() && oz <= c.getMaxZ()) return c;
                }
            }
        }
        return null;
    }

    /**
     * Heuristically find the linked overworld for a given Nether world. Bukkit does not expose a
     * direct API for the portal-linkage relationship, so we go by name convention first
     * ({@code <name>_nether} → {@code <name>}) and fall back to "the first NORMAL world loaded".
     * For the default single-world Paper setup this returns {@code world}.
     */
    private static World findLinkedOverworld(World nether) {
        String name = nether.getName();
        if (name.endsWith("_nether")) {
            World w = Bukkit.getWorld(name.substring(0, name.length() - "_nether".length()));
            if (w != null && w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return null;
    }

    /** Returns the first existing claim that overlaps this box, or null. */
    public Claim findOverlap(UUID worldId, int minX, int minZ, int maxX, int maxZ) {
        for (Claim c : claimsByOwner.values()) {
            if (c.overlaps(worldId, minX, minZ, maxX, maxZ)) return c;
        }
        return null;
    }

    public void addClaim(Claim claim) {
        // If this owner had a previous claim that was unclaimed, restore their per-slot turret
        // levels from the saved snapshot. The snapshot is single-use — consumed here.
        int[] saved = savedSlotLevels.remove(claim.getOwner());
        if (saved != null) claim.setSlotLevels(saved);
        claimsByOwner.put(claim.getOwner(), claim);
        save();
    }

    public boolean removeClaim(UUID owner) {
        Claim removed = claimsByOwner.remove(owner);
        if (removed != null) {
            // Stash the slot-level array so a future re-claim by the same owner inherits the
            // upgrades. Without this, /unclaim wipes the player's diamond/netherite investment.
            savedSlotLevels.put(owner, removed.getSlotLevels());
            save();
            return true;
        }
        return false;
    }

    /**
     * Remove every claim whose owner UUID has no resolvable offline-player name. These
     * are claims created by {@code /HomeSystem dev claim} for testing — real player UUIDs
     * always resolve once that player has joined the server.
     *
     * <p>TODO(dev): drop together with the dev command before production.
     */
    public int removeUnnamedClaims() {
        int removed = 0;
        var it = claimsByOwner.entrySet().iterator();
        while (it.hasNext()) {
            UUID owner = it.next().getKey();
            if (Bukkit.getOfflinePlayer(owner).getName() == null) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) save();
        return removed;
    }

    public Map<UUID, Claim> all() {
        return claimsByOwner;
    }

    // ---- saved gamemodes (used by ZoneListener) --------------------------

    public GameMode getSavedGamemode(UUID id) {
        return savedGamemodes.get(id);
    }

    public void setSavedGamemode(UUID id, GameMode mode) {
        savedGamemodes.put(id, mode);
        final String m = mode.name();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> worldDb.upsertGamemode(id, m));
    }

    public GameMode clearSavedGamemode(UUID id) {
        GameMode removed = savedGamemodes.remove(id);
        if (removed != null) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> worldDb.deleteGamemode(id));
        return removed;
    }

    public String resolveName(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        if (name != null) return name;
        String cached = lastKnownNames.get(id);
        if (cached != null) return cached;
        return id.toString().substring(0, 8);
    }

    /**
     * Reverse of {@link #resolveName} — find a UUID for the given case-insensitive name. Checks
     * the live cache first (populated by every player join), then falls back to Bukkit's
     * offline-player lookup. Returns null on no match.
     */
    public UUID resolveUUID(String name) {
        if (name == null || name.isEmpty()) return null;
        for (var e : lastKnownNames.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue())) return e.getKey();
        }
        return null;
    }

    /**
     * Record a UUID → name mapping. Idempotent and only persists when something actually
     * changed; safe to call on every join.
     */
    public void noteName(UUID id, String name) {
        if (id == null || name == null || name.isEmpty()) return;
        String prev = lastKnownNames.put(id, name);
        if (!name.equals(prev)) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> worldDb.upsertName(id, name));
    }
}
