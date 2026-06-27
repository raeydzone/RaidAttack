package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Happy-Ghast rules (apply ONLY to {@link EntityType#HAPPY_GHAST} — never normal Ghasts):
 * <ul>
 *   <li><b>Speed buff only while actively RIDDEN by a player, with a smooth ramp.</b> A keyed
 *       {@link AttributeModifier} on the ghast's {@code FLYING_SPEED} raises its top speed toward
 *       ~12.5 blocks/second while a <em>player</em> rides it (a non-player passenger does not count;
 *       default un-ridden travel is ~5.3 b/s). The buff eases IN over ~3 s after mount and eases back
 *       OUT over ~3 s after dismount so it never snaps; a partial ramp (e.g. dismounting before full
 *       speed) eases out proportionally faster. Normal Ghasts and un-ridden Happy Ghasts keep full
 *       vanilla behaviour. Each mount/dismount + a ~1 Hz speed sample is logged ({@code [GhastRide]})
 *       so the top speed can be verified.</li>
 *   <li><b>Invincible inside any claim.</b> All damage to a Happy Ghast standing in a claimed zone
 *       is cancelled — ridden or parked, any damage cause.</li>
 *   <li><b>Confined to the claim it was parked in.</b> When an un-ridden Happy Ghast is in a claim we
 *       remember that claim as its "home" and clamp it to the claim's XZ footprint — pulling it back
 *       in even after vanilla wandering has carried it out (overriding the mob's natural roam). The
 *       home leash is released the moment a player rides it; on dismount it re-homes to wherever it
 *       lands (if that's inside a claim). A ghast that was never parked in a claim roams freely.</li>
 *   <li><b>One-time attack notice.</b> A player who hits a protected Happy Ghast is told once per
 *       login that they can't attack it inside a protected area.</li>
 * </ul>
 */
public final class HappyGhastManager implements Listener {

    /**
     * Ridden top-speed target as a MULTIPLIER on the ghast's live default {@code FLYING_SPEED} base
     * value (read at runtime). The curve is ~QUADRATIC (cap 0.0665 → ~6 b/s, 0.096 → ~11.8 cruise,
     * 0.13 → ~24 b/s; speed ≈ 1300·cap²). NOTE: {@code FLYING_SPEED} is an acceleration/force cap, not
     * a hard velocity ceiling — steady cruise settles near this value but momentum and especially
     * diving overshoot it (we can't hard-clamp a player-ridden mob's velocity). 1.71× (cap ≈ 0.0854)
     * is ~10% slower than the earlier 1.8× — a ~9.4 cruise / ~12–13 dive feel. Nudge from the
     * {@code [GhastRide]} log; quadratic (speed ∝ cap²), so small changes move speed a lot.
     */
    private static final double RIDDEN_SPEED_ATTR_MULTIPLIER = 1.71;
    /** Seconds for the buff to fully ease IN (after mount) or fully ease OUT (after dismount). */
    private static final double RAMP_SECONDS = 3.0;
    /** Enforcement cadence — eases the ridden buff in/out + confines parked ghasts. */
    private static final long TASK_INTERVAL = 5L;   // 0.25 s
    /** Per-tick ramp delta so a full 0→1 (or 1→0) transition spans {@link #RAMP_SECONDS}. */
    private static final double RAMP_STEP = (TASK_INTERVAL / 20.0) / RAMP_SECONDS;
    /** Worst-case escape: a parked ghast pressed against the zone border this long (s) is teleported. */
    private static final double STUCK_SECONDS = 5.0;
    private static final long STUCK_TICKS = (long) (STUCK_SECONDS * 20);
    /** "Pressed against the border" = the ghast is within this many blocks of a zone wall. */
    private static final double STUCK_EDGE = 1.0;

    private final HomeSystemPlugin plugin;
    private final NamespacedKey speedKey;
    /** Players already shown the "can't attack in a protected area" notice this login. */
    private final Set<UUID> warned = new HashSet<>();
    /** Per-ghast ramp position in [0,1]: 0 = vanilla speed, 1 = full ridden top speed. */
    private final Map<UUID, Double> rampProgress = new HashMap<>();
    /** Claim (+ world) an un-ridden ghast is leashed to; survives the ghast drifting out of bounds. */
    private final Map<UUID, Park> parkedHome = new HashMap<>();
    /** Ghasts we've already logged a "mounted" line for (drives mount/dismount transition logging). */
    private final Set<UUID> riddenLogged = new HashSet<>();
    /** Last sampled position per ghast — used to measure real speed (ridden mobs report velocity ≈ 0). */
    private final Map<UUID, Location> lastLoc = new HashMap<>();
    /** Tick at which a parked ghast first pressed against the zone border (drives the 5 s escape). */
    private final Map<UUID, Long> borderSince = new HashMap<>();
    private BukkitTask task;

    /** A parked ghast's home claim plus the world it was parked in (Claim has no public world getter). */
    private static final class Park {
        final Claim claim;
        final UUID worldId;
        Park(Claim claim, UUID worldId) { this.claim = claim; this.worldId = worldId; }
    }

    public HappyGhastManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.speedKey = new NamespacedKey(plugin, "happy_ghast_speed");
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::enforce, TASK_INTERVAL, TASK_INTERVAL);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    // -- periodic enforcement -------------------------------------------------

    private void enforce() {
        Set<UUID> seen = new HashSet<>();
        boolean sampleNow = (Bukkit.getCurrentTick() % 20L) < TASK_INTERVAL;   // ~once/sec speed log
        for (World w : Bukkit.getWorlds()) {
            for (LivingEntity le : w.getLivingEntities()) {
                if (le.getType() != EntityType.HAPPY_GHAST) continue;
                UUID id = le.getUniqueId();
                seen.add(id);
                // Legacy cleanup: the first version buffed MOVEMENT_SPEED too (always-on). We only
                // use FLYING_SPEED now, so strip any leftover keyed modifier from MOVEMENT_SPEED.
                stripKeyed(le.getAttribute(Attribute.MOVEMENT_SPEED));

                // Only an actively-riding PLAYER triggers our speed logic — a non-player passenger
                // (or no passenger) leaves the ghast at full vanilla behaviour.
                boolean ridden = hasPlayerRider(le);

                // Measure real speed from position delta — a player-ridden mob reports velocity ≈ 0
                // (the rider drives it), so getVelocity() reads as 0 and is useless for this.
                Location cur = le.getLocation();
                Location prev = lastLoc.put(id, cur);
                double bps = 0.0, hbps = 0.0;
                if (prev != null && cur.getWorld() != null && cur.getWorld().equals(prev.getWorld())) {
                    double dt = TASK_INTERVAL / 20.0;
                    double dx = cur.getX() - prev.getX(), dy = cur.getY() - prev.getY(), dz = cur.getZ() - prev.getZ();
                    bps = Math.sqrt(dx * dx + dy * dy + dz * dz) / dt;
                    hbps = Math.hypot(dx, dz) / dt;
                }
                logRideState(le, id, ridden, sampleNow, bps, hbps);

                double prog = rampProgress.getOrDefault(id, 0.0);
                // Ease the buff IN while ridden, OUT once dismounted. Easing out from wherever it
                // currently sits means a brief ride decelerates proportionally faster than a full one.
                prog = ridden ? Math.min(1.0, prog + RAMP_STEP) : Math.max(0.0, prog - RAMP_STEP);

                if (prog <= 0.0) {
                    rampProgress.remove(id);
                    removeRiddenSpeed(le);                // back to vanilla speed
                } else {
                    rampProgress.put(id, prog);
                    applyRiddenSpeed(le, prog);
                }

                // Confinement: a ridden ghast roams free (rider drives it); an un-ridden ghast is
                // leashed to the claim it was parked in — re-homing each tick it's inside one, and
                // pulled back if it has wandered out.
                if (ridden) {
                    parkedHome.remove(id);
                } else {
                    Claim here = plugin.getClaimManager().getClaimAt(le.getLocation());
                    if (here != null) parkedHome.put(id, new Park(here, le.getWorld().getUID()));
                    Park home = parkedHome.get(id);
                    if (home != null && le.getWorld().getUID().equals(home.worldId)) confineToClaim(le, home.claim);
                }
            }
        }
        // Drop per-ghast state for ghasts that no longer exist so the maps can't grow without bound.
        rampProgress.keySet().retainAll(seen);
        parkedHome.keySet().retainAll(seen);
        riddenLogged.retainAll(seen);
        lastLoc.keySet().retainAll(seen);
        borderSince.keySet().retainAll(seen);
    }

    /** Log mount/dismount transitions, and (~1 Hz while ridden) a speed/cap sample for verification. */
    private void logRideState(LivingEntity le, UUID id, boolean ridden, boolean sampleNow, double bps, double hbps) {
        if (ridden) {
            if (riddenLogged.add(id)) {
                Player r = firstPlayerRider(le);
                plugin.getLogger().info("[GhastRide] " + (r != null ? r.getName() : "?")
                        + " mounted Happy Ghast " + shortId(id) + " — speed buff easing in.");
            }
            if (sampleNow) {
                AttributeInstance attr = le.getAttribute(Attribute.FLYING_SPEED);
                double cap = attr != null ? attr.getValue() : -1.0;       // effective (base + our modifier)
                double base = attr != null ? attr.getBaseValue() : -1.0;
                double prog = rampProgress.getOrDefault(id, 0.0);
                plugin.getLogger().info(String.format(Locale.ROOT,
                        "[GhastRide] %s ridden: speed=%.2f b/s (horiz %.2f) | flyingSpeed cap=%.4f base=%.4f ramp=%.2f",
                        shortId(id), bps, hbps, cap, base, prog));
            }
        } else if (riddenLogged.remove(id)) {
            plugin.getLogger().info("[GhastRide] Happy Ghast " + shortId(id) + " dismounted — speed buff easing out.");
        }
    }

    private static boolean hasPlayerRider(LivingEntity le) {
        for (Entity e : le.getPassengers()) if (e instanceof Player) return true;
        return false;
    }

    private static Player firstPlayerRider(LivingEntity le) {
        for (Entity e : le.getPassengers()) if (e instanceof Player p) return p;
        return null;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    /**
     * Set the ridden flying buff to the given ramp position. At {@code progress == 1} the effective
     * flying speed reaches {@link #RIDDEN_SPEED_ATTR_MULTIPLIER}× the live base value; {@code progress}
     * scales the added amount linearly so the cap eases in/out. The keyed modifier is replaced each
     * tick (its amount changes as we ramp), so it never compounds.
     */
    private void applyRiddenSpeed(LivingEntity ghast, double progress) {
        AttributeInstance attr = ghast.getAttribute(Attribute.FLYING_SPEED);
        if (attr == null) return;
        double fullAmount = attr.getBaseValue() * (RIDDEN_SPEED_ATTR_MULTIPLIER - 1.0); // amount at progress=1
        double amount = fullAmount * progress;                                          // ADD_NUMBER → cap
        stripKeyed(attr);                                                               // replace, don't compound
        if (amount > 1e-6) {
            attr.addModifier(new AttributeModifier(speedKey, amount, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    private void removeRiddenSpeed(LivingEntity ghast) {
        stripKeyed(ghast.getAttribute(Attribute.FLYING_SPEED));
    }

    /** Remove our keyed speed modifier from an attribute, if present. Null-safe. */
    private void stripKeyed(AttributeInstance attr) {
        if (attr == null) return;
        for (AttributeModifier m : attr.getModifiers()) {
            if (speedKey.equals(m.getKey())) { attr.removeModifier(m); return; }
        }
    }

    /**
     * Keep a parked (un-ridden) ghast inside its home claim, in three layers:
     * <ol>
     *   <li><b>Steer to the centre chunk.</b> The chunk containing the zone centre is its "comfort
     *       box" — being interior, it rarely touches a wall. Whenever the ghast wanders out of that
     *       box we point its pathfinder at the zone centre, biasing it to roam the middle (overriding
     *       the mob's vanilla "wander within my chunk" with "wander within the centre chunk").</li>
     *   <li><b>Hard clamp.</b> The base-edge invisible wall — it can never actually cross the
     *       footprint.</li>
     *   <li><b>Stuck escape (worst case).</b> If it's been pressed against the border for
     *       {@link #STUCK_SECONDS}s straight (pathfinding failed to pull it off the wall), teleport it
     *       to the zone centre. Only as a last resort, never instantly.</li>
     * </ol>
     */
    private void confineToClaim(LivingEntity ghast, Claim claim) {
        UUID id = ghast.getUniqueId();
        Location loc = ghast.getLocation();
        World world = loc.getWorld();
        double zMinX = claim.getMinX(), zMaxX = claim.getMaxX() + 1.0;   // world-space span (block-inclusive)
        double zMinZ = claim.getMinZ(), zMaxZ = claim.getMaxZ() + 1.0;
        double centreX = (claim.getMinX() + claim.getMaxX()) / 2.0 + 0.5;
        double centreZ = (claim.getMinZ() + claim.getMaxZ()) / 2.0 + 0.5;

        // Centre chunk (intersected with the zone) — the comfort box to keep it roaming within.
        int ccx = Math.floorDiv((int) Math.floor(centreX), 16);
        int ccz = Math.floorDiv((int) Math.floor(centreZ), 16);
        double ccMinX = Math.max(zMinX, ccx * 16.0), ccMaxX = Math.min(zMaxX, ccx * 16.0 + 16.0);
        double ccMinZ = Math.max(zMinZ, ccz * 16.0), ccMaxZ = Math.min(zMaxZ, ccz * 16.0 + 16.0);

        // (1) Steer back toward the centre whenever it strays out of the centre chunk.
        if (ghast instanceof Mob mob
                && (loc.getX() < ccMinX || loc.getX() > ccMaxX || loc.getZ() < ccMinZ || loc.getZ() > ccMaxZ)) {
            try {
                mob.getPathfinder().moveTo(new Location(world, centreX, loc.getY(), centreZ, loc.getYaw(), loc.getPitch()));
            } catch (Throwable ignored) {}
        }

        // (2) Stuck escape: continuously pressed against the zone border for STUCK_SECONDS → teleport.
        boolean atBorder = loc.getX() <= zMinX + STUCK_EDGE || loc.getX() >= zMaxX - STUCK_EDGE
                        || loc.getZ() <= zMinZ + STUCK_EDGE || loc.getZ() >= zMaxZ - STUCK_EDGE;
        if (atBorder) {
            long since = borderSince.computeIfAbsent(id, k -> (long) Bukkit.getCurrentTick());
            if (Bukkit.getCurrentTick() - since >= STUCK_TICKS) {
                ghast.setVelocity(new Vector(0, 0, 0));
                ghast.teleport(new Location(world, centreX, loc.getY(), centreZ, loc.getYaw(), loc.getPitch()));
                borderSince.remove(id);
                return;
            }
        } else {
            borderSince.remove(id);
        }

        // (3) Hard clamp to the zone footprint — last line of defence.
        double cx = Math.max(zMinX + 0.5, Math.min(zMaxX - 0.5, loc.getX()));
        double cz = Math.max(zMinZ + 0.5, Math.min(zMaxZ - 0.5, loc.getZ()));
        if (cx != loc.getX() || cz != loc.getZ()) {
            ghast.setVelocity(new Vector(0, 0, 0));
            ghast.teleport(new Location(world, cx, loc.getY(), cz, loc.getYaw(), loc.getPitch()));
        }
    }

    // -- invincibility + one-time notice --------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntityType() != EntityType.HAPPY_GHAST) return;
        if (plugin.getClaimManager().getClaimAt(e.getEntity().getLocation()) == null) return;
        e.setCancelled(true);                                    // invincible inside any claim
        if (e instanceof EntityDamageByEntityEvent ed) {
            Player attacker = resolvePlayer(ed.getDamager());
            if (attacker != null && warned.add(attacker.getUniqueId())) {
                attacker.sendMessage(ChatColor.RED
                        + "You cannot attack a Happy Ghast while it is in a protected area.");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        warned.remove(e.getPlayer().getUniqueId());              // reset the notice per login
    }

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }
}
