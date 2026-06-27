package com.raeyd.raidattack.alliance;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Cancels any damage event where the attacker and victim are members of the same alliance.
 * Covers:
 *
 * <ul>
 *   <li>Direct melee (damager is the other player).</li>
 *   <li>Arrows, snowballs, eggs, tridents, etc. (damager is a {@link Projectile}; we resolve
 *       its shooter via {@link Projectile#getShooter()}).</li>
 *   <li>TNT, end-crystal, and any other entity explosion whose Bukkit damager is the source
 *       entity rather than the player — only filtered if we can trace ownership back to a
 *       player. For TNT specifically, Paper exposes the primer's source via the damager being
 *       the TNTPrimed; we don't bother chasing that — explosives are rare in alliance griefing.</li>
 * </ul>
 *
 * <p>We do <b>not</b> touch turret bullets. The user spec is explicit: turrets keep firing at
 * alliance members until manually added to the claim's friends list (or auto-added via
 * {@code /HomeSystem friends self_alliance}). That decision lives in the home-system code, not
 * here.
 */
public final class AlliancePvpListener implements Listener {

    private final HomeSystemPlugin plugin;

    public AlliancePvpListener(HomeSystemPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolveAttacker(e.getDamager());
        if (attacker == null) return;
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return;     // self-damage — ignore

        AllianceManager mgr = plugin.getAllianceManager();
        Alliance va = mgr.getOf(victim.getUniqueId());
        Alliance aa = mgr.getOf(attacker.getUniqueId());
        if (va != null && va == aa) {
            // Same alliance — protect.
            e.setCancelled(true);
        }
    }

    /** Trace an EntityDamageByEntityEvent damager back to the player who's actually responsible. */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
