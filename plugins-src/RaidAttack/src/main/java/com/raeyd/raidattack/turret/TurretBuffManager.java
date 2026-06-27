package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Resistance I aura for claim members standing near any of their (or a friendly claim's)
 * turrets. Re-applied every second for 10 s — players inside the aura always have at least a
 * full duration on the buff; when they walk out, the timer drains naturally and they keep
 * Resistance for ~10 s of residual effect.
 *
 * <p>Aura radius is {@link TurretCombatTuning#BUFF_AURA_RADIUS} (10 blocks) measured from each
 * turret's layer-5 centre (= NPC position). One buff per player per tick is enough — multiple
 * turrets covering the same player don't stack the duration beyond the refresh interval.
 */
public final class TurretBuffManager {

    private static final long TICK_INTERVAL = 20L;     // 1 s
    private static final double AURA_RADIUS_SQ =
            TurretCombatTuning.BUFF_AURA_RADIUS * TurretCombatTuning.BUFF_AURA_RADIUS;

    private final HomeSystemPlugin plugin;
    private BukkitTask task;

    public TurretBuffManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (insideAnyFriendlyTurretAura(p)) {
                p.addPotionEffect(new PotionEffect(
                        PotionEffectType.RESISTANCE,
                        TurretCombatTuning.BUFF_AURA_TICKS,
                        0,         // amplifier 0 = Resistance I
                        true, true, true));
            }
        }
    }

    private boolean insideAnyFriendlyTurretAura(Player p) {
        Location pl = p.getLocation();
        World pw = pl.getWorld();
        if (pw == null) return false;
        java.util.UUID id = p.getUniqueId();
        for (Claim claim : plugin.getClaimManager().all().values()) {
            // Player must be a member of the claim whose turret is buffing them.
            if (!claim.isMember(id)) continue;
            if (!claim.getWorldId().equals(pw.getUID())) continue;
            for (Turret t : claim.getTurrets()) {
                double dx = (t.getX() + 0.5) - pl.getX();
                double dy = (t.getY() + 4)   - pl.getY();
                double dz = (t.getZ() + 0.5) - pl.getZ();
                if (dx * dx + dy * dy + dz * dz <= AURA_RADIUS_SQ) return true;
            }
        }
        return false;
    }
}
