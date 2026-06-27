package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.turret.Turret;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One in-flight raid. Holds the immutable identity of the raid (attacker, zone owner, world,
 * units purchased) plus the mutable progress counters (spawned-so-far, currently alive, phase).
 *
 * <p><b>Scaling.</b> Active-attacker goal and total-spawn budget interpolate linearly between
 * 32 and 64 units, per the spec:
 * <pre>
 *   32 units → 9 active goal,  128 total spawns
 *   64 units → 12 active goal, 256 total spawns
 * </pre>
 * 1 extra unit = +0.09375 active goal and +4 total spawns. We round at evaluation time so a
 * 48-unit raid lands on 11 active / 192 total exactly.
 *
 * <p><b>Persistence.</b> Mutable counters are written back to {@code raids.yml} on every state
 * change so a server crash mid-raid recovers cleanly: spawned counter restores the queue
 * position, alive counter tells the load path how many to instant-spawn at the perimeter to
 * restore the cohort the player was already fighting.
 *
 * <p><b>Boss bar.</b> Runtime-only; not serialized. The boss bar is reconstructed on load from
 * the persistent state, so a crash doesn't leak a ghost progress bar.
 */
public final class ActiveRaid {

    public enum Phase {
        /** Pre-spawn delay (1-2 minutes) — boss bar visible, no raiders yet. */
        DELAY,
        /** Spawn engine actively producing raiders until total is reached. */
        SPAWNING,
        /** Total spawn budget exhausted; waiting on the last few to die. */
        DRAINING,
        /** Every raider dead — raid is complete. RaidManager prunes after this. */
        ENDED;

        /**
         * Player-facing label for this phase. SPAWNING and DRAINING both show as "Active"
         * because the distinction (still producing vs. waiting on stragglers) doesn't matter
         * to the defender — what matters is "raiders may show up at any moment". The internal
         * enum still differentiates so the spawn engine knows whether to spawn or not.
         */
        public String displayName() {
            switch (this) {
                case DELAY:    return "Incoming";
                case SPAWNING:
                case DRAINING: return "Active";
                case ENDED:    return "Over";
                default:       return name();
            }
        }
    }

    private final UUID raidId;
    private final UUID attackerId;
    private final String attackerName;
    private final UUID zoneOwnerId;
    private final UUID worldId;
    private final int units;
    private final int totalToSpawn;
    private final int activeGoal;
    private final long startedAtMillis;

    private long delayUntilMillis;
    private int spawnedSoFar;
    private int aliveCount;
    private Phase phase;

    // -- per-raid stats (in-memory, not persisted) ----------------------------
    /** Wall-clock ms when this raid first entered SPAWNING. 0 until that transition. */
    private long activePhaseStartMillis;
    /** Defender-side player → raider kill count for this raid. Used to gate the +1000 XP. */
    private final Map<UUID, Integer> raidersKilledByPlayer = new HashMap<>();
    /** Attacker-side player → cumulative damage dealt to defender-side players inside the zone.
     *  Used to gate attacker-friend / attacker-ally XP eligibility for the bonus payouts. */
    private final Map<UUID, Double> damageToDefendersByAttackerSide = new HashMap<>();
    /** True once the +500 XP "Destroy All 4 Turret Bonus" has been paid out (one per raid). Also
     *  doubles as the "were all four turrets EVER simultaneously down" flag — its inverse gates the
     *  defenders' "keep a turret intact" bonus. */
    private boolean allTurretsBonusAwarded;
    /** True once the +500 XP "Sustain 15 min" bonus has been paid out (one per raid). */
    private boolean sustainedBonusAwarded;
    /** Count of turrets the attacker has destroyed during THIS raid — feeds the "10 in one raid"
     *  quest. Transient (not persisted); resets to 0 on a fresh raid, which is the intent. */
    private int turretsDestroyedThisRaid;
    /** Defender-side players who died at least once during this raid — excluded from the
     *  "made it to the end" bonus (owner included; the bonus loses meaning otherwise). */
    private final Set<UUID> defendersWhoDied = new HashSet<>();
    /** Per-defender seconds spent inside the zone (sampled), and total sampled raid seconds.
     *  Friends/allies need ≥50% to be bonus-eligible; the owner is exempt from this gate. */
    private final Map<UUID, Integer> inZoneSecondsByDefender = new HashMap<>();
    private int sampledRaidSeconds;

