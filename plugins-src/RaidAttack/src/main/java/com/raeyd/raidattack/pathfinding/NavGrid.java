package com.raeyd.raidattack.pathfinding;

import com.raeyd.raidattack.claim.Claim;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Thread-safe block-passability views for off-thread pathfinding, plus a per-claim snapshot cache.
 *
 * <p>{@link BlockSource} is the read interface the planners use. {@link LiveBlockSource} reads the
 * live world (MAIN THREAD ONLY — full {@code Block#isPassable()} fidelity; used for the synchronous
 * first path). {@link SnapshotBlockSource} reads immutable {@link ChunkSnapshot}s captured on the
 * main thread, so it is safe to query from a worker thread (used for async replans). The snapshot
 * view derives passability from the block {@link Material} ({@code !isSolid()}) — a ~99% match for
 * ground pathing that treats slabs/stairs as walls. That's acceptable for raiders, and the
 * synchronous first path keeps full fidelity, so the common case is unaffected.
 */
public final class NavGrid {

    /**
     * Cells a raid mob must NEVER path through even though they don't physically block movement —
     * deadly fluids/blocks. Without this a raider treats a lava moat as "passable" (lava isn't solid)
     * and walks straight through it to reach a turret. Pathing must route around these instead.
     */
    public static boolean isPathHazard(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE;
    }

    /** Block queries a path planner needs. Coordinates are absolute block coords. */
    public interface BlockSource {
        World world();
        boolean loaded(int x, int z);
        boolean solid(int x, int y, int z);
        boolean passable(int x, int y, int z);
        /** Is this cell a path hazard (lava / fire)? Used to keep a 1-block buffer around lava. */
        boolean hazard(int x, int y, int z);
        int minHeight();
        int maxHeight();
    }

    /** Live, accurate, MAIN-THREAD-ONLY view. */
    public static final class LiveBlockSource implements BlockSource {
        private final World world;
        public LiveBlockSource(World world) { this.world = world; }
        @Override public World world() { return world; }
        @Override public boolean loaded(int x, int z) { return world.isChunkLoaded(x >> 4, z >> 4); }
        @Override public boolean solid(int x, int y, int z) { return world.getBlockAt(x, y, z).getType().isSolid(); }
        @Override public boolean passable(int x, int y, int z) {
            org.bukkit.block.Block b = world.getBlockAt(x, y, z);
            return b.isPassable() && !isPathHazard(b.getType());
        }
        @Override public boolean hazard(int x, int y, int z) {
            return isPathHazard(world.getBlockAt(x, y, z).getType());
        }
        @Override public int minHeight() { return world.getMinHeight(); }
        @Override public int maxHeight() { return world.getMaxHeight(); }
    }

    /** Immutable chunk-snapshot view. SAFE to read from any thread once built. */
    public static final class SnapshotBlockSource implements BlockSource {
        private final World world;
        private final int minY, maxY;
        private final Map<Long, ChunkSnapshot> chunks;   // populated on build, never mutated after

        SnapshotBlockSource(World world, int minY, int maxY, Map<Long, ChunkSnapshot> chunks) {
            this.world = world; this.minY = minY; this.maxY = maxY; this.chunks = chunks;
        }
        private ChunkSnapshot chunk(int x, int z) { return chunks.get(key(x >> 4, z >> 4)); }
        @Override public World world() { return world; }
        @Override public boolean loaded(int x, int z) { return chunk(x, z) != null; }
        @Override public boolean solid(int x, int y, int z) {
            if (y < minY || y >= maxY) return false;
            ChunkSnapshot c = chunk(x, z);
            return c != null && c.getBlockData(x & 15, y, z & 15).getMaterial().isSolid();
        }
        @Override public boolean passable(int x, int y, int z) {
            if (y < minY || y >= maxY) return true;
            ChunkSnapshot c = chunk(x, z);
            if (c == null) return false;                 // unknown cell → wall (matches "unloaded" in live)
            Material m = c.getBlockData(x & 15, y, z & 15).getMaterial();
            return !m.isSolid() && !isPathHazard(m);
        }
        @Override public boolean hazard(int x, int y, int z) {
            if (y < minY || y >= maxY) return false;
            ChunkSnapshot c = chunk(x, z);
            return c != null && isPathHazard(c.getBlockData(x & 15, y, z & 15).getMaterial());
        }
        @Override public int minHeight() { return minY; }
        @Override public int maxHeight() { return maxY; }
    }

    private static long key(int cx, int cz) { return (((long) cx) << 32) ^ (cz & 0xffffffffL); }

    // -- per-claim snapshot cache (BUILT on main thread, READ off-thread) -----

    private record Cached(long builtTick, SnapshotBlockSource src) {}
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    /**
     * Get (or rebuild on TTL) the snapshot view covering {@code zone}'s loaded chunks. MUST be called
     * on the main thread (it reads live chunks); the returned source is then safe to query off-thread.
     */
    public SnapshotBlockSource forClaim(World world, Claim zone, long currentTick, long ttlTicks) {
        UUID owner = zone.getOwner();
        Cached c = cache.get(owner);
        if (c != null && currentTick - c.builtTick() < ttlTicks && world.equals(c.src().world())) {
            return c.src();
        }
        SnapshotBlockSource src = build(world, zone);
        cache.put(owner, new Cached(currentTick, src));
        return src;
    }

    public void clear(UUID owner) { cache.remove(owner); }
    public void clearAll() { cache.clear(); }

    private static SnapshotBlockSource build(World world, Claim zone) {
        int minCX = zone.getMinX() >> 4, maxCX = zone.getMaxX() >> 4;
        int minCZ = zone.getMinZ() >> 4, maxCZ = zone.getMaxZ() >> 4;
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;     // never force a sync chunk load here
                snaps.put(key(cx, cz), world.getChunkAt(cx, cz).getChunkSnapshot(false, false, false));
            }
        }
        return new SnapshotBlockSource(world, world.getMinHeight(), world.getMaxHeight(), snaps);
    }
}
