package com.raeyd.raidattack.pathfinding;

import com.raeyd.raidattack.claim.Claim;
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

/**
 * Block-grid ground path planner (A*) for raid mobs.
 *
 * <p>Reads the world through a {@link NavGrid.BlockSource} so the SAME search runs either
 * synchronously on the live world (the first path, where we can't wait) or on a worker thread
 * against an immutable {@link NavGrid.SnapshotBlockSource} (replans — see {@link RaidSpawnEngine}).
 * Citizens' location navigator is unreliable for the player-NPC raiders and teleport-stepping looks
 * awful, so this returns ground waypoints that {@code RaidSpawnEngine} follows with entity velocity.
 */
public final class GroundPathPlanner {

    /** Default node budget (used for the synchronous first path, where main-thread cost matters). */
    public static final int DEFAULT_MAX_NODES = 8_000;
    /** Off-thread replans can afford a much wider search since they don't block the tick. */
    private static final int MAX_SEARCH_RADIUS = 160;
    private static final int MAX_STEP_DOWN = 10;

    private GroundPathPlanner() {}

    public static List<Location> findPath(NavGrid.BlockSource src, Location from, Location to,
                                          Claim zone, int clearance) {
        return findPath(src, from, to, zone, clearance, DEFAULT_MAX_NODES);
    }

    /**
     * Plan a path of standable feet locations from {@code from} to {@code to}, exploring at most
     * {@code maxNodes}. Returns block-center feet locations, or {@code null} if no path is found in
     * budget or either end isn't standable in a loaded/known cell.
     */
    public static List<Location> findPath(NavGrid.BlockSource src, Location from, Location to,
                                          Claim zone, int clearance, int maxNodes) {
        World world = src.world();
        if (world == null) return null;
        if (zone != null && !world.getUID().equals(zone.getWorldId())) return null;

        Node start = nodeAtStandable(src, from.getBlockX(), from.getBlockY(), from.getBlockZ(), zone, clearance);
        Node goal = nodeAtStandable(src, to.getBlockX(), to.getBlockY(), to.getBlockZ(), zone, clearance);
        if (start == null || goal == null) return null;
        if (start.equals(goal)) return List.of(center(world, goal));

        Map<Node, Double> gScore = new HashMap<>();
        Map<Node, Node> cameFrom = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(
                p -> gScore.getOrDefault(p, Double.MAX_VALUE) + heuristic(p, goal)));

        gScore.put(start, 0.0);
        open.add(start);

        int explored = 0;
        while (!open.isEmpty() && explored < maxNodes) {
            Node current = open.poll();
            if (current.equals(goal)) {
                return toLocations(world, reconstruct(cameFrom, current));
            }
            if (!closed.add(current)) continue;
            explored++;

            double currentG = gScore.getOrDefault(current, Double.MAX_VALUE);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Node next = neighbor(src, current, dx, dz, start, zone, clearance);
                    if (next == null || closed.contains(next)) continue;

                    // Prevent squeezing diagonally through solid corners.
                    if (dx != 0 && dz != 0) {
                        if (neighbor(src, current, dx, 0, start, zone, clearance) == null) continue;
                        if (neighbor(src, current, 0, dz, start, zone, clearance) == null) continue;
                    }

                    double tentative = currentG + stepCost(current, next);
                    if (tentative >= gScore.getOrDefault(next, Double.MAX_VALUE)) continue;

                    cameFrom.put(next, current);
                    gScore.put(next, tentative);
                    open.add(next);
                }
            }
        }
        return null;
    }

    /**
     * Find a standable feet location near the supplied block coordinate, checking upward first and
     * then downward. Useful for turret approach cells around a known anchor Y.
     */
    public static Location findStandableNear(NavGrid.BlockSource src, int x, int baseY, int z,
                                             int up, int down, Claim zone, int clearance) {
        for (int dy = 0; dy <= Math.max(up, down); dy++) {
            if (dy <= up && isStandable(src, x, baseY + dy, z, zone, clearance)) {
                return center(src.world(), new Node(x, baseY + dy, z));
            }
            if (dy > 0 && dy <= down && isStandable(src, x, baseY - dy, z, zone, clearance)) {
                return center(src.world(), new Node(x, baseY - dy, z));
            }
        }
        return null;
    }

    private static Node nodeAtStandable(NavGrid.BlockSource src, int x, int y, int z, Claim zone, int clearance) {
        Location snapped = findStandableNear(src, x, y, z, 3, 16, zone, clearance);
        return snapped == null ? null : new Node(snapped.getBlockX(), snapped.getBlockY(), snapped.getBlockZ());
    }

    private static Node neighbor(NavGrid.BlockSource src, Node cur, int dx, int dz, Node start,
                                 Claim zone, int clearance) {
        int x = cur.x + dx;
        int z = cur.z + dz;
        if (!src.loaded(x, z)) return null;
        if (!insideZone(x, z, zone)) return null;
        int rx = x - start.x;
        int rz = z - start.z;
        if (rx * rx + rz * rz > MAX_SEARCH_RADIUS * MAX_SEARCH_RADIUS) return null;

        for (int y = cur.y + 1; y >= cur.y - MAX_STEP_DOWN; y--) {
            if (isStandable(src, x, y, z, zone, clearance)) return new Node(x, y, z);
        }
        return null;
    }

    private static boolean isStandable(NavGrid.BlockSource src, int x, int y, int z, Claim zone, int clearance) {
        if (!src.loaded(x, z)) return false;
        if (!insideZone(x, z, zone)) return false;
        if (y <= src.minHeight() || y + clearance >= src.maxHeight()) return false;
        if (!src.solid(x, y - 1, z)) return false;
        for (int i = 0; i < clearance; i++) {
            if (!src.passable(x, y + i, z)) return false;
        }
        if (nearHazard(src, x, y, z)) return false;     // keep a 1-block buffer off lava edges
        return true;
    }

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** True if lava/fire sits in any of the 4 cardinally-adjacent cells (at feet or floor level), so
     *  a raider's hitbox/velocity can't clip into a lava edge while walking past a moat. */
    private static boolean nearHazard(NavGrid.BlockSource src, int x, int y, int z) {
        for (int[] o : CARDINALS) {
            if (src.hazard(x + o[0], y, z + o[1])) return true;
            if (src.hazard(x + o[0], y - 1, z + o[1])) return true;
        }
        return false;
    }

    private static boolean insideZone(int x, int z, Claim zone) {
        if (zone == null) return true;
        return x >= zone.getMinX() && x <= zone.getMaxX()
                && z >= zone.getMinZ() && z <= zone.getMaxZ();
    }

    private static double stepCost(Node a, Node b) {
        int dx = a.x - b.x;
        int dy = a.y - b.y;
        int dz = a.z - b.z;
        return Math.sqrt(dx * (double) dx + dy * (double) dy + dz * (double) dz);
    }

    private static double heuristic(Node a, Node b) {
        return stepCost(a, b);
    }

    private static List<Node> reconstruct(Map<Node, Node> cameFrom, Node goal) {
        List<Node> path = new ArrayList<>();
        Node cur = goal;
        while (cur != null) {
            path.add(0, cur);
            cur = cameFrom.get(cur);
        }
        return path;
    }

    private static List<Location> toLocations(World world, List<Node> nodes) {
        List<Location> out = new ArrayList<>(nodes.size());
        for (Node n : nodes) out.add(center(world, n));
        return out;
    }

    private static Location center(World world, Node n) {
        return new Location(world, n.x + 0.5, n.y, n.z + 0.5);
    }

    private record Node(int x, int y, int z) {}
}
