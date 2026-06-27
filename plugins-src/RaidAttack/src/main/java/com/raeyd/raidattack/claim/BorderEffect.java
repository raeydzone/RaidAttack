package com.raeyd.raidattack.claim;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Plays the "claim confirmed" feedback: a one-shot XP levelup sound, then a green particle
 * outline along the claim's perimeter for ten seconds.
 *
 * <p>For each block on the perimeter we sample the topmost solid Y via the WORLD_SURFACE
 * heightmap and spawn six dust particles stacked from {@code surface+1} up to {@code surface+6}.
 * That keeps particle spawns to the perimeter only — no underground spam, no air spam.
 */
public final class BorderEffect {

    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.LIME, 1.5f);
    private static final int VERTICAL_LAYERS = 6;          // surface+1 .. surface+6
    private static final long EMIT_INTERVAL_TICKS = 10L;   // every 0.5 s
    public static final long CLAIM_DURATION_TICKS = 200L;  // 10 s — on /HomeSystem claim
    public static final long SHOW_DURATION_TICKS = 100L;   //  5 s — on /HomeSystem show border

    private BorderEffect() {}

    /** Convenience: full claim-creation feedback (XP sound + 10 s border). */
    public static void play(Plugin plugin, Player who, Claim claim) {
        play(plugin, who, claim, CLAIM_DURATION_TICKS, true);
    }

    /**
     * @param durationTicks how long the particle outline persists, in server ticks
     * @param playSound     whether to also play the one-shot LEVELUP confirmation
     */
    public static void play(Plugin plugin, Player who, Claim claim, long durationTicks, boolean playSound) {
        if (playSound) {
            who.playSound(who.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }

        World world = plugin.getServer().getWorld(claim.getWorldId());
        if (world == null) return;

        new BukkitRunnable() {
            long elapsed = 0L;
            @Override public void run() {
                emit(world, claim);
                elapsed += EMIT_INTERVAL_TICKS;
                if (elapsed >= durationTicks) cancel();
            }
        }.runTaskTimer(plugin, 0L, EMIT_INTERVAL_TICKS);
    }

    private static void emit(World world, Claim claim) {
        int minX = claim.getMinX();
        int maxX = claim.getMaxX();
        int minZ = claim.getMinZ();
        int maxZ = claim.getMaxZ();

        // North + south edges (full X span).
        for (int x = minX; x <= maxX; x++) {
            emitColumn(world, x, minZ);
            if (maxZ != minZ) emitColumn(world, x, maxZ);
        }
        // East + west edges (exclude corners we already did).
        for (int z = minZ + 1; z <= maxZ - 1; z++) {
            emitColumn(world, minX, z);
            if (maxX != minX) emitColumn(world, maxX, z);
        }
    }

    private static void emitColumn(World world, int x, int z) {
        // Skip columns in unloaded chunks: getHighestBlockYAt there would force a synchronous
        // chunk load/generation on the main thread. A border particle in an unloaded chunk is
        // invisible to everyone anyway, so there's nothing to draw.
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        int surface = world.getHighestBlockYAt(x, z);
        for (int dy = 1; dy <= VERTICAL_LAYERS; dy++) {
            world.spawnParticle(
                    Particle.DUST,
                    x + 0.5, surface + dy + 0.1, z + 0.5,
                    1,                  // count
                    0.0, 0.0, 0.0,      // offset
                    0.0,                // extra (speed)
                    GREEN,
                    true                // force, so distant players also see it
            );
        }
    }
}
