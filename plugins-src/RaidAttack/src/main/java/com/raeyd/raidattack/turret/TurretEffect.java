package com.raeyd.raidattack.turret;

import java.util.List;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Temporary visual marker for turret positions. The turrets themselves are unimplemented; this
 * effect stands in so the player can see where a slot has been deployed (or where all existing
 * slots are when listing). Pure cosmetic — no entity is spawned.
 *
 * <p>Effect is a green dust column rising five blocks from the turret's block position, pulsed
 * every 0.5 s for the requested duration.
 */
public final class TurretEffect {

    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.LIME, 1.6f);
    private static final int VERTICAL_LAYERS = 5;
    private static final long EMIT_INTERVAL_TICKS = 10L;
    public static final long DEPLOY_DURATION_TICKS = 60L;   // 3 s on /turret deploy
    public static final long LIST_DURATION_TICKS = 100L;    // 5 s on /turret list

    private TurretEffect() {}

    /** One-shot confirmation effect at a single just-placed turret. Plays a small "ping" sound. */
    public static void playDeploy(Plugin plugin, Player who, World world, Turret turret) {
        who.playSound(who.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.5f);
        pulse(plugin, world, List.of(turret), DEPLOY_DURATION_TICKS);
    }

    /** Visualise every turret in a claim (used by {@code /HomeSystem turret list}). */
    public static void playList(Plugin plugin, World world, List<Turret> turrets) {
        pulse(plugin, world, turrets, LIST_DURATION_TICKS);
    }

    private static void pulse(Plugin plugin, World world, List<Turret> turrets, long durationTicks) {
        if (world == null || turrets.isEmpty()) return;
        new BukkitRunnable() {
            long elapsed = 0L;
            @Override public void run() {
                for (Turret t : turrets) emitColumn(world, t);
                elapsed += EMIT_INTERVAL_TICKS;
                if (elapsed >= durationTicks) cancel();
            }
        }.runTaskTimer(plugin, 0L, EMIT_INTERVAL_TICKS);
    }

    private static void emitColumn(World world, Turret t) {
        for (int dy = 0; dy < VERTICAL_LAYERS; dy++) {
            world.spawnParticle(
                    Particle.DUST,
                    t.getX() + 0.5, t.getY() + 0.5 + dy, t.getZ() + 0.5,
                    3,                  // count per layer — a small puff
                    0.15, 0.05, 0.15,   // jitter
                    0.0,                // extra (speed)
                    GREEN,
                    true                // force, visible at distance
            );
        }
    }

    /** Convenience for one-off ad-hoc location effects (e.g. on remove confirmation). */
    public static void playOnce(Location loc) {
        World w = loc.getWorld();
        if (w == null) return;
        w.spawnParticle(Particle.DUST, loc.clone().add(0.5, 0.5, 0.5),
                30, 0.4, 0.4, 0.4, 0.0, GREEN, true);
    }
}
