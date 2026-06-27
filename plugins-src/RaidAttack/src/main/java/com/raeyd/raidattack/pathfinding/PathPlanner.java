package com.raeyd.raidattack.pathfinding;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

/**
 * Static 3D A* path planner for turret projectiles. Block-resolution nodes, 26-neighbour
 * (cardinals + face-diagonals + corner-diagonals) so paths can curve naturally in three
 * dimensions instead of stair-stepping.
 *
 * <p>"Passable" = the cell is not a solid block OR it's inside any deployed turret zone (the
 * bullet phases through those, see {@link HomeSystemPlugin#isInTurretZone}).
 *
 * <p>Capped at {@link #MAX_NODES} explored to keep the worst-case CPU cost predictable. A search
 * that exceeds the budget returns {@code null}; the caller can fall back to reactive flight.
 *
 * <p>Output is a list of block-centre {@link Vector}s, smoothed by removing intermediate
 * waypoints that have unobstructed line of sight to the next-but-one — projectile follows the
 * minimum set of corners needed to clear obstacles.
 */
public final class PathPlanner {

    /**
     * Max A* nodes explored per call. 2000 is plenty for any path inside the 35-block engagement
     * range now that the cheap raycast pre-filter culls buried targets upstream — A* is only ever
     * fed candidates with at most a handful of opaque blocks on the LOS, so the detour budget is
     * small. (35-block straight ≈ 35 nodes, 3× detour around a tree/house ≈ 105 nodes — 2000 is
     * still a ~20× safety margin and the per-call worst case is ~0.5 ms.)
     */
    private static final int MAX_NODES = 4000;
    /**
     * Bullet won't be routed through nodes farther than this from the start (blocks). Slightly
     * wider than the 35-block engagement range so a path that has to curve out before curving
     * back in still has room to breathe.
     */
    private static final double MAX_SEARCH_RADIUS = 45.0;

    private PathPlanner() {}

