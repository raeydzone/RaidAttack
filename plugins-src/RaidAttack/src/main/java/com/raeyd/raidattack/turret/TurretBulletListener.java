package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Bridges Bukkit events to {@link TurretCombatManager} for the ShulkerBullet entities we use as
 * projectile visuals:
 *
 * <ul>
 *   <li>Vanilla damage that our bullets <i>deal</i> is cancelled — we apply damage manually
 *       in the combat manager on hit.</li>
 *   <li>Damage taken <i>by</i> our bullets is captured, rerouted through
 *       {@link TurretCombatManager#applyDamageTo(java.util.UUID, double)} so each bullet has a
 *       30-HP pool against player melee / arrows, then the vanilla deletion is cancelled.</li>
 *   <li>Levitation effects from any ATTACK source are suppressed (the only vanilla emitter is
 *       a shulker bullet and we want it gone).</li>
 *   <li>Any {@code ProjectileHitEvent} on our bullets that slips through is handled by
 *       dropping the tracker and removing the entity.</li>
 * </ul>
 */
public final class TurretBulletListener implements Listener {

    private final HomeSystemPlugin plugin;

    public TurretBulletListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Damage TAKEN by our bullet (player swung at it, arrow hit it, …). Cancel vanilla so the
     * bullet isn't removed instantly, then route through the HP tracker.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBulletTakeDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof ShulkerBullet bullet)) return;
        if (!bullet.hasMetadata(TurretCombatManager.BULLET_META)) return;
        // EntityDamageByEntityEvent (a subclass) is fired before this; that's where the
        // damage-dealt suppression below lives. For *incoming* damage we always cancel vanilla
        // and route through our HP tracker.
        e.setCancelled(true);
        // Our bullets are FIRE-PROOF: lava / fire / magma / lightning must not chip the HP pool.
        // Routing those to applyDamageTo() made bullets "burn out" and respawn (with the
        // extinguish sound) the moment they flew over a lava-moat turret. Only real combat damage
        // (player melee / arrows) counts against a bullet's HP.
        switch (e.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR, MELTING, CAMPFIRE, LIGHTNING -> { return; }
            default -> { }
        }
        plugin.getTurretCombat().applyDamageTo(bullet.getUniqueId(), e.getDamage());
    }

    /** Keep bullets from catching fire over lava/fire at all — no flames, no ignition sound. */
    @EventHandler(ignoreCancelled = true)
    public void onBulletCombust(EntityCombustEvent e) {
        if (!(e.getEntity() instanceof ShulkerBullet bullet)) return;
        if (!bullet.hasMetadata(TurretCombatManager.BULLET_META)) return;
        e.setCancelled(true);
    }

    /**
     * Damage DEALT by our bullet to another entity. Cancel — the combat manager already
     * applied the level-tuned damage on hit.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBulletDealDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof ShulkerBullet bullet)) return;
        if (!bullet.hasMetadata(TurretCombatManager.BULLET_META)) return;
        e.setCancelled(true);
    }

    /** Drop vanilla Levitation. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionApply(EntityPotionEffectEvent e) {
        if (e.getNewEffect() == null) return;
        if (e.getNewEffect().getType() != PotionEffectType.LEVITATION) return;
        if (e.getCause() != EntityPotionEffectEvent.Cause.ATTACK) return;
        if (!(e.getEntity() instanceof LivingEntity)) return;
        e.setCancelled(true);
    }

    /**
     * Vanilla fires this when its own collision pass notices our teleport-controlled bullet
     * overlapping a block (e.g. brushing the turret structure on the way out). Cancelling
     * prevents vanilla from despawning the entity — our combat manager is the sole authority
     * on when a bullet dies (HP, lifetime, or its own pathfind-collision).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof ShulkerBullet bullet)) return;
        if (!bullet.hasMetadata(TurretCombatManager.BULLET_META)) return;
        e.setCancelled(true);
    }
}
