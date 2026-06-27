package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Combat re-balancing (the active nerfs that follow the {@link DamageTrackingListener} data-gathering
 * phase). Runs at {@link EventPriority#HIGH} so it mutates the BASE (raw) damage before armor /
 * Resistance / Blast-Protection are applied and before the MONITOR-priority tracker logs the result.
 *
 * <ul>
 *   <li><b>Mace</b> — vanilla fall-smash damage is left untouched up to 25 raw, then compressed
 *       along a straight line so that what vanilla would deal as 63 raw lands at the 35 raw cap;
 *       anything heavier is clamped to 35. So the curve eases into the cap instead of a hard wall.</li>
 *   <li><b>Spear</b> — same shape, but the knee→cap compression spans 25→50 vanilla raw (the spear's
 *       lighter ceiling) onto 25→35, clamped to 35 beyond.</li>
 *   <li><b>End Crystal explosion</b> — damage replaced with a flat-then-linear falloff: full 40 raw
 *       within 1 block of the blast, scaling to 0 at 4 blocks, nothing beyond. Vanilla blast knockback
 *       is overridden with our own (launch up + away, scaled by closeness). Cover is respected: if a
 *       solid block sits between the crystal and the victim (e.g. crouched under it) there's no damage
 *       and no launch — mirroring vanilla line-of-sight shielding.</li>
 *   <li><b>Respawn Anchor explosion</b> — identical damage falloff and launch, but with NO cover
 *       exception: every entity inside the radius takes it regardless of position or shielding.</li>
 * </ul>
 */
public final class CombatBalanceListener implements Listener {

    // -- weapon caps ----------------------------------------------------------
    private static final double MACE_KNEE = 25.0, MACE_CAP = 35.0, MACE_VANILLA_AT_CAP = 63.0;
    private static final double SPEAR_KNEE = 25.0, SPEAR_CAP = 35.0, SPEAR_VANILLA_AT_CAP = 50.0;

    // -- explosion falloff ----------------------------------------------------
    /** Full damage at/under this distance (blocks) from the blast centre. */
    private static final double EXPLODE_FULL_RADIUS = 1.0;
    /** Zero damage at/beyond this distance (blocks); linear between the two. */
    private static final double EXPLODE_OUTER_RADIUS = 4.0;
    /** Raw damage dealt at point-blank. */
    private static final double EXPLODE_MAX_DAMAGE = 40.0;
    /** Launch velocity (blocks/tick) at point-blank — vertical. ~0.85 ≈ ~5 blocks of height. */
    private static final double KB_MAX_UP = 0.85;
    /** Launch velocity (blocks/tick) at point-blank — horizontal (away). ~0.95 ≈ ~10 blocks out. */
    private static final double KB_MAX_HORIZONTAL = 0.95;

    private final HomeSystemPlugin plugin;

    public CombatBalanceListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    // -- entity-sourced: mace, spear, end crystal -----------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();

        // End Crystal explosion — distance falloff + custom launch, cover respected.
        if (damager instanceof EnderCrystal) {
            if (e.getEntity() instanceof LivingEntity victim) applyExplosion(e, victim, true, "END_CRYSTAL");
            return;
        }

        // Mace / Spear — remap the raw weapon damage onto the nerf curve.
        Player attacker = resolvePlayer(damager);
        boolean spearProjectile = damager instanceof Projectile && damager.getType().name().contains("SPEAR");
        if (attacker != null) {
            Material held = attacker.getInventory().getItemInMainHand().getType();
            if (held == Material.MACE) { e.setDamage(maceCurve(e.getDamage())); return; }
            if (held.name().contains("SPEAR") || spearProjectile) { e.setDamage(spearCurve(e.getDamage())); }
        } else if (spearProjectile) {
            e.setDamage(spearCurve(e.getDamage()));
        }
    }

    // -- block-sourced: respawn anchor ----------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByBlock(EntityDamageByBlockEvent e) {
        if (!(e.getEntity() instanceof LivingEntity victim)) return;
        // Any block explosion (respawn anchor, bed in the wrong dimension, …). The anchor blast is a
        // partly-unregistered explosion — Mojang fires it almost like a raw "apply explosion damage"
        // with the source block already gone (getDamager() == null) — so we key purely off the
        // BLOCK_EXPLOSION cause instead of trusting any block reference to identify it.
        if (e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        applyExplosion(e, victim, false, "BLOCK_EXPLOSION");     // no cover exception for the anchor
    }

    // -- shared explosion handling --------------------------------------------

    private void applyExplosion(EntityDamageEvent e, LivingEntity victim, boolean respectCover, String label) {
        Location center = explosionCenter(e, victim);
        if (center == null || center.getWorld() == null || !center.getWorld().equals(victim.getWorld())) {
            // Couldn't locate the blast (e.g. an anchor with no source data). At minimum enforce the
            // cap so it can never one-shot; skip the distance falloff and the launch.
            double capped = Math.min(e.getDamage(), EXPLODE_MAX_DAMAGE);
            e.setDamage(capped);
            plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                    "[Balance] %s blast: centre UNKNOWN -> capped raw to %.1f", label, capped));
            return;
        }

        if (respectCover && shielded(center, victim)) {      // crouched under a block etc.
            e.setCancelled(true);
            return;
        }

        double d = center.toVector().distance(victim.getBoundingBox().getCenter());
        if (d >= EXPLODE_OUTER_RADIUS) { e.setCancelled(true); return; }   // outside radius → nothing

        double dmg = damageAtDistance(d);
        e.setDamage(dmg);
        if (victim instanceof Player p) scheduleLaunch(p, center, d);      // override vanilla knockback
        plugin.getLogger().info(String.format(java.util.Locale.ROOT,
                "[Balance] %s blast: dist=%.2f -> raw %.1f%s", label, d, dmg,
                victim instanceof Player ? " + launch" : ""));
    }

    /** Flat {@link #EXPLODE_MAX_DAMAGE} within the inner radius, linear down to 0 at the outer radius. */
    private static double damageAtDistance(double d) {
        if (d <= EXPLODE_FULL_RADIUS) return EXPLODE_MAX_DAMAGE;
        if (d >= EXPLODE_OUTER_RADIUS) return 0.0;
        double f = (EXPLODE_OUTER_RADIUS - d) / (EXPLODE_OUTER_RADIUS - EXPLODE_FULL_RADIUS);
        return EXPLODE_MAX_DAMAGE * f;
    }

    /**
     * Replace vanilla blast knockback with our own launch, applied one tick later so it overwrites
     * whatever the explosion set. Closeness {@code f} scales both the upward and outward push, so a
     * point-blank hit throws the player ~5 up and ~10 away while an edge hit barely nudges them.
     */
    private void scheduleLaunch(Player p, Location center, double d) {
        double f = (EXPLODE_OUTER_RADIUS - d) / (EXPLODE_OUTER_RADIUS - EXPLODE_FULL_RADIUS);
        if (f > 1.0) f = 1.0; else if (f < 0.0) f = 0.0;
        final double scale = f;

        Vector away = p.getLocation().toVector().subtract(center.toVector());
        away.setY(0);
        if (away.lengthSquared() > 1.0e-6) away.normalize().multiply(KB_MAX_HORIZONTAL * scale);
        else away = new Vector(0, 0, 0);                    // directly above/below → straight up
        final Vector launch = new Vector(away.getX(), KB_MAX_UP * scale, away.getZ());

        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isValid() && !p.isDead()) p.setVelocity(launch); });
    }

    /** True if a solid block sits on the straight line between the blast centre and the victim's eyes. */
    private boolean shielded(Location center, LivingEntity victim) {
        Location eye = victim.getEyeLocation();
        Vector dir = eye.toVector().subtract(center.toVector());
        double dist = dir.length();
        if (dist < 1.0e-3) return false;
        RayTraceResult r = center.getWorld().rayTraceBlocks(
                center, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return r != null && r.getHitBlock() != null;
    }

    /**
     * Blast origin. Prefer the concrete source object's location (we KNOW the crystal entity / the
     * exploded block IS the centre — the captured {@link BlockState} carries the location even after
     * the block has turned to air), and only fall back to the {@link DamageSource}'s reported source
     * location, which for the anchor may be absent.
     */
    private Location explosionCenter(EntityDamageEvent e, LivingEntity victim) {
        if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager() instanceof EnderCrystal c) {
            return c.getLocation().add(0, 0.5, 0);
        }
        if (e instanceof EntityDamageByBlockEvent be) {
            BlockState bs = be.getDamagerBlockState();
            if (bs != null) return bs.getLocation().add(0.5, 0.5, 0.5);
            if (be.getDamager() != null) return be.getDamager().getLocation().add(0.5, 0.5, 0.5);
        }
        DamageSource ds = e.getDamageSource();
        if (ds != null) {
            Location src = ds.getSourceLocation();
            if (src != null) return src;
        }
        return null;
    }

    // -- weapon curves --------------------------------------------------------

    static double maceCurve(double raw) {
        if (raw <= MACE_KNEE) return raw;
        if (raw >= MACE_VANILLA_AT_CAP) return MACE_CAP;
        return MACE_KNEE + (raw - MACE_KNEE) * (MACE_CAP - MACE_KNEE) / (MACE_VANILLA_AT_CAP - MACE_KNEE);
    }

    static double spearCurve(double raw) {
        if (raw <= SPEAR_KNEE) return raw;
        if (raw >= SPEAR_VANILLA_AT_CAP) return SPEAR_CAP;
        return SPEAR_KNEE + (raw - SPEAR_KNEE) * (SPEAR_CAP - SPEAR_KNEE) / (SPEAR_VANILLA_AT_CAP - SPEAR_KNEE);
    }

    // -- helpers --------------------------------------------------------------

    private static Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