    /**
     * Plan a path from {@code from} to {@code to}. Returns the waypoint list (start excluded,
     * goal included) or {@code null} if no path was found within the node budget.
     */
    public static List<Vector> findPath(HomeSystemPlugin plugin, Location from, Location to) {
        World world = from.getWorld();
        if (world == null || to.getWorld() != world) return null;

        BlockPos start = new BlockPos(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        BlockPos goal = new BlockPos(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        if (start.equals(goal)) return List.of(centerOf(goal));

        // gScore + parents are local to this call so the planner is safe to invoke repeatedly.
        Map<BlockPos, Double> gScore = new HashMap<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        gScore.put(start, 0.0);

        PriorityQueue<BlockPos> open = new PriorityQueue<>(Comparator.comparingDouble(
                p -> gScore.getOrDefault(p, Double.MAX_VALUE) + heuristic(p, goal)));
        open.add(start);

        int explored = 0;
        while (!open.isEmpty() && explored < MAX_NODES) {
            BlockPos current = open.poll();
            if (current.equals(goal)) {
                return smoothPath(plugin, world, reconstruct(cameFrom, current));
            }
            if (!closed.add(current)) continue;
            explored++;

            double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);

            for (BlockPos delta : NEIGHBOR_OFFSETS) {
                BlockPos neighbor = current.add(delta);
                if (closed.contains(neighbor)) continue;
                if (heuristic(neighbor, start) > MAX_SEARCH_RADIUS) continue;
                if (!passable(plugin, world, neighbor)) continue;

                // Corner-cut prevention. For diagonal / multi-axis moves, every intermediate
                // single-axis cell must also be passable — otherwise the projectile would have
                // to physically pass through a solid block corner, which our smoothing's
                // line-of-sight check might rubber-stamp but the actual bullet can't navigate.
                int absDx = Math.abs(delta.x), absDy = Math.abs(delta.y), absDz = Math.abs(delta.z);
                if (absDx + absDy + absDz > 1) {
                    if (delta.x != 0 && !passable(plugin, world,
                            current.add(new BlockPos(delta.x, 0, 0)))) continue;
                    if (delta.y != 0 && !passable(plugin, world,
                            current.add(new BlockPos(0, delta.y, 0)))) continue;
                    if (delta.z != 0 && !passable(plugin, world,
                            current.add(new BlockPos(0, 0, delta.z)))) continue;
                }

                double stepCost = delta.length();
                double tentativeG = currentG + stepCost;
                if (tentativeG >= gScore.getOrDefault(neighbor, Double.MAX_VALUE)) continue;

                cameFrom.put(neighbor, current);
                gScore.put(neighbor, tentativeG);
                open.add(neighbor);
            }
        }
        return null;
    }

    /**
     * Voxel-traversal line-of-sight. Steps at 0.1 along the segment a→b, deduplicating
     * block-coordinate hits via a hash so each unique block is only checked once. This catches
     * the "diagonal line crosses a solid corner block" case that the previous 0.5-block
     * sampler would skip — when the smoothing reports "clear LoS", the bullet's straight-line
     * flight actually has zero solids in the way.
     */
    private static boolean lineOfSight(HomeSystemPlugin plugin, World world, Vector a, Vector b) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.001) return true;
        int steps = (int) Math.ceil(dist / 0.1);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = a.getX() + dx * t;
            double py = a.getY() + dy * t;
            double pz = a.getZ() + dz * t;
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            long key = (((long) bx) << 40) ^ (((long) by) << 20) ^ ((long) bz);
            if (!seen.add(key)) continue;
            // Don't force-generate an unloaded chunk just to test line-of-sight: treat it as
            // obstructed so smoothing keeps the waypoint rather than stalling the tick.
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            Location loc = new Location(world, bx, by, bz);
            if (plugin.isInTurretZone(loc)) continue;
            if (!loc.getBlock().isPassable()) return false;
        }
        return true;
    }

    /**
     * Remove intermediate waypoints that the bullet can skip over via direct line-of-sight.
     * Greedy: from each waypoint, look as far ahead as still has clear LoS; drop everything
     * in between.
     */
    private static List<Vector> smoothPath(HomeSystemPlugin plugin, World world, List<BlockPos> raw) {
        List<Vector> waypoints = new ArrayList<>(raw.size());
        for (BlockPos p : raw) waypoints.add(centerOf(p));
        if (waypoints.size() <= 2) return waypoints;

        List<Vector> smoothed = new ArrayList<>();
        smoothed.add(waypoints.get(0));
        int i = 0;
        while (i < waypoints.size() - 1) {
            int furthest = i + 1;
            for (int j = waypoints.size() - 1; j > i + 1; j--) {
                if (lineOfSight(plugin, world, waypoints.get(i), waypoints.get(j))) {
                    furthest = j;
                    break;
                }
            }
            smoothed.add(waypoints.get(furthest));
            i = furthest;
        }
        return smoothed;
    }

    private static boolean passable(HomeSystemPlugin plugin, World world, BlockPos p) {
        // Never probe an unloaded chunk: reading its block would force a synchronous chunk
        // load/generation on the main thread (the TPS-spike root cause). Treat unloaded cells
        // as impassable so A* keeps the route inside already-loaded terrain rather than paying
        // terrain-generation cost mid-search.
        if (!world.isChunkLoaded(p.x >> 4, p.z >> 4)) return false;
        Location loc = new Location(world, p.x, p.y, p.z);
        if (plugin.isInTurretZone(loc)) return true;
        // Block#isPassable() is the canonical "can a body move through this cell" test and is
        // block-state-accurate (handles slabs/stairs/open doors/waterlogging correctly), unlike
        // the enum-level Material#isSolid() which can misreport. Air/water/foliage = passable;
        // stone/dirt/full blocks = wall. This is THE definition of a "road" for A* — if a cell is
        // not passable, A* can never route through it.
        return loc.getBlock().isPassable();
    }

    private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos goal) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos cur = goal;
        while (cur != null) {
            path.add(0, cur);
            cur = cameFrom.get(cur);
        }
        return path;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        int dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * (double) dx + dy * (double) dy + dz * (double) dz);
    }

    private static Vector centerOf(BlockPos p) {
        return new Vector(p.x + 0.5, p.y + 0.5, p.z + 0.5);
    }

    private record BlockPos(int x, int y, int z) {
        BlockPos add(BlockPos other) { return new BlockPos(x + other.x, y + other.y, z + other.z); }
        double length() { return Math.sqrt(x * (double) x + y * (double) y + z * (double) z); }
    }

    private static final List<BlockPos> NEIGHBOR_OFFSETS = buildNeighbors();
    private static List<BlockPos> buildNeighbors() {
        List<BlockPos> out = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    out.add(new BlockPos(dx, dy, dz));
                }
            }
        }
        return out;
    }
}
