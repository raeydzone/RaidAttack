package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Block;
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

/**
 * Pure-observation console logger for combat damage, added to gather balancing data — it never
 * mutates the event. Logs one line whenever a player <b>deals</b> damage or <b>takes</b> combat
 * damage, capturing final/raw amount, source, held weapon, cause and the victim's HP swing.
 *
 * <p>We're specifically watching a few high-burst sources flagged for possible nerfs — the
 * {@code MACE}, the new spear item, End Crystal explosions and Respawn Anchor explosions — so the
 * line carries a {@code {TAG}} when one of those is recognised. The Respawn Anchor blast is a
 * partially-unregistered custom-style explosion (no default death attribution); it usually surfaces
 * as a bare {@code BLOCK_EXPLOSION} with no surviving block, so we tag that case too.
 *
 * <p>Read-only at {@link EventPriority#MONITOR} (and NOT {@code ignoreCancelled}) so we see the
 * final post-mitigation numbers and also note when another system cancelled the hit. Pure
 * environmental self-damage (fall, fire, drowning, …) is filtered out unless a player dealt it.
 */
public final class DamageTrackingListener implements Listener {

    /** Environmental causes we don't care about for combat balancing (suppressed to cut spam). */
    private static final Set<String> ENV_NOISE = Set.of(
            "FALL", "FIRE", "FIRE_TICK", "LAVA", "DROWNING", "STARVATION", "SUFFOCATION",
            "FREEZE", "VOID", "HOT_FLOOR", "CRAMMING", "DRYOUT", "CONTACT", "WITHER",
            "POISON", "FLY_INTO_WALL", "FALLING_BLOCK", "LIGHTNING", "CAMPFIRE");

    private final HomeSystemPlugin plugin;
    private final Logger log;

    public DamageTrackingListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent ev) {
        Entity victim = ev.getEntity();
        boolean victimIsPlayer = victim instanceof Player;
        Player attacker = resolveAttacker(ev);

        // Only log player-involved combat: a player dealt it, or a player took it.
        if (attacker == null && !victimIsPlayer) return;
        // When no player is dealing the damage, ignore pure environmental self-harm.
        if (attacker == null && ENV_NOISE.contains(ev.getCause().name())) return;

        String victimName = victimIsPlayer ? ((Player) victim).getName() : victim.getType().name();
        String source = describeSource(ev, attacker);
        String tag = classify(ev, attacker);

        StringBuilder sb = new StringBuilder("[DmgTrack] ");
        sb.append(victimName)
          .append(" <- ").append(fmt(ev.getFinalDamage()))
          .append(" (raw ").append(fmt(ev.getDamage())).append(')')
          .append(" from ").append(source)
          .append(" [cause=").append(ev.getCause().name()).append(']');
        if (victim instanceof LivingEntity le) {
            double hp = le.getHealth();
            sb.append(" hp ").append(fmt(hp)).append("->").append(fmt(Math.max(0.0, hp - ev.getFinalDamage())));
        }
        if (tag != null) sb.append(" {").append(tag).append('}');
        if (ev.isCancelled()) sb.append(" (CANCELLED)");
        log.info(sb.toString());
    }

    // -- helpers --------------------------------------------------------------

    /** The player ultimately responsible for this damage (direct hit or projectile shooter), or null. */
    private Player resolveAttacker(EntityDamageEvent ev) {
        if (!(ev instanceof EntityDamageByEntityEvent ee)) return null;
        Entity dmg = ee.getDamager();
        if (dmg instanceof Player p) return p;
        if (dmg instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    /** Human-readable description of what dealt the damage, always including the held weapon. */
    private String describeSource(EntityDamageEvent ev, Player attacker) {
        if (ev instanceof EntityDamageByEntityEvent ee) {
            Entity dmg = ee.getDamager();
            if (attacker != null) {
                String held = attacker.getInventory().getItemInMainHand().getType().name();
                if (dmg instanceof Projectile) {
                    return attacker.getName() + " via " + dmg.getType().name() + " (held " + held + ')';
                }
                return attacker.getName() + " w/ " + held;
            }
            return dmg.getType().name();                 // mob / crystal / non-player source
        }
        if (ev instanceof EntityDamageByBlockEvent be) {
            Block b = be.getDamager();
            return "block " + (b == null ? "?" : b.getType().name());
        }
        return "environment";
    }

    /** Tag the watched high-burst sources, or null if this hit isn't one of them. */
    private String classify(EntityDamageEvent ev, Player attacker) {
        if (attacker != null) {
            Material held = attacker.getInventory().getItemInMainHand().getType();
            if (held == Material.MACE) return "MACE";
            if (held.name().contains("SPEAR")) return "SPEAR";
        }
        if (ev instanceof EntityDamageByEntityEvent ee) {
            Entity dmg = ee.getDamager();
            if (dmg instanceof EnderCrystal) return "END_CRYSTAL";
            // A thrown spear arrives as a projectile; tag by its entity type name.
            if (dmg.getType().name().contains("SPEAR")) return "SPEAR";
        }
        if (ev instanceof EntityDamageByBlockEvent be) {
            Block b = be.getDamager();
            if (b != null && b.getType() == Material.RESPAWN_ANCHOR) return "RESPAWN_ANCHOR";
        }
        // Respawn-anchor blasts usually arrive as a bare BLOCK_EXPLOSION with the block already gone.
        if (ev.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return "BLOCK_EXPLOSION(anchor?)";
        return null;
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
