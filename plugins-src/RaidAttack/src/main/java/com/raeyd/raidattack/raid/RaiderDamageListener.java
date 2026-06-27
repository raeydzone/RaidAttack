package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Two related responsibilities for raid NPCs:
 * <ul>
 *   <li><b>Friendly-fire immunity.</b> When a player damages a raider/ravager belonging to a
 *       raid whose attacker is themselves, in their /hs claim friends list, or in their
 *       alliance, cancel the damage. Per spec: "the attacker (alongside its friend and
 *       alliance) cannot attack any of their raiders". Handles both melee and projectile
 *       (bow / crossbow / trident — resolves the shooter via {@link Projectile#getShooter}).</li>
 *   <li><b>Death bookkeeping.</b> When a raid NPC dies, decrement the owning raid's alive
 *       counter via {@link RaidManager#noteDied}. RaidManager itself triggers the raid-end
 *       transition once the counter hits zero with no spawns remaining.</li>
 * </ul>
 *
 * <p>Identifying which raid a damaged entity belongs to: every {@link CustomRaider} /
 * {@link CustomRavager} stamps {@code raid-id} onto its Citizens NPC data() at spawn time.
 * We read it back here without going through the Citizens API — the {@link RaidEntityManager}
 * already indexes wrappers by entity {@link UUID}, which is the cheap path.
 */
public final class RaiderDamageListener implements Listener {

    private final HomeSystemPlugin plugin;

    public RaiderDamageListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Raid NPCs are immune to fall damage. They constantly get teleported around (perimeter
     * spawn, stuck-fallback) and the random standable Y may be on top of a tree or a 30-block
     * cliff edge — without this the entire cohort would casually one-shot itself within seconds
     * of any teleport that landed above terrain. Cancelling the fall cause is the cleanest fix;
     * the AI still cares about path elevation, this just stops vanilla from hurting them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent ev) {
        if (ev.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (raidIdOf(ev.getEntity()) == null) return;
        ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent ev) {
        UUID raidId = raidIdOf(ev.getEntity());
        if (raidId == null) return;

        ActiveRaid raid = plugin.getRaidManager().getRaid(raidId);
        if (raid == null) return;

        Player damager = resolvePlayerSource(ev.getDamager());
        if (damager == null) return;

        // Cancel only if the damager is an ALLY of the raiders (attacker-side and NOT also defending
        // this base). isRaiderAlly is the shared authority — the raider TARGETING code in
        // RaidSpawnEngine uses the exact same predicate, so "they won't attack me" and "I can't hit
        // them" can never disagree. Defender side (owner + claim friends + alliance) is never an
        // ally, so a defender can always fight the raiders attacking their base even if they happen
        // to be friended/allied to the attacker (e.g. the dev-base tester).
        if (plugin.getRaidManager().isRaiderAlly(raid, damager.getUniqueId())) {
            ev.setCancelled(true);
        }
    }

    /**
     * A raid NPC just died. Three responsibilities:
     * <ol>
     *   <li>Decrement the owning raid's alive counter via {@link RaidManager#noteDied}.</li>
     *   <li>Drop the {@link RaidEntityManager} wrapper so the periodic sweep doesn't keep
     *       iterating over a dead reference.</li>
     *   <li>Clear drops + force-remove the entity so the body disappears instantly rather
     *       than playing the ~1-second vanilla death animation. Raiders are not real mobs —
     *       they shouldn't drop items or leave a corpse.</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent ev) {
        UUID entityId = ev.getEntity().getUniqueId();
        UUID raidId = raidIdOf(ev.getEntity());
        if (raidId == null) return;
        ActiveRaid raid = plugin.getRaidManager().getRaid(raidId);
        if (raid != null) {
            // Per-raid raider-kill counter. Used to gate the +1000 XP defender-side reward —
            // friends/alliance members only earn the bonus if they personally killed ≥10 raiders
            // (owner gets it unconditionally on raid defeat). EntityDeathEvent.getEntity()
            // .getKiller() is the Player that landed the killing blow (or null for env deaths).
            Player killer = ev.getEntity().getKiller();
            if (killer != null && isDefenderSide(raid, killer.getUniqueId())) {
                raid.incrementRaiderKill(killer.getUniqueId());
            }
            plugin.getRaidManager().noteDied(raid);
        }
        RaidEntityManager rem = plugin.getRaidEntities();
        if (rem != null) {
            CustomRaider r = rem.getRaider(entityId);
            if (r != null) rem.destroy(r);
            CustomRavager rv = rem.getRavager(entityId);
            if (rv != null) rem.destroy(rv);
        }
        // No item loot — raiders aren't natural mobs — but they DO drop a little XP on death.
        // The heavier "ravager" type drops more than a standard raider. We spawn a real orb at the
        // death location rather than via setDroppedExp(), because we force-remove the body below
        // (which would otherwise swallow the engine's pending XP drop).
        ev.getDrops().clear();
        ev.setDroppedExp(0);
        int xp = ev.getEntity().getType() == EntityType.RAVAGER
                ? java.util.concurrent.ThreadLocalRandom.current().nextInt(4, 9)    // ravager: 4–8
                : java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 3);   // raider:  1–2
        org.bukkit.Location deathLoc = ev.getEntity().getLocation();
        org.bukkit.World deathWorld = deathLoc.getWorld();
        if (deathWorld != null && xp > 0) {
            deathWorld.spawn(deathLoc, org.bukkit.entity.ExperienceOrb.class, o -> o.setExperience(xp));
        }
        // Force-remove the entity so the body skips the death animation. npc.destroy() above
        // schedules removal at end-of-tick; calling entity.remove() makes it gone this tick.
        try { ev.getEntity().remove(); } catch (Throwable ignored) {}
    }

    /**
     * Track damage between players during a raid. Specifically: when an attacker-side player
     * damages a defender-side player AND both are inside the raid's claim, accumulate the
     * damage in ActiveRaid.damageToDefendersByAttackerSide. Used to gate attacker-friend /
     * attacker-ally XP eligibility (must have dealt ≥25 damage to earn a share of bonus XP).
     *
     * <p>Listener priority MONITOR + ignoreCancelled=true: we read the final-damage value after
     * every other plugin has had its say. Doesn't mutate the event.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent ev) {
        if (!(ev.getEntity() instanceof Player victim)) return;
        Player damager = resolvePlayerSource(ev.getDamager());
        if (damager == null || damager.equals(victim)) return;
        // Walk active raids to see if this damage hit inside any raided zone with the right sides.
        for (ActiveRaid raid : plugin.getRaidManager().allActive()) {
            if (raid.getPhase() == ActiveRaid.Phase.ENDED) continue;
            if (!isAttackerSide(raid, damager.getUniqueId())) continue;
            if (!isDefenderSide(raid, victim.getUniqueId())) continue;
            Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
            if (zone == null || !zone.contains(damager.getLocation())) continue;
            raid.addDamageToDefenders(damager.getUniqueId(), ev.getFinalDamage());
            return;     // a player can only be in one raided zone at a time
        }
    }

    /**
     * Track defender-side deaths for the "made it to the end" bonus: any player who dies during an
     * active raid they're defending is dropped from that raid's survival bonus. RaidManager filters
     * to defender-side players, so attacker deaths and NPC (player-type raider) deaths are ignored.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent ev) {
        if (plugin.getRaidManager() == null) return;
        plugin.getRaidManager().markDefenderDeath(ev.getEntity().getUniqueId());
    }

    /** Attacker-side = attacker UUID, or anyone friendly to the attacker per RaidManager rules. */
    private boolean isAttackerSide(ActiveRaid raid, UUID playerId) {
        return plugin.getRaidManager().isFriendlyToAttacker(raid.getAttackerId(), playerId);
    }

    /** Defender-side = owner, owner's claim friends, or owner's alliance members.
     *  Delegates to {@link RaidManager#isDefenderSide} so there's one definition of "defender". */
    private boolean isDefenderSide(ActiveRaid raid, UUID playerId) {
        return plugin.getRaidManager().isDefenderSide(raid, playerId);
    }

    // -- helpers --------------------------------------------------------------

    /**
     * Read the raid id stamped on this entity's Citizens NPC, or null if the entity isn't one
     * of our raiders. We pull from {@link RaidEntityManager} first (HashMap lookup, no Citizens
     * iteration) and only fall through to the data-tag path if the wrapper has been pruned but
     * the entity hasn't died yet.
     */
    private UUID raidIdOf(Entity e) {
        if (e == null) return null;
        RaidEntityManager rem = plugin.getRaidEntities();
        if (rem != null) {
            CustomRaider r = rem.getRaider(e.getUniqueId());
            if (r != null) return r.getRaidId();
            CustomRavager rv = rem.getRavager(e.getUniqueId());
            if (rv != null) return rv.getRaidId();
        }
        // Fallback: read the raid-id NPC data tag direct from Citizens. Only hits in the
        // narrow window where the wrapper has been pruned but the entity still exists (e.g.
        // sweep race). Citizens API is heavier than the map lookup so it's worth the split.
        if (e.getType() != EntityType.PLAYER && e.getType() != EntityType.RAVAGER) return null;
        try {
            net.citizensnpcs.api.npc.NPC npc =
                    net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(e);
            if (npc == null) return null;
            Object raw = npc.data().get("raid-id");
            if (raw == null) return null;
            return UUID.fromString(raw.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolve a damager Entity → the underlying Player source, or null if non-player. */
    private static Player resolvePlayerSource(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        return null;
    }
}
