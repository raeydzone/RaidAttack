package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.quest.Quest;
import com.raeyd.raidattack.turret.Turret;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Owns the in-memory + on-disk state for every {@link ActiveRaid}. Single source of truth for:
 * <ul>
 *   <li><b>Existence:</b> "is zone X currently being raided?" → {@link #getRaidOnZone(UUID)}.</li>
 *   <li><b>Cost-gated start:</b> {@link #startRaid} — units already validated/paid by the
 *       caller; this just stamps the raid into state and persists.</li>
 *   <li><b>Counter updates:</b> spawn/death events go through here so the file stays current
 *       and downstream observers (boss bar, GUI) see consistent numbers.</li>
 *   <li><b>Crash recovery:</b> on {@link #load()}, restores every saved raid and exposes them
 *       so the spawn engine can re-spawn the cohort the player was fighting before the crash.</li>
 *   <li><b>Friendly-fire query:</b> {@link #isFriendlyToAttacker} — encapsulates the rule that
 *       the attacker + their /hs claim friends + their alliance can't damage their own raid.</li>
 * </ul>
 *
 * <p>Persistence file: {@code plugins/RaidAttack/raids.yml}. We save on every mutation —
 * each raid is ~12 string-keyed scalars, file size is ~1 KB per raid, the IO cost is
 * negligible compared to the network/world IO Paper already does each tick.
 */
public final class RaidManager {

    /** Pre-spawn delay before the first raider appears, randomly picked in this range per raid.
     *  Updated per user spec: "randomly between 90 and 180 seconds, on each time u get raided
     *  its RANDOM, not always 75s". */
    public static final long PRE_SPAWN_DELAY_MIN_MS =  90_000L;
    public static final long PRE_SPAWN_DELAY_MAX_MS = 180_000L;

    /** Once a zone has been raided, it can't be raided again until this long has elapsed.
     *  Per spec: 36 hours. Stamped at raid start (see {@link #stampZoneCooldown}); the stamp
     *  is persisted so it survives a restart. Dev-mode raids deliberately skip the stamp/check
     *  (handled at the call site) so the zone can be re-raided immediately for testing. */
    public static final long RAID_COOLDOWN_MS = 36L * 60L * 60L * 1000L;

    private final HomeSystemPlugin plugin;
    /** Active raids are RUNTIME-only — never persisted (the plugin wipes them on every enable). */
    private final Map<UUID, ActiveRaid> raidsById = new HashMap<>();
    /** zoneOwnerId → epoch-ms when this zone becomes raidable again. Persisted to
     *  {@code world.raid_cooldowns}; entries past their deadline are pruned lazily on read. */
    private final Map<UUID, Long> zoneCooldownUntil = new HashMap<>();

    public RaidManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    // -- lifecycle ------------------------------------------------------------

    public void load() {
        raidsById.clear();   // active raids are never restored (ephemeral by design)
        zoneCooldownUntil.clear();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Long> e : plugin.getWorldDatabase().loadRaidCooldowns().entrySet()) {
            if (e.getValue() > now) zoneCooldownUntil.put(e.getKey(), e.getValue());   // drop expired
        }
        plugin.getLogger().info("Loaded 0 active raid(s) (ephemeral), "
                + zoneCooldownUntil.size() + " zone cooldown(s).");
    }

    /** Persist cooldowns (active-raid state is not persisted). Async full-replace — the set is tiny. */
    public void save() {
        Map<UUID, Long> snap = new HashMap<>(zoneCooldownUntil);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getWorldDatabase().saveRaidCooldowns(snap));
    }

    /** Synchronous cooldown flush, for server shutdown (so an in-flight async write isn't lost). */
    public void flush() {
        plugin.getWorldDatabase().saveRaidCooldowns(new HashMap<>(zoneCooldownUntil));
    }

    // -- queries --------------------------------------------------------------

    public ActiveRaid getRaid(UUID raidId) { return raidsById.get(raidId); }

    public Collection<ActiveRaid> allActive() { return raidsById.values(); }

    /**
     * The single raid currently targeting this zone owner, or null if none. We enforce
     * "one raid per zone at a time" at start time, so this is at most one entry.
     */
    public ActiveRaid getRaidOnZone(UUID zoneOwnerId) {
        for (ActiveRaid r : raidsById.values()) {
            if (r.getPhase() == ActiveRaid.Phase.ENDED) continue;
            if (r.getZoneOwnerId().equals(zoneOwnerId)) return r;
        }
        return null;
    }

    /** Every raid started by this attacker that's still in flight. Used for "/raid status". */
    public List<ActiveRaid> raidsByAttacker(UUID attackerId) {
        List<ActiveRaid> out = new ArrayList<>();
        for (ActiveRaid r : raidsById.values()) {
            if (r.getPhase() == ActiveRaid.Phase.ENDED) continue;
            if (r.getAttackerId().equals(attackerId)) out.add(r);
        }
        return out;
    }

    // -- lifecycle: start / end -----------------------------------------------

    /**
     * Stamp a new raid into state. The caller has already validated that the attacker can
     * afford the units, paid them, and confirmed the zone isn't already being raided. The
     * pre-spawn delay endpoint is chosen here (60–120 s from now) so the file stores it once
     * — no drift on restart. Returns the new raid's id.
     */
    public ActiveRaid startRaid(UUID attackerId, String attackerName, UUID zoneOwnerId,
                                UUID worldId, int units) {
        long now = System.currentTimeMillis();
        long delay = PRE_SPAWN_DELAY_MIN_MS
                + ThreadLocalRandom.current().nextLong(PRE_SPAWN_DELAY_MAX_MS - PRE_SPAWN_DELAY_MIN_MS + 1);
        long delayUntil = now + delay;
        ActiveRaid raid = ActiveRaid.create(attackerId, attackerName, zoneOwnerId,
                worldId, units, now, delayUntil);
        raidsById.put(raid.getRaidId(), raid);
        save();
        if (plugin.getQuests() != null) plugin.getQuests().add(attackerId, Quest.START_RAIDS, 1);
        plugin.getLogger().info("Raid " + shortId(raid.getRaidId())
                + " started: attacker=" + attackerName + " owner="
                + plugin.getClaimManager().resolveName(zoneOwnerId)
                + " units=" + units + " total=" + raid.getTotalToSpawn()
                + " goal=" + raid.getActiveGoal()
                + " pre-spawn-delay=" + (delay / 1000) + "s");
        return raid;
    }

    /**
     * Mark the raid complete and remove it from the active set. Also despawns every still-
     * standing NPC tagged to this raid (defensive — under normal flow the spawn engine
     * shouldn't have any alive if isComplete() returned true, but a forced-end from /unclaim
     * or admin command can leave them).
     */
    public void endRaid(UUID raidId, String reason) {
        ActiveRaid raid = raidsById.remove(raidId);
        if (raid == null) return;
        raid.setPhase(ActiveRaid.Phase.ENDED);
        int despawned = plugin.getRaidEntities() == null
                ? 0 : plugin.getRaidEntities().despawnByRaid(raidId);
        // Tear down the boss bar — every viewer's UI snaps clean at this point. Done before
        // logging so the user-visible UI updates ahead of any console spam from the cleanup.
        if (plugin.getRaidBossBars() != null) {
            plugin.getRaidBossBars().onRaidEnded(raidId);
        }
        // Defender-win condition: the raid ended because every raider died. Only that exact
        // reason qualifies — other end paths (zone unclaimed mid-raid, admin /raid wipe, etc.)
        // shouldn't pay XP because the defender didn't actually beat the cohort.
        if ("all raiders dead".equals(reason)) {
            awardDefenderWinBonus(raid);
        }
        plugin.getLogger().info("Raid " + shortId(raidId) + " ended (" + reason
                + ") — despawned " + despawned + " straggler(s).");
        save();
    }

    /**
     * Common attacker-side bonus payout. Recipients are:
     * <ul>
     *   <li><b>Attacker</b> — always, regardless of where they are.</li>
     *   <li><b>Attacker's claim friends + alliance</b> — only if currently inside the raided
     *       zone AND have dealt ≥25 cumulative damage to defender-side players this raid.</li>
     * </ul>
     * Each recipient gets {@code xp} experience points + a green Raid msg labelled with
     * {@code reasonLabel} ("Destroy All 4 Turret Bonus", "Sustain 15 min Bonus", etc.) +
     * a positive sound cue.
     */
    public void awardAttackerSideBonus(ActiveRaid raid, int xp, String reasonLabel) {
        Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (zone == null) return;

        Set<UUID> recipients = new HashSet<>();
        recipients.add(raid.getAttackerId());

        // Attacker-side pool: their own claim friends + alliance members.
        Set<UUID> attackerSidePool = new HashSet<>();
        Claim attackerClaim = plugin.getClaimManager().getClaimOf(raid.getAttackerId());
        if (attackerClaim != null) attackerSidePool.addAll(attackerClaim.getFriends());
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(raid.getAttackerId());
            if (a != null) attackerSidePool.addAll(a.getMembers());
        }
        attackerSidePool.remove(raid.getAttackerId());      // dedup — attacker already added

        for (UUID id : attackerSidePool) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!zone.contains(p.getLocation())) continue;
            if (raid.damageDealtBy(id) < 25.0) continue;
            recipients.add(id);
        }

        boolean sustainBonus = reasonLabel != null && reasonLabel.startsWith("Sustain 15 min");
        for (UUID id : recipients) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.giveExp(xp);
            p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            RaidMessages.broadcastBonus(p.getName() + " earned the \"" + reasonLabel
                    + "\" bonus (+" + xp + " XP)!");
            // Quest: earning the 15-minute sustain bonus.
            if (sustainBonus && plugin.getQuests() != null) {
                plugin.getQuests().complete(id, Quest.LONG_RAID_BONUS);
            }
        }
        plugin.getLogger().info("Raid " + shortId(raid.getRaidId())
                + " — awarded " + reasonLabel + " (" + xp + " XP) to "
                + recipients.size() + " attacker-side player(s).");
    }

    /**
     * Defender-side reward pass when a raid is fully defeated. Two separate +500 XP bonuses, each
     * broadcast server-wide ([Raid] tag) so everyone sees who earned what:
     * <ul>
     *   <li><b>"Made it to the end"</b> — to eligible defenders who never died this raid. The owner
     *       is included in BOTH the eligibility and the survival gate: if the owner died, they miss
     *       it too (otherwise the bonus is meaningless for the one being attacked).</li>
     *   <li><b>"Keep a turret intact"</b> — to the whole eligible pool, only if all four turrets
     *       were never simultaneously down (we reuse the attacker "Successfully Raided" flag as the
     *       inverse).</li>
     * </ul>
     * Eligibility: owner always; friends/allies need ≥10 raider kills AND ≥50% of the raid spent
     * inside the zone.
     */
    private void awardDefenderWinBonus(ActiveRaid raid) {
        Claim claim = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        String ownerName = plugin.getClaimManager().resolveName(raid.getZoneOwnerId());
        String attackerName = raid.getAttackerName();

        Set<UUID> pool = defenderPool(raid, claim);

        // Universal "we defended" ping to every online defender — independent of the bonus gates.
        for (UUID id : pool) {
            if (plugin.getQuests() != null) plugin.getQuests().complete(id, Quest.PREVENT_RAID);
            Player p = Bukkit.getPlayer(id);
            if (p != null) RaidMessages.bonus(p,
                    "You successfully defended against " + attackerName + "'s raid!",
                    Sound.UI_TOAST_CHALLENGE_COMPLETE);
        }

        boolean turretSurvived = !raid.isAllTurretsBonusAwarded();   // a turret always stood
        for (UUID id : pool) {
            boolean isOwner = id.equals(raid.getZoneOwnerId());
            boolean eligible = isOwner
                    || (raid.raiderKillsBy(id) >= 10 && raid.spentEnoughTimeInZone(id));
            if (!eligible) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;       // offline — can't grant XP

            if (raid.defenderSurvived(id)) {
                p.giveExp(500);
                p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                RaidMessages.broadcastBonus(p.getName() + " made it to the end of "
                        + ownerName + "'s raid — +500 XP!");
            }
            if (turretSurvived) {
                p.giveExp(500);
                RaidMessages.broadcastBonus(p.getName() + " kept a turret standing through "
                        + ownerName + "'s raid — +500 XP!");
            }
        }
    }

    /** Defender pool = owner + claim friends + alliance members, deduped. */
    private Set<UUID> defenderPool(ActiveRaid raid, Claim claim) {
        Set<UUID> pool = new HashSet<>();
        pool.add(raid.getZoneOwnerId());
        if (claim != null) pool.addAll(claim.getFriends());
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(raid.getZoneOwnerId());
            if (a != null) pool.addAll(a.getMembers());
        }
        return pool;
    }

    private boolean isDefenderSideOf(ActiveRaid raid, UUID playerId) {
        if (raid.getZoneOwnerId().equals(playerId)) return true;
        Claim claim = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (claim != null && claim.getFriends().contains(playerId)) return true;
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(raid.getZoneOwnerId());
            if (a != null && a.getMembers().contains(playerId)) return true;
        }
        return false;
    }

    /**
     * Mark a player as having died during any active raid they're defending — drops them from that
     * raid's "made it to the end" bonus. Called from the player-death listener. Harmless for NPC
     * deaths (player-type raiders) since an NPC UUID is never owner/friend/alliance.
     */
    public void markDefenderDeath(UUID playerId) {
        if (playerId == null) return;
        for (ActiveRaid raid : raidsById.values()) {
            if (raid.getPhase() == ActiveRaid.Phase.ENDED) continue;
            if (isDefenderSideOf(raid, playerId)) raid.markDefenderDied(playerId);
        }
    }

    /**
     * Sample (every {@code seconds}) which defenders are inside the zone — feeds the ≥50%-time
     * bonus gate. Called from the loot ticker on its 10 s cadence. The owner is exempt from the
     * time gate, so we skip sampling them.
     */
    public void sampleDefenderPresence(ActiveRaid raid, Claim zone, int seconds) {
        if (raid == null || zone == null || seconds <= 0) return;
        raid.addRaidSampleSeconds(seconds);
        for (UUID id : defenderPool(raid, zone)) {
            if (id.equals(raid.getZoneOwnerId())) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (!p.getWorld().getUID().equals(zone.getWorldId())) continue;
            if (zone.contains(p.getLocation())) raid.addInZoneSeconds(id, seconds);
        }
    }

    /**
     * Hard-clear every active raid + persist the empty state. Used on plugin enable in dev
     * mode so a raid that was mid-flight when the server stopped doesn't come back with
     * mismatched state (raiders have been auditor-wiped, viewer-attach is finicky, etc.).
     * No notifications, no boss-bar teardown — caller is responsible for any UI cleanup.
     */
    public void wipeAllActiveRaids(String reason) {
        int n = raidsById.size();
        raidsById.clear();
        save();
        plugin.getLogger().info("Wiped " + n + " active raid(s) — reason: " + reason);
    }

    // -- counter mutations (call from spawn engine + death listener) ----------

    public void noteSpawned(ActiveRaid raid) {
        raid.noteSpawned();
        save();
    }

    public void noteDied(ActiveRaid raid) {
        raid.noteDied();
        // Auto-end if this was the last one and the spawn queue is empty.
        if (raid.isComplete()) {
            endRaid(raid.getRaidId(), "all raiders dead");
        } else {
            save();
            // Drip-spawn replacement: if the raid is still spawning and the cohort just dropped
            // below the active goal, tell the spawn engine to fire its next-spawn timer now
            // instead of waiting on whatever 1-3s deadline was sitting in the cadence map. This
            // is what makes the wave feel continuous — one death → one new raider within the
            // 1-3s cadence floor, rather than the previous "all 10 die, then 10 spawn" wave bug.
            RaidSpawnEngine engine = plugin.getRaidSpawnEngine();
            if (engine != null && raid.getPhase() == ActiveRaid.Phase.SPAWNING
                    && raid.remainingToSpawn() > 0) {
                engine.onRaiderDied(raid.getRaidId());
            }
        }
    }

    // -- cooldown + edit-lock -------------------------------------------------

    /**
     * Stamp this zone as freshly raided: it can't be raided again for {@link #RAID_COOLDOWN_MS}.
     * Called from the (non-dev) raid-start path. Persisted immediately so the cooldown survives
     * a restart.
     */
    public void stampZoneCooldown(UUID zoneOwnerId) {
        if (zoneOwnerId == null) return;
        zoneCooldownUntil.put(zoneOwnerId, System.currentTimeMillis() + RAID_COOLDOWN_MS);
        save();
    }

    /** Milliseconds left on this zone's raid cooldown, or 0 if none (expired entries pruned). */
    public long getZoneCooldownRemainingMs(UUID zoneOwnerId) {
        if (zoneOwnerId == null) return 0L;
        Long until = zoneCooldownUntil.get(zoneOwnerId);
        if (until == null) return 0L;
        long rem = until - System.currentTimeMillis();
        if (rem <= 0L) {
            zoneCooldownUntil.remove(zoneOwnerId);
            return 0L;
        }
        return rem;
    }

    /**
     * True iff {@code actor}'s claim friend list must be frozen right now because they're
     * entangled in an active raid — either as the <b>defender</b> (their zone is being raided)
     * or as an <b>attacker</b> (they started a raid that's still in flight). Per spec, while a
     * raid is happening neither side may add or remove base members.
     */
    public boolean isMemberEditLocked(UUID actor) {
        return isInActiveRaid(actor);
    }

    /**
     * True if this player is entangled in any active raid — either as the <b>defender</b> (their
     * zone is being raided) or as an <b>attacker</b> (they started a raid still in flight). Used to
     * freeze BOTH the friend list and alliance join/leave while the raid runs, so the friendly-fire
     * relationship that decides whether turrets shoot a side's raiders can't be gamed mid-raid.
     */
    public boolean isInActiveRaid(UUID actor) {
        if (actor == null) return false;
        if (getRaidOnZone(actor) != null) return true;       // defender: own zone under raid
        return !raidsByAttacker(actor).isEmpty();             // attacker: raiding someone
    }

    // -- friendly-fire helper -------------------------------------------------

    /**
     * True iff {@code damagerId} should NOT be able to damage raiders of the raid started by
     * {@code attackerId}. The friendly-fire rule per spec:
     * <ol>
     *   <li>The attacker themselves.</li>
     *   <li>Anyone the attacker has added as a friend on their own home claim (via
     *       {@code /hs add}).</li>
     *   <li>Anyone in the attacker's current alliance.</li>
     * </ol>
     * Order matters only for short-circuit; the predicates are independent.
     */
    public boolean isFriendlyToAttacker(UUID attackerId, UUID damagerId) {
        if (attackerId == null || damagerId == null) return false;
        if (attackerId.equals(damagerId)) return true;

        Claim attackerClaim = plugin.getClaimManager().getClaimOf(attackerId);
        if (attackerClaim != null && attackerClaim.getFriends().contains(damagerId)) return true;

        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance attackerAlliance = am.getOf(attackerId);
            Alliance damagerAlliance  = am.getOf(damagerId);
            if (attackerAlliance != null && attackerAlliance == damagerAlliance) return true;
        }
        return false;
    }

    /**
     * Defender side of a raid: the zone owner, the owner's /hs claim friends, and the owner's
     * alliance members — the players defending the raided base.
     */
    public boolean isDefenderSide(ActiveRaid raid, UUID playerId) {
        if (raid == null || playerId == null) return false;
        if (raid.getZoneOwnerId().equals(playerId)) return true;
        Claim claim = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (claim != null && claim.getFriends().contains(playerId)) return true;
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(raid.getZoneOwnerId());
            if (a != null && a.getMembers().contains(playerId)) return true;
        }
        return false;
    }

    /**
     * Single source of truth for "this player is an ALLY of the raiders": the raiders must neither
     * target them ({@link RaidSpawnEngine} acquisition) nor take damage from them
     * ({@link RaiderDamageListener} friendly-fire). True iff the player is on the ATTACKER's side
     * (the attacker + their claim friends + their alliance) AND is NOT also on the defender's side.
     *
     * <p><b>Defender membership always wins.</b> A player defending the raided base stays a valid
     * enemy of the raiders even when they also happen to be friended/allied to the attacker — e.g.
     * the tester who is friended into the dev base while their own base is the one under raid, or two
     * claim-friends who raid each other. Routing both the targeting AI and the damage gate through
     * this one predicate keeps "they won't attack me" and "I can't hit them" from ever disagreeing.
     */
    public boolean isRaiderAlly(ActiveRaid raid, UUID playerId) {
        if (raid == null || playerId == null) return false;
        if (isDefenderSide(raid, playerId)) return false;     // defender always an enemy of raiders
        return isFriendlyToAttacker(raid.getAttackerId(), playerId);
    }

    // -- internals ------------------------------------------------------------

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
}
