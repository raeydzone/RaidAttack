package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRemoveEvent;

/**
 * Resurrects bullets that vanilla self-discards.
 *
 * <p>Why this exists: vanilla's {@code ShulkerBullet.onHit()} unconditionally calls
 * {@code discard()} after firing {@link org.bukkit.event.entity.ProjectileHitEvent} — cancelling
 * the event suppresses damage/levitation but NOT the despawn. {@link NmsReflection#setNoPhysics}
 * doesn't help either, because shulker-bullet collision is a manual raycast inside the entity's
 * own tick rather than a physics interaction.
 *
 * <p>So we listen at MONITOR (after vanilla's discard is committed) and, if the bullet was one
 * of ours and we hadn't explicitly marked it for cleanup (i.e. we hit target / HP zero /
 * lifetime), schedule a same-tick respawn via {@link TurretCombatManager#respawnAt} preserving
 * all projectile state.
 */
public final class TurretBulletRemoveListener implements Listener {

    private final HomeSystemPlugin plugin;

    public TurretBulletRemoveListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof ShulkerBullet bullet)) return;
        if (!bullet.hasMetadata(TurretCombatManager.BULLET_META)) return;

        TurretProjectile proj = plugin.getTurretCombat().getProjectile(bullet.getUniqueId());
        if (proj == null) return;
        if (proj.markedForCleanup) return;   // our own explicit removal, leave alone

        // Capture position now (entity is being removed; getLocation works during the event).
        Location at = bullet.getLocation();
        TurretProjectile capture = proj;
        // Respawn next tick — can't spawn entities while inside a removal event.
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getTurretCombat().respawnAt(capture, at));
    }
}