    private ActiveRaid(UUID raidId, UUID attackerId, String attackerName, UUID zoneOwnerId,
                       UUID worldId, int units, int totalToSpawn, int activeGoal,
                       long startedAtMillis, long delayUntilMillis,
                       int spawnedSoFar, int aliveCount, Phase phase) {
        this.raidId          = raidId;
        this.attackerId      = attackerId;
        this.attackerName    = attackerName;
        this.zoneOwnerId     = zoneOwnerId;
        this.worldId         = worldId;
        this.units           = units;
        this.totalToSpawn    = totalToSpawn;
        this.activeGoal      = activeGoal;
        this.startedAtMillis = startedAtMillis;
        this.delayUntilMillis = delayUntilMillis;
        this.spawnedSoFar    = spawnedSoFar;
        this.aliveCount      = aliveCount;
        this.phase           = phase;
    }

    /** Build a fresh raid (start-of-life). The delay endpoint is supplied by the caller so the
     *  same RaidManager.startRaid path can choose a 60-120s random window deterministically. */
    public static ActiveRaid create(UUID attackerId, String attackerName, UUID zoneOwnerId,
                                    UUID worldId, int units, long now, long delayUntilMillis) {
        units = clampUnits(units);
        int total = totalSpawnsFor(units);
        int goal  = activeGoalFor(units);
        return new ActiveRaid(UUID.randomUUID(), attackerId, attackerName, zoneOwnerId, worldId,
                units, total, goal, now, delayUntilMillis, 0, 0, Phase.DELAY);
    }

    // -- scaling --------------------------------------------------------------

    /** 32 units → 128 spawns, 64 units → 256 spawns, linear in between (rounded). */
    public static int totalSpawnsFor(int units) {
        units = clampUnits(units);
        // total = 128 + (units - 32) * 4
        return 128 + (units - 32) * 4;
    }

    /** 32 units → 9 active goal, 64 units → 12 active goal, linear in between (rounded). */
    public static int activeGoalFor(int units) {
        units = clampUnits(units);
        // goal = 9 + (units - 32) * 3/32
        return (int) Math.round(9.0 + (units - 32) * 3.0 / 32.0);
    }

    private static int clampUnits(int u) {
        if (u < 32) return 32;
        if (u > 64) return 64;
        return u;
    }

    // -- getters --------------------------------------------------------------

    public UUID   getRaidId()           { return raidId; }
    public UUID   getAttackerId()       { return attackerId; }
    public String getAttackerName()     { return attackerName; }
    public UUID   getZoneOwnerId()      { return zoneOwnerId; }
    public UUID   getWorldId()          { return worldId; }
    public int    getUnits()            { return units; }
    public int    getTotalToSpawn()     { return totalToSpawn; }
    public int    getActiveGoal()       { return activeGoal; }
    public long   getStartedAtMillis()  { return startedAtMillis; }
    public long   getDelayUntilMillis() { return delayUntilMillis; }
    public int    getSpawnedSoFar()     { return spawnedSoFar; }
    public int    getAliveCount()       { return aliveCount; }
    public Phase  getPhase()            { return phase; }

    public int remainingToSpawn() { return Math.max(0, totalToSpawn - spawnedSoFar); }

    /**
     * Progress percentage shown in the boss bar. Counts both raiders still to spawn and
     * raiders still alive against the total. So 100% means nobody's been killed yet (or the
     * raid just started); 0% means every raider that will ever appear has been killed.
     */
    public double progressFractionRemaining() {
        if (totalToSpawn <= 0) return 0.0;
        int pending = remainingToSpawn() + aliveCount;
        return Math.max(0.0, Math.min(1.0, (double) pending / totalToSpawn));
    }

    // -- mutators -------------------------------------------------------------

    public void setPhase(Phase phase) { this.phase = phase; }
    public void setDelayUntilMillis(long v) { this.delayUntilMillis = v; }

    /** Called by the spawn engine immediately after a raider/ravager comes up alive. */
    public void noteSpawned() {
        spawnedSoFar++;
        aliveCount++;
    }

    /** Called when a raider dies (or its NPC is destroyed mid-raid for any reason). */
    public void noteDied() {
        if (aliveCount > 0) aliveCount--;
    }

    /**
     * Used by the load path to restore counters from {@code raids.yml} without going through
     * noteSpawned/noteDied. Caller is responsible for matching alive-count with the actual
     * cohort the spawn engine instantiates on recovery.
     */
    public void restoreCounters(int spawnedSoFar, int aliveCount, Phase phase) {
        this.spawnedSoFar = Math.max(0, spawnedSoFar);
        this.aliveCount   = Math.max(0, aliveCount);
        this.phase        = phase;
    }

    /** True when nothing more will ever spawn AND nobody's still alive. */
    public boolean isComplete() {
        return remainingToSpawn() == 0 && aliveCount == 0;
    }

    // -- stat hooks -----------------------------------------------------------

