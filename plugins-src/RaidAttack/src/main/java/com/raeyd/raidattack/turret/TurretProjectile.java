package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.pathfinding.PathPlanner;
import java.util.List;
import java.util.UUID;
import org.bukkit.util.Vector;

/**
 * Bookkeeping for a single in-flight turret projectile. The {@code entityId} points at a
 * ShulkerBullet we spawned purely for its visual; movement, retargeting, collision and damage
 * are all driven by {@link TurretCombatManager} — vanilla AI is suppressed.
 *
 * <p>The {@code spawningClaimOwner} pins which claim the bullet came out of. A bullet that
 * drifts outside that claim's footprint can still finish its current target but cannot acquire
 * a new one (see {@code projectileTick}).
 */
public final class TurretProjectile {

    public final UUID entityId;
    public final int level;
    public final long spawnTick;
    /** Claim owner who fired this bullet. Used for member-immunity + out-of-zone retarget rule. */
    public final UUID spawningClaimOwner;
    /** 0-based slot index of the turret that fired this bullet (for kill attribution). */
    public final int firingSlot;

    /** Current chosen target UUID; null if no target found yet or last target was lost. */
    public UUID targetId;
    /** Vector applied per tick (length = {@link TurretCombatTuning#BLOCKS_PER_TICK}). */
    public Vector lastDirection;
    /** Last tick we re-evaluated the target list. */
    public long lastRetargetTick;
    /** Hit points. Player melee / arrows decrement this; ≤ 0 → bullet dies without exploding. */
    public int hp;
    /**
     * Set true when we explicitly decide to kill the bullet (proximity hit, HP zero, lifetime,
     * etc.) so the EntityRemove listener knows NOT to resurrect it. Without this flag we'd
     * spawn replacements for every legitimate despawn too.
     */
    public boolean markedForCleanup;
    /** How many times vanilla has killed this projectile and we've respawned it. Capped to
     *  prevent infinite loops if the bullet's position is genuinely unrecoverable. */
    public int respawnCount;

    /**
     * Locked cardinal direction for the forced-exit phase. Picked at spawn as the closest of
     * N/E/S/W to the initial target's horizontal bearing — guarantees the bullet leaves through
     * one of the four gaps in layer 5 of its own turret structure rather than slamming into
     * the corner walls.
     */
    public Vector forcedExitDirection;
    /**
     * Remaining distance (in blocks) of the forced-exit phase. Counted down each tick by
     * {@link TurretCombatTuning#BLOCKS_PER_TICK}; while > 0 the projectile flies in a perfect
     * straight line through {@link #forcedExitDirection}, all collision and pathfinding off.
     */
    public double forcedExitBlocksRemaining;

    /**
     * Planned waypoint list (block-centre coordinates) computed by {@link PathPlanner} A*.
     * Empty / null = no plan, fall back to direct line + reactive avoidance. Bullet aims at
     * {@code path.get(pathIndex)} each tick and advances when within
     * {@link TurretCombatTuning#WAYPOINT_REACHED_DISTANCE} blocks of it.
     */
    public List<Vector> path;
    /** Index into {@link #path} of the waypoint currently being chased. */
    public int pathIndex;
    /** Last tick we replanned the A* path. Replans are throttled to avoid CPU thrash. */
    public long lastReplanTick;
    /**
     * While {@code currentTick < pathRetryAfterTick}, skip A* replans entirely. Set after a
     * FAILED search ({@link TurretCombatTuning#PATH_FAIL_BACKOFF_TICKS}) so an unreachable target
     * can't trigger a full-budget A* every tick — the bullet coasts straight at the target until
     * the backoff elapses. Cleared (0) on a successful path.
     */
    public long pathRetryAfterTick;
    /** Closest squared distance to the current target seen so far — drives the no-progress
     *  watchdog. Reset to {@link Double#MAX_VALUE} whenever the target changes. */
    public double bestProgressDistSq = Double.MAX_VALUE;
    /** Tick at which {@link #bestProgressDistSq} was last improved. 0 = never. */
    public long bestProgressTick;

    public TurretProjectile(UUID entityId, int level, long spawnTick,
                            UUID spawningClaimOwner, int firingSlot) {
        this.entityId = entityId;
        this.level = level;
        this.spawnTick = spawnTick;
        this.spawningClaimOwner = spawningClaimOwner;
        this.firingSlot = firingSlot;
        this.hp = TurretCombatTuning.MAX_HP;
    }
}
