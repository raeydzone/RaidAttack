package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Anti-spawn-camp "public spawn area" — a {@value #HALF}-block-radius square (≈500×500) centred on
 * the overworld world spawn. Two protections, both deliberately lighter than a claim:
 *
 * <ul>
 *   <li><b>Random respawn.</b> A player with no personal spawn (no bed / no anchor, or a destroyed
 *       one) would land on the exact world-spawn block — a campable choke point. Instead they are
 *       scattered to a random surface spot somewhere inside the area, so a camper can't predict
 *       where they reappear. Bed / anchor respawns are left alone.</li>
 *   <li><b>Adventure + explosion-proof.</b> Everyone inside the area is forced to Adventure (handled
 *       by the spawn-area branch in {@link ZoneListener#update}), so nobody can break or place
 *       blocks here; and creeper / TNT block damage is stripped (the spawn-area clause added to
 *       {@link ZoneListener}'s explosion handlers). This is NOT full claim protection — containers,
 *       entities, fluids etc. behave normally — it only stops the area's terrain being griefed.</li>
 * </ul>
 *
 * <p>The area is anchored live to {@link World#getSpawnLocation()} of the first NORMAL world, so
 * moving the world spawn moves the area with it.
 */
public final class SpawnAreaManager implements Listener {

    /** Half-extent of the square spawn area (blocks). 250 → a ≈500×500 footprint around spawn. */
    public static final int HALF = 250;
    /** How many random points to sample looking for safe (solid, non-hazard) ground before giving up. */
    private static final int MAX_SPAWN_TRIES = 16;

    private final HomeSystemPlugin plugin;

    public SpawnAreaManager(HomeSystemPlugin plugin) { this.plugin = plugin; }

    /** The overworld whose spawn anchors the area (first NORMAL world). */
    private World overworld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) return w;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    /** True iff {@code loc} is inside the overworld spawn area (square ±{@link #HALF} around spawn). */
    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        World ow = overworld();
        if (ow == null || !loc.getWorld().equals(ow)) return false;
        Location spawn = ow.getSpawnLocation();
        return Math.abs(loc.getBlockX() - spawn.getBlockX()) <= HALF
                && Math.abs(loc.getBlockZ() - spawn.getBlockZ()) <= HALF;
    }

    /**
     * True iff the rectangle {@code [minX,minZ]–[maxX,maxZ]} in {@code world} overlaps the spawn
     * area (AABB intersection). Used to keep the spawn zone claim-free.
     */
    public boolean intersects(World world, int minX, int minZ, int maxX, int maxZ) {
        World ow = overworld();
        if (ow == null || world == null || !world.equals(ow)) return false;
        Location spawn = ow.getSpawnLocation();
        int cx = spawn.getBlockX(), cz = spawn.getBlockZ();
        return minX <= cx + HALF && maxX >= cx - HALF
                && minZ <= cz + HALF && maxZ >= cz - HALF;
    }

    /** A random, reasonably-safe surface spot within the spawn area (or {@code null} if no overworld). */
    public Location randomSurfaceSpawn() {
        World ow = overworld();
        if (ow == null) return null;
        Location spawn = ow.getSpawnLocation();
        int cx = spawn.getBlockX(), cz = spawn.getBlockZ();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location fallback = null;
        for (int i = 0; i < MAX_SPAWN_TRIES; i++) {
            int x = cx + r.nextInt(-HALF, HALF + 1);
            int z = cz + r.nextInt(-HALF, HALF + 1);
            int y = ow.getHighestBlockYAt(x, z);                 // surface (top motion-blocking block)
            Location candidate = new Location(ow, x + 0.5, y + 1.0, z + 0.5, r.nextFloat() * 360f, 0f);
            if (fallback == null) fallback = candidate;          // first sample = last-resort fallback
            Block surface = ow.getBlockAt(x, y, z);
            Material m = surface.getType();
            if (!m.isSolid()) continue;                          // need solid ground to stand on
            if (m == Material.LAVA || m == Material.MAGMA_BLOCK || m == Material.CACTUS
                    || m == Material.FIRE || m == Material.WATER) continue;   // skip hazards / water
            return candidate;
        }
        return fallback;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRespawn(PlayerRespawnEvent e) {
        if (e.isBedSpawn() || e.isAnchorSpawn()) return;         // personal spawn set — leave it
        Location random = randomSurfaceSpawn();
        if (random == null) return;
        e.setRespawnLocation(random);
        // Re-evaluate gamemode one tick later (player is fully placed by then) so they land in
        // Adventure via the spawn-area rule in ZoneListener, without waiting for their first move.
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && plugin.getZoneListener() != null) {
                plugin.getZoneListener().update(p, p.getLocation());
            }
        });
    }
}
