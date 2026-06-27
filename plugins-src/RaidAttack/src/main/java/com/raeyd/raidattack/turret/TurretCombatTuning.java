package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.claim.Claim;

/**
 * Centralised combat constants. Per-level stats live here so a tuning pass is a single file
 * touch.
 *
 * <p>Current tuning:
 * <pre>
 *   range:        35 blocks horizontal (all levels)
 *   speed:        6 blocks/s (custom — vanilla ShulkerBullet ignored)
 *   lifetime:     12.5 s before forced despawn
 *   damage:       8 / 10 / 12 (L1 / L2 / L3)
 *   interval:     4 s / 3 s / 2 s
 *   splash:       1.5 blocks (HIT_RADIUS + EXPLOSION_RADIUS both at 1.5, sphere semantics)
 * </pre>
 */
public final class TurretCombatTuning {

    private TurretCombatTuning() {}

    /** Detection range of a turret looking for targets (blocks). */
    public static final double RANGE = 35.0;
    /**
     * Per fire-attempt, how many candidate targets the turret will A*-test before giving up.
     * Lower than it used to be because the cheap occluding-block raycast pre-filter in
     * {@code findEligibleTargets} already drops buried mobs — by the time the list reaches A*,
     * every candidate has at most 3 opaque blocks in the way and is genuinely a plausible hit.
     */
    public static final int MAX_PATH_TRIES = 3;
    /** Range a bullet uses each tick to look for an updated target (blocks). */
    public static final double BULLET_HOMING_RANGE = 35.0;
    /** Projectile speed in blocks/second. */
    public static final double SPEED_BLOCKS_PER_SECOND = 6.0;
    /** Per-tick movement step at 20 TPS. */
    public static final double BLOCKS_PER_TICK = SPEED_BLOCKS_PER_SECOND / 20.0;
    /**
     * Sphere radius (in blocks) used for both the projectile's hit-trigger and its splash AoE.
     * Semantics: an entity is "inside" the radius iff the distance from the impact point to the
     * nearest point of the entity's bounding box is ≤ {@code HIT_RADIUS}. That's a true sphere
     * around the impact — same reach in every direction — which matches how the user thinks
     * about it ("1.5 blocks from projectile centre in every direction"). The previous
     * bbox-expand approach measured along each axis independently and produced a cube, which
     * over-reached in the diagonals and felt wrong at the boundary.
     */
    public static final double HIT_RADIUS = 1.5;
    /** Ticks between target re-evaluations per projectile. */
    public static final long RETARGET_INTERVAL = 5L;
    /** Hard despawn after this many ticks (12.5 s). */
    public static final long LIFETIME_TICKS = 250L;
    /** Per-projectile hit points; sword/arrow damage subtracts. ≤ 0 → bullet dies silently. */
    public static final int MAX_HP = 30;
    /**
     * Just-fired bullets ignore ALL block collisions for this many ticks (2 s). Lets the
     * projectile clear its own turret structure and any close walls without exploding on
     * its own dome. After this elapses, normal block collision applies — except blocks that
     * fall inside any turret's protected zone, which remain pass-through forever.
     */
    public static final long GRACE_PERIOD_TICKS = 40L;
    /**
     * Distance (blocks) the bullet flies in a locked cardinal direction immediately after
     * firing. Pathfinding, retargeting and avoidance are all disabled during this phase so the
     * bullet can pop cleanly out of the layer-5 cardinal gap of its own turret structure.
     */
    public static final double FORCED_EXIT_BLOCKS = 4.0;
    /**
     * Look-ahead distance (blocks) for smooth avoidance. When the cell at this distance ahead
     * is blocked, the bullet's heading is gently blended toward open air. 1.0 gives enough
     * runway (~3 ticks at 6 b/s) for the blended curve to actually clear small obstacles like
     * tree trunks before the bullet would arrive — 0.5 was too late, the curve hadn't built
     * up enough vertical when the bullet reached the trunk.
     */
    public static final double AVOIDANCE_LOOKAHEAD = 1.0;
    /** Maximum vertical probe distance when looking for "over the top" avoidance (blocks). */
    public static final int AVOIDANCE_UP_PROBE_MAX = 5;
    /** Maximum horizontal perpendicular probe distance (blocks). */
    public static final int AVOIDANCE_SIDE_PROBE_MAX = 3;