    /** Snapshot the start-of-active-phase wall-clock once, on the first SPAWNING transition. */
    public void markActivePhaseStartedIfNeeded(long now) {
        if (activePhaseStartMillis == 0L) activePhaseStartMillis = now;
    }

    public long getActivePhaseStartMillis() { return activePhaseStartMillis; }

    /** Increment a defender's raider-kill counter. Returns the new total. */
    public int incrementRaiderKill(UUID killerId) {
        if (killerId == null) return 0;
        int next = raidersKilledByPlayer.getOrDefault(killerId, 0) + 1;
        raidersKilledByPlayer.put(killerId, next);
        return next;
    }

    public int raiderKillsBy(UUID killerId) {
        if (killerId == null) return 0;
        return raidersKilledByPlayer.getOrDefault(killerId, 0);
    }

    /** Accumulate damage dealt by an attacker-side player to a defender. */
    public void addDamageToDefenders(UUID attackerSideId, double amount) {
        if (attackerSideId == null || amount <= 0.0) return;
        damageToDefendersByAttackerSide.merge(attackerSideId, amount, Double::sum);
    }

    public double damageDealtBy(UUID attackerSideId) {
        if (attackerSideId == null) return 0.0;
        return damageToDefendersByAttackerSide.getOrDefault(attackerSideId, 0.0);
    }

    public boolean isAllTurretsBonusAwarded()      { return allTurretsBonusAwarded; }
    public void markAllTurretsBonusAwarded()       { this.allTurretsBonusAwarded = true; }
    public boolean isSustainedBonusAwarded()       { return sustainedBonusAwarded; }
    public void markSustainedBonusAwarded()        { this.sustainedBonusAwarded = true; }
    public int getTurretsDestroyedThisRaid()       { return turretsDestroyedThisRaid; }
    public void addTurretDestroyedThisRaid()       { this.turretsDestroyedThisRaid++; }

    /** Record that a defender-side player died this raid (drops them from the survival bonus). */
    public void markDefenderDied(UUID id) { if (id != null) defendersWhoDied.add(id); }

    /** True if this player never died during the raid ("made it to the end"). */
    public boolean defenderSurvived(UUID id) { return id == null || !defendersWhoDied.contains(id); }

    /** Add a presence sample: {@code seconds} of raid time, of which the listed defenders were
     *  inside the zone for the same {@code seconds}. */
    public void addRaidSampleSeconds(int seconds) { if (seconds > 0) sampledRaidSeconds += seconds; }
    public void addInZoneSeconds(UUID id, int seconds) {
        if (id != null && seconds > 0) inZoneSecondsByDefender.merge(id, seconds, Integer::sum);
    }

    /** True if this player spent ≥50% of the sampled raid time inside the zone. Returns true when
     *  nothing was sampled yet (very short raid) so it never wrongly blocks. */
    public boolean spentEnoughTimeInZone(UUID id) {
        if (sampledRaidSeconds <= 0) return true;
        int inZone = id == null ? 0 : inZoneSecondsByDefender.getOrDefault(id, 0);
        return inZone * 2 >= sampledRaidSeconds;
    }

    // -- persistence ----------------------------------------------------------

    public void serialize(ConfigurationSection s) {
        s.set("attacker",     attackerId.toString());
        s.set("attacker_name", attackerName);
        s.set("owner",        zoneOwnerId.toString());
        s.set("world",        worldId.toString());
        s.set("units",        units);
        s.set("total",        totalToSpawn);
        s.set("goal",         activeGoal);
        s.set("started_at",   startedAtMillis);
        s.set("delay_until",  delayUntilMillis);
        s.set("spawned",      spawnedSoFar);
        s.set("alive",        aliveCount);
        s.set("phase",        phase.name());
    }

    public static ActiveRaid deserialize(UUID raidId, ConfigurationSection s) {
        UUID attacker = UUID.fromString(s.getString("attacker"));
        String name   = s.getString("attacker_name", "?");
        UUID owner    = UUID.fromString(s.getString("owner"));
        UUID world    = UUID.fromString(s.getString("world"));
        int units     = s.getInt("units", 32);
        int total     = s.getInt("total", totalSpawnsFor(units));
        int goal      = s.getInt("goal", activeGoalFor(units));
        long startedAt   = s.getLong("started_at", System.currentTimeMillis());
        long delayUntil  = s.getLong("delay_until", startedAt);
        int spawned   = s.getInt("spawned", 0);
        int alive     = s.getInt("alive", 0);
        Phase phase;
        try { phase = Phase.valueOf(s.getString("phase", "SPAWNING")); }
        catch (IllegalArgumentException e) { phase = Phase.SPAWNING; }
        return new ActiveRaid(raidId, attacker, name, owner, world, units, total, goal,
                startedAt, delayUntil, spawned, alive, phase);
    }
}
