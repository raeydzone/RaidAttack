package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.claim.ZoneListener;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tracks which players are "active enemies" of which claims. A player becomes an active enemy
 * of a claim the moment they're inside it (and aren't a member / bypassed) and stays one for
 * {@link #ENEMY_EXPIRY_MS} milliseconds after they were last seen inside.
 *
 * <p>This gates {@link TurretCombatManager}'s target acquisition: a turret will not shoot a
 * passer-by who never crossed into the claim, but it will pursue someone who briefly poked in
 * and then ran out — for one minute.
 *
 * <p>Refreshed by a 1 Hz tick that re-stamps every online player currently inside a foreign
 * claim. 1 s resolution is enough; if you want sub-second precision later, also hook
 * {@link ZoneListener#update}.
 */
public final class TurretEnemyTracker {

    /** Stay an active enemy for this many ms after last being seen inside the claim. */
    public static final long ENEMY_EXPIRY_MS = 60_000L;
    private static final long TICK_INTERVAL = 20L;  // 1 s

    private final HomeSystemPlugin plugin;
    /** claim-owner UUID → player UUID → last-seen-inside epoch-ms. */
    private final Map<UUID, Map<UUID, Long>> lastSeenInside = new HashMap<>();
    private BukkitTask task;

    public TurretEnemyTracker(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        }
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        lastSeenInside.clear();
    }

    /**
     * Eligibility test for player targeting. Bypassed players are never active enemies.
     */
    public boolean isActiveEnemy(UUID claimOwner, Player player) {
        if (player.hasPermission("homesystem.bypass")) return false;
        Map<UUID, Long> per = lastSeenInside.get(claimOwner);
        if (per == null) return false;
        Long t = per.get(player.getUniqueId());
        if (t == null) return false;
        return System.currentTimeMillis() - t < ENEMY_EXPIRY_MS;
    }

    /** Force-tag a player as an active enemy right now (used by ZoneListener on entry). */
    public void note(UUID claimOwner, UUID playerId) {
        lastSeenInside.computeIfAbsent(claimOwner, k -> new HashMap<>())
                .put(playerId, System.currentTimeMillis());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        // Re-stamp every online non-member currently inside a claim.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("homesystem.bypass")) continue;
            Claim claim = plugin.getClaimManager().getClaimAt(p.getLocation());
            if (claim == null) continue;
            if (claim.isMember(p.getUniqueId())) continue;
            lastSeenInside.computeIfAbsent(claim.getOwner(), k -> new HashMap<>())
                    .put(p.getUniqueId(), now);
        }
        // Garbage-collect long-expired entries so the map doesn't grow forever.
        long cutoff = now - ENEMY_EXPIRY_MS;
        for (Iterator<Map.Entry<UUID, Map<UUID, Long>>> it = lastSeenInside.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Long>> entry = it.next();
            entry.getValue().entrySet().removeIf(e -> e.getValue() < cutoff);
            if (entry.getValue().isEmpty()) it.remove();
        }
    }
}