    /** Bullet advances to the next waypoint when within this many blocks of the current one. */
    public static final double WAYPOINT_REACHED_DISTANCE = 1.0;
    /** Re-plan the A* path no more often than every N ticks, even if the target moved. */
    public static final long REPLAN_INTERVAL_TICKS = 20L;     // 1 s
    /**
     * After a FAILED A* search (target currently unreachable from the bullet's position), wait
     * this many ticks before attempting another. Without this, a bullet whose {@code path} stays
     * null re-runs a full-budget A* EVERY tick for its whole life — the "spinning projectile"
     * TPS sink. The bullet coasts straight at its target (with reactive avoidance) during the
     * backoff. 40 ticks = 2 s.
     */
    public static final long PATH_FAIL_BACKOFF_TICKS = 40L;
    /**
     * A coasting bullet (no usable A* path) that hasn't gotten meaningfully closer to its target
     * for this many ticks is presumed stuck/orbiting an unreachable point and is expired early
     * instead of spinning for its full {@link #LIFETIME_TICKS}. 200 ticks = 10 s — bumped up from
     * the old 3 s, which was visibly cutting the final-burst panic-volley bullets short (they
     * acquire the attacker, often can't path to them, and were dying at ~4-5 s). The A* failure
     * backoff ({@link #PATH_FAIL_BACKOFF_TICKS}) already prevents the per-tick replan TPS sink, so
     * a bullet coasting for the full 10 s here is cheap.
     */
    public static final long NO_PROGRESS_TIMEOUT_TICKS = 200L;
    /** Minimum squared-distance improvement (blocks²) that counts as "making progress" toward
     *  the target, for the no-progress watchdog. 4.0 = 2 blocks. */
    public static final double NO_PROGRESS_EPSILON_SQ = 4.0;
    /**
     * Vanilla ShulkerBullet always self-discards on block raycast hit regardless of whether
     * ProjectileHitEvent was cancelled. We resurrect the bullet at the same position when this
     * happens — but cap how many times per projectile so a truly unrecoverable spot (genuinely
     * walled in) eventually gives up.
     */
    public static final int MAX_RESPAWNS = 30;
    /** Forward share of the blended direction when avoidance kicks in (rest = avoidance). */
    public static final double AVOIDANCE_FORWARD_WEIGHT = 0.5;
    /** Splash radius (blocks) around the impact point — every eligible living entity in here
     *  takes the same level-tuned damage as the direct hit. Uses the same sphere semantics as
     *  {@link #HIT_RADIUS}: an entity is in the splash iff its bbox is within {@code
     *  EXPLOSION_RADIUS} of the impact point. */
    public static final double EXPLOSION_RADIUS = 1.5;

    /**
     * Reachability ray threshold: if the line turret→target crosses more than this many
     * *occluding* (opaque, full) blocks, the target is deemed unreachable. Bumped to 8 from 4
     * because a single tall tree canopy can easily contain 3-4 occluding blocks along a line,
     * and A* with a 1000-node budget can route around moderate obstacles — overly tight
     * filtering was rejecting valid targets at 8b distance.
     *
     * <p>Combined with the {@code isOccluding} check (vs the older {@code isSolid}) we no
     * longer count leaves/glass/vines, which the bullet phases through visually anyway.
     */
    public static final int REACHABILITY_MAX_SOLID_BLOCKS = 8;
    /** Step size (blocks) along the reachability ray. Smaller = more accurate, more lookups. */
    public static final double REACHABILITY_STEP = 0.5;
    /**
     * Once a target is marked unreachable, skip it for this many ticks before re-checking.
     * Keeps the per-tick raycast cost bounded when many candidates are present and all
     * unreachable (e.g. mobs in caves under the turret) — without this, we'd recompute every
     * combat tick for every candidate.
     */
    public static final long UNREACHABLE_CACHE_TICKS = 60L;   // 3 s

    /** Damage applied on hit. Index = level - 1. */
    private static final double[] DAMAGE = {8.0, 10.0, 12.0};
    /** Attack interval between shots (Bukkit ticks). Index = level - 1. */
    private static final long[] INTERVAL_TICKS = {80L, 60L, 40L};   // 4s, 3s, 2s
    /** Regeneration duration (seconds) given to claim members hit by friendly turret splash. */
    private static final int[] FRIENDLY_REGEN_SECONDS = {3, 4, 5};

    /** Radius (blocks) around any turret's centre inside which claim members get Resistance I. */
    public static final double BUFF_AURA_RADIUS = 10.0;
    /** Duration in ticks of the resistance buff each refresh — 10 s as spec'd. */
    public static final int BUFF_AURA_TICKS = 200;

    public static double damage(int level) {
        return DAMAGE[clamp(level) - 1];
    }

    public static long attackIntervalTicks(int level) {
        return INTERVAL_TICKS[clamp(level) - 1];
    }

    public static int friendlyRegenSeconds(int level) {
        return FRIENDLY_REGEN_SECONDS[clamp(level) - 1];
    }

    private static int clamp(int level) {
        if (level < 1) return 1;
        if (level > Claim.MAX_TURRET_LEVEL) return Claim.MAX_TURRET_LEVEL;
        return level;
    }
}
