package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Relocates phantoms from the Overworld to The End.
 *
 * <ul>
 *   <li><b>Overworld:</b> the vanilla insomnia phantom spawn ({@link CreatureSpawnEvent.SpawnReason#NATURAL})
 *       is cancelled, so phantoms never appear there naturally. Dev /summon (COMMAND) and spawn eggs
 *       (SPAWNER_EGG) are left alone for testing.</li>
 *   <li><b>The End:</b> a ticker keeps ~{@link #PER_PLAYER_TARGET} vanilla {@link Phantom}s in the air
 *       around each player (capped server-wide at {@link #HARD_CAP}). Phantoms spawn just overhead so
 *       they actually engage, and are force-targeted at the player. Each phantom DEATH blocks one
 *       refill slot for {@link #RESPAWN_COOLDOWN_MS} (1 min), so killing them isn't an instant farm —
 *       e.g. kill 2 of 5 at once and it's a full minute before 2 new ones can appear.</li>
 * </ul>
 *
 * <p>Note: the "phantom" the turret/wither code talks about is an {@link org.bukkit.entity.ArmorStand}
 * HP-label, NOT a real Phantom — so none of this touches turrets.
 */
public final class EndPhantomManager implements Listener {

    // -- tunables (all in one place) ------------------------------------------
    /** How often the End spawn pass runs (ticks). 30 = 1.5 s. */
    private static final long SPAWN_INTERVAL_TICKS = 30L;
    /** Target number of phantoms to keep around each player. */
    private static final int PER_PLAYER_TARGET = 5;
    /** Absolute ceiling on phantoms across all End worlds combined. */
    private static final int HARD_CAP = 20;
    /** Radius (blocks) that "belongs" to a player — phantoms inside it count toward their target. */
    private static final double CAP_RADIUS = 64.0;
    /** After a phantom dies, one refill slot is blocked for this long (1 min). */
    private static final long RESPAWN_COOLDOWN_MS = 60_000L;
    /** Most phantoms spawned for one player in a single pass (gentle ramp-up, not a burst). */
    private static final int MAX_PER_PASS = 2;
    /** Horizontal offset (blocks) from the player — kept small so they spawn ~overhead and engage. */
    private static final double RING_MIN = 4.0, RING_MAX = 14.0;
    /** Height (blocks) above the player the phantom spawns at (~20 — high enough to swoop). */
    private static final int HEIGHT_MIN = 16, HEIGHT_MAX = 24;

    private final HomeSystemPlugin plugin;
    private BukkitTask task;
    /** playerId → queue of death-cooldown expiry timestamps. Each entry blocks one refill for 1 min. */
    private final Map<UUID, Deque<Long>> deathCooldowns = new HashMap<>();

    public EndPhantomManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                SPAWN_INTERVAL_TICKS, SPAWN_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        deathCooldowns.clear();
    }

    // -- overworld suppression ------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.PHANTOM) return;
        World w = e.getLocation().getWorld();
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return;
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) e.setCancelled(true);
    }

    // -- death cooldown -------------------------------------------------------

    /** Each phantom death in The End blocks one refill slot for {@link #RESPAWN_COOLDOWN_MS},
     *  charged to the nearest player within {@link #CAP_RADIUS} (the one it was harassing). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Phantom ph)) return;
        if (ph.getWorld().getEnvironment() != World.Environment.THE_END) return;
        Player nearest = null;
        double bestSq = CAP_RADIUS * CAP_RADIUS;
        for (Player p : ph.getWorld().getPlayers()) {
            double dsq = p.getLocation().distanceSquared(ph.getLocation());
            if (dsq <= bestSq) { bestSq = dsq; nearest = p; }
        }
        if (nearest == null) return;     // died far from everyone → no refill pressure
        deathCooldowns.computeIfAbsent(nearest.getUniqueId(), k -> new ArrayDeque<>())
                .addLast(System.currentTimeMillis() + RESPAWN_COOLDOWN_MS);
    }

    // -- end spawning ---------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() != World.Environment.THE_END) continue;
            int globalAlive = w.getEntitiesByClass(Phantom.class).size();
            for (Player p : w.getPlayers()) {
                if (globalAlive >= HARD_CAP) break;
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                globalAlive += trySpawnAround(w, p, now, HARD_CAP - globalAlive);
            }
        }
    }

    /** @return number of phantoms actually spawned for this player. */
    private int trySpawnAround(World w, Player p, long now, int globalRemaining) {
        if (globalRemaining <= 0) return 0;

        // Death cooldowns currently blocking refills for this player (expired ones pruned).
        Deque<Long> cds = deathCooldowns.get(p.getUniqueId());
        int activeCooldowns = 0;
        if (cds != null) {
            cds.removeIf(exp -> exp <= now);
            activeCooldowns = cds.size();
        }

        int aliveNear = 0;
        for (Entity e : p.getNearbyEntities(CAP_RADIUS, CAP_RADIUS, CAP_RADIUS)) {
            if (e instanceof Phantom) aliveNear++;
        }

        // Refill toward the target, minus what's alive AND minus slots a recent death still holds.
        int allowed = PER_PLAYER_TARGET - aliveNear - activeCooldowns;
        if (allowed <= 0) return 0;

        int toSpawn = Math.min(Math.min(allowed, MAX_PER_PASS), globalRemaining);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location base = p.getLocation();
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            double ang = r.nextDouble(0.0, Math.PI * 2.0);
            double dist = r.nextDouble(RING_MIN, RING_MAX);
            double x = base.getX() + Math.cos(ang) * dist;
            double z = base.getZ() + Math.sin(ang) * dist;
            int y = Math.min((int) base.getY() + r.nextInt(HEIGHT_MIN, HEIGHT_MAX + 1),
                    w.getMaxHeight() - 2);
            Location at = new Location(w, x, y, z);
            if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            if (!at.getBlock().getType().isAir()) continue;   // need air to fit the phantom
            try {
                Phantom ph = w.spawn(at, Phantom.class);       // CUSTOM reason; vanilla swoop AI
                ph.setTarget(p);                               // lock onto the player so it engages
                spawned++;
            } catch (IllegalArgumentException ignored) {
                // bad spawn location (out of world bounds etc.) — skip this one
            }
        }
        return spawned;
    }
}
