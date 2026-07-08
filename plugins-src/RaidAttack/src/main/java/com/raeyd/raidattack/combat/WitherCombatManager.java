package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.pathfinding.PathPlanner;
import com.raeyd.raidattack.quest.Quest;
import com.raeyd.raidattack.raid.ActiveRaid;
import com.raeyd.raidattack.raid.CustomRaider;
import com.raeyd.raidattack.raid.CustomRavager;
import com.raeyd.raidattack.raid.RaidEntityManager;
import com.raeyd.raidattack.raid.RaidMessages;
import com.raeyd.raidattack.turret.Turret;
import com.raeyd.raidattack.turret.TurretStructure;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Wither combat manager — the full custom Wither AI (combat AI + zone/turret-siege AI + spawn-ritual
 * spin + the multi-stage death sequence) plus the turret-structure HP system the wither attacks
 * (phantom HP labels, structure damage, destruction/respawn). Formerly {@code TurretStructureCombat};
 * renamed because the bulk of this class is now the Wither.
 *
 * <p><b>Mob behaviour.</b> All non-wither mobs keep their full vanilla AI — there is no longer
 * any "mob attacks turret" code path. Zombies, skeletons, creepers, etc. ignore the structure
 * and chase players as normal.
 *
 * <p><b>Wither special.</b> When a {@link Wither} is inside a claim zone AND at least one
 * turret in that claim is still alive:
 * <ol>
 *   <li>Pick the closest turret whose hover point (12 blocks above its centre) is reachable
 *       through air from the wither's current position.</li>
 *   <li>Each tick, set the wither's velocity toward that hover point. When within ~1 block of
 *       the hover, zero its velocity so it stays put (max 1-block drift from centre).</li>
 *   <li>{@code setTarget(phantom)} the floating armor stand at the turret's centre — the
 *       wither's vanilla skull-firing goal aims at the target, so skulls rain down on the
 *       structure.</li>
 *   <li>If no turret is reachable through air, vanilla AI takes over.</li>
 * </ol>
 *
 * <p><b>Damage routing.</b> We do not invent damage values. The wither's skulls explode in
 * the claim zone; {@link EntityExplodeEvent} catches each one and credits its (vanilla) damage
 * to the nearest live turret's HP pool. {@link ZoneListener} cancels the actual block damage,
 * so the structure stays standing visually.
 *
 * <p><b>Phantom + HP label.</b> One {@link ArmorStand} per turret, sitting at the layer-5
 * centre (same Y as the shulker NPC). Its custom name shows {@code <hp>/<max> HP}. We dedupe
 * aggressively each tick — if any other armor stand carries our PDC tag for the same anchor,
 * it's destroyed. The label updates only when the HP value actually changes, so the entity
 * metadata packet isn't spammed.
 *
 * <p><b>Tier HP.</b> Max HP scales with level: L1=150 / L2=175 / L3=200, via
 * {@link Turret#maxHpForLevel(int)}.
 *
 * <p><b>Destruction + respawn.</b> Unchanged: anvil-destroy sound + 20-block-radius red
 * broadcast at 0 HP, shulker NPC despawns, 2-minute respawn timer, then full HP again.
 *
 * <p><b>Player exclusion.</b> Players cannot damage the phantom directly or via projectile —
 * the damage event is cancelled with no HP loss.
 */
public final class WitherCombatManager implements Listener {

    /** Tick interval for the master maintenance loop. */
    private static final long TICK_INTERVAL = 20L;     // 1 s
    /** Tick interval for the wither flight controller. <b>1 tick (= 20 Hz).</b> Vanilla applies
     *  physics/AI every single server tick, so anything slower leaves an off-tick gap where the
     *  vanilla goal selector and residual knockback velocity drift the wither and "win" — exactly
     *  the loss-of-control the user reported. Running every tick (plus per-tick velocity zeroing
     *  in {@link #flyTowardAndTarget}) closes that gap so our teleport-based pathing is
     *  authoritative. The earlier TPS concern from {@code getHighestBlockYAt} is gone now that the
     *  ground sample is cached for 1 s. Step size scales with this interval so net speed is
     *  unchanged when it's tuned. */
    private static final long WITHER_TICK_INTERVAL = 1L;
    /** Sound + broadcast radius when a turret is destroyed. */
    private static final double DESTROY_SOUND_RADIUS = 20.0;
    /** Time a destroyed turret stays down before respawning. */
    private static final long RESPAWN_AFTER_MILLIS = 5L * 60L * 1000L;     // 5 minutes
    /** Vertical offset of the phantom + HP label above the turret anchor (= layer-5 centre). */
    private static final double PHANTOM_DY = 4.0;
    /** Horizontal "move-to" speed (b/s) while CRUISING toward a target. Buffed by another +1 (was
     *  5.0) on the user's request — shared by BOTH the zone (turret) AI and the new combat AI. */
    private static final double WITHER_CRUISE_SPEED_BPS = 6.0;
    /** Horizontal speed (b/s) while HOVERING/circling once on-station. Left as-is (hover "same"). */
    private static final double WITHER_HOVER_SPEED_BPS = 2.5;
    /** Vertical climb/descend speed (b/s) in both phases. Bumped 3 → 5 so the wither can actually
     *  gain altitude (it was "incapable of flying up in most moments") and climb to turrets perched
     *  up a mountain instead of face-planting the cliff and waiting for the stuck-teleport. */
    private static final double WITHER_VERT_SPEED_BPS = 5.0;
    /** Lookahead distance (blocks) for ground sampling. */
    private static final double WITHER_GROUND_LOOKAHEAD = 5.0;
    /** Refresh the cached ground sample no more often than this. */
    private static final long WITHER_GROUND_REFRESH_TICKS = 20L;
    /** Per-step displacement at the configured tick interval. */
    /** Amplitude of the vertical sin-bob applied to the aim Y each step. Mimics the slight up-
     *  down hover wobble of a vanilla wither in flight. */
    private static final double WITHER_BOB_AMPLITUDE = 0.5;
    /** Period of the sin-bob in server ticks. ~2 s feels natural. */
    private static final double WITHER_BOB_PERIOD_TICKS = 40.0;
    /** Engagement gate: wither within this XZ-distance (blocks) of a claim's edge that still has a
     *  live turret locks onto it and flies in to siege. Inside the claim itself counts as distance 0.
     *  Per design: summon the wither <em>near</em> the base you want to hit (within ~25 blocks) and it
     *  homes in; summon it out in the wild and it stays in its general combat AI instead of beelining
     *  a base across the map. Once a target is acquired the wither <b>commits</b> to it (see
     *  {@link #engagedClaimByWither}) so it doesn't dither between two nearby bases as it moves. */
    private static final double WITHER_ENGAGE_PROXIMITY = 25.0;
    /** Horizontal distance to turret centre under which we switch from "cruise" to "hover above". */
    private static final double WITHER_HOVER_APPROACH_DIST_XZ = 8.0;
    /** Cruise altitude above ground in phase 1 (HP > 150). The wither flies low-ish so it looks
     *  natural skimming over terrain rather than helicoptering at sky height. */
    private static final double CRUISE_ALT_PHASE1 = 7.0;
    /** Cruise altitude above ground in phase 2 (HP ≤ 150). Vanilla wither drops down when its
     *  armor breaks — match that feel. */
    private static final double CRUISE_ALT_PHASE2 = 2.5;
    /** Hover altitude above the turret anchor in phase 1 — middle of the 10-12 user spec. */
    private static final double HOVER_DY_PHASE1 = 11.0;
    /** Hover altitude above the turret anchor in phase 2 — middle of the 8-10 user spec. */
    private static final double HOVER_DY_PHASE2 = 9.0;
    /** HP threshold for phase-2 behaviour. */
    private static final double PHASE2_HP_THRESHOLD = 150.0;
    /** Toggle wither-state debug logging. Off by default once behaviour is dialled in. */
    private static final boolean WITHER_DEBUG = true;
    /** Cooldown between custom skull BURSTS (ticks). Matches vanilla wither cadence (~2 s). */
    private static final long CUSTOM_SKULL_INTERVAL_TICKS = 40L;
    /** Delay between the two shots of a burst (ticks). Vanilla pattern is one skull then ~0.5 s later another. */
    private static final long BURST_SECOND_SHOT_DELAY = 10L;
    /** Speed (blocks/tick) of our custom wither skulls. */
    private static final double CUSTOM_SKULL_SPEED = 1.0;
    /** Horizontal roaming radius around the turret centre (blocks). */
    private static final double WITHER_WANDER_RADIUS_XZ = 5.0;
    /** How often the wither picks a new wander offset (ticks). */
    private static final long WANDER_REFRESH_TICKS = 60L;     // 3 s
    /** HP regen amount per regen interval. Tuned to +1 HP every 2 seconds (see interval below). */
    private static final int HP_REGEN_AMOUNT = 1;
    /** HP regen interval — master loop is 1 Hz, so 2 master ticks = 2 seconds → +1 HP / 2 s. */
    private static final int HP_REGEN_INTERVAL_TICKS = 2;

    // -- wither boss + combat-AI tuning ---------------------------------------
    /** Wither boss max HP under our control (vanilla is 300). */
    private static final double WITHER_MAX_HP = 600.0;
    /** The wither only self-heals (1 HP/s) while strictly BELOW this HP. */
    private static final double WITHER_REGEN_CEILING = 300.0;
    /** Half-HP threshold (= half of {@link #WITHER_MAX_HP}). At/under this the wither glows, drops
     *  lower (combat AI), and ONCE spawns reinforcement skeletons. Intentionally separate from
     *  {@link #PHASE2_HP_THRESHOLD} — that is the ZONE-AI altitude knee, left at its old tuning so
     *  the (already-working) zone behaviour is untouched. */
    private static final double WITHER_HALF_HP = 300.0;
    /** Normal wither skeletons spawned once when the wither first crosses {@link #WITHER_HALF_HP}. */
    private static final int HALF_HP_SKELETONS = 3;

    // -- wither-skeleton minion AI (the skeletons spawned at half HP) --------------------------
    /** PDC tag value marking a skeleton as one of our custom-AI minions. */
    private static final String MINION_TAG = "wither_minion";
    /** Target acquisition radius — same target priority as the wither, but a 15-block reach. */
    private static final double MINION_ACQUIRE_RANGE = 15.0;
    /** Melee reach: deal damage within this many blocks of a living target. */
    private static final double MINION_ATTACK_RANGE = 2.5;
    /** Damage per hit — 6 hearts (also the structure HP a hit removes from a turret). */
    private static final double MINION_DAMAGE = 12.0;
    /** Wither I (amplifier 0) for 10 s applied to living targets on hit. */
    private static final int MINION_WITHER_TICKS = 200;
    private static final int MINION_WITHER_AMPLIFIER = 0;
    /** ~1 s between hits (melee or turret). */
    private static final long MINION_ATTACK_COOLDOWN_TICKS = 20L;
    /** Navigator speed modifier (× base movement speed) for the vanilla pathfinder. */
    private static final double MINION_NAV_SPEED = 1.2;
    /** Follow-range attribute — lets the vanilla navigator compute a path far enough to cross the
     *  base to the next turret (the default 16 is too short). */
    private static final double MINION_FOLLOW_RANGE = 48.0;
    /** Retaliation memory window — fight back against whoever hit us for this long. */
    private static final long MINION_RETALIATE_WINDOW_TICKS = 100L;
    /** Combat-AI hover height above the current target (or ground when idle) — above half HP. */
    private static final double COMBAT_HOVER_PHASE1 = 6.0;
    /** Combat-AI hover height above the current target (or ground) — at/under half HP (drops down). */
    private static final double COMBAT_HOVER_PHASE2 = 3.0;
    /** Idle hover height above the ground when the wither has NO target — it sinks down and loiters
     *  just above the surface (≈1 block) instead of floating high doing nothing. */
    private static final double COMBAT_HOVER_IDLE = 2.0;
    /** Combat-AI target acquisition range on each axis. */
    private static final double COMBAT_ACQUIRE_RANGE = 40.0;
    /** How long (ticks) the wither keeps retaliating against something that attacked it (5 s). */
    private static final long RETALIATE_WINDOW_TICKS = 100L;
    /** Re-scan for a combat target only this often (ticks). Between scans we just track the cached
     *  target's live position — keeps the per-tick {@code getNearbyEntities} sweep off the hot path
     *  (it was a measurable TPS cost), re-scanning twice a second instead of 20×. */
    private static final long COMBAT_ACQUIRE_INTERVAL = 10L;
    /** Combat-AI block-break cadence: every 5 s while the wither is wedged against solid blocks. */
    private static final long WITHER_BREAK_INTERVAL_TICKS = 100L;
    /** Wither body footprint for collision/dig tests: half-width of the ~0.9-wide body (+buffer). The
     *  old checks only sampled the single block column under the wither's feet, so its real body width
     *  could sit ~half a block INSIDE adjacent blocks (and the dig gate missed nearby blocks). */
    private static final double WITHER_HALF_WIDTH = 0.5;
    /** Wither body height for collision/dig tests (~3.5-tall body + buffer). */
    private static final double WITHER_BODY_HEIGHT = 3.6;
    /** Movement-collision margin — 0 stops the body edge AT the block face (no clipping into blocks). */
    private static final double WITHER_COLLISION_MARGIN = 0.0;
    /** Anything within this radius of a combat-skull explosion is splashed with Wither II for 35 s. */
    private static final double COMBAT_SKULL_HIT_RADIUS = 1.0;
    private static final int COMBAT_WITHER_TICKS = 700;     // 35 s
    private static final int COMBAT_WITHER_AMPLIFIER = 1;   // level II

    // -- death sequence -------------------------------------------------------
    /** Length of the spin-up death sequence before the final blast (5 s). */
    private static final long DEATH_SEQUENCE_TICKS = 100L;
    /** Total XP dropped (as scattered orbs) when the wither finally explodes. */
    private static final int WITHER_DEATH_XP = 1000;
    /** Final-blast radius (~10-wide footprint). */
    private static final double DEATH_BLAST_RADIUS = 5.0;
    /** Damage at the very centre of the final blast. */
    private static final double DEATH_BLAST_CENTER_DMG = 50.0;
    /** Damage at the outer edge of the final blast (linear falloff from centre). */
    private static final double DEATH_BLAST_EDGE_DMG = 10.0;
    /** Base radius of the 5 s in-combat dig sweep (~9-wide before the irregular ± shell). */
    private static final double DIG_BASE_RADIUS = 4.0;

    // -- charge (dash) attack -------------------------------------------------
    /** Cooldown between charge attempts (1 min). */
    private static final long CHARGE_COOLDOWN_TICKS = 1200L;
    /** While off-cooldown, re-probe feasibility this often (5 s). */
    private static final long CHARGE_CHECK_INTERVAL_TICKS = 100L;
    /** Max straight-line distance to the target for a charge to be possible — matches the target
     *  acquisition range, so the wither can use the dash to close on a fleeing player at any distance
     *  it can still see them. */
    private static final double CHARGE_MAX_RANGE = 40.0;
    /** Windup: freeze + tilt + telegraph line for this long before dashing (1 s). */
    private static final long CHARGE_WINDUP_TICKS = 20L;
    /** Dash speed (~3× cruise). */
    private static final double CHARGE_SPEED_BPS = 18.0;
    /** Forward "helicopter" tilt (pitch°) held during windup + dash. */
    private static final float CHARGE_TILT_PITCH = 45.0f;
    /** Irregular tunnel radius carved along the dash path (~5-wide, dirty). */
    private static final double CHARGE_DIG_RADIUS = 2.5;
    /** Anything within this of the dashing wither takes the one-time hit. */
    private static final double CHARGE_HIT_RADIUS = 2.5;
    /** One-time damage to anything caught in the dash path. */
    private static final double CHARGE_DAMAGE = 20.0;
    /** Safety cap on dash duration (3 s). */
    private static final long CHARGE_DASH_MAX_TICKS = 60L;

    /** PDC key marking an armor stand as a turret phantom. */
    private final NamespacedKey phantomKey;

    private final HomeSystemPlugin plugin;
    private BukkitTask task;
    private BukkitTask witherTask;
    /** Counts master-loop ticks since the last HP regen pulse. */
    private int regenCounter;

    /** Map turret anchor key → phantom armor stand UUID. */
    private final Map<String, UUID> phantomByAnchor = new HashMap<>();
    /** Inverse map for quick damage-event resolution. */
    private final Map<UUID, String> anchorByPhantom = new HashMap<>();
    /** Last HP value displayed on each phantom's nametag — refresh only when it changes. */
    private final Map<UUID, Integer> lastHpShown = new HashMap<>();
    /** Withers currently locked onto a turret. Used to suppress their vanilla skull launches —
     *  we fire our own aimed skulls instead, since vanilla side-head targets are uncontrollable. */
    private final Set<UUID> withersInAttackState = new HashSet<>();
    /** Per-wither cooldown for our custom skull BURSTS (one burst = two shots 0.5 s apart). */
    private final Map<UUID, Long> lastSkullTick = new HashMap<>();
    /** Scheduled tick for the second skull of an in-progress burst, or null if no burst pending. */
    private final Map<UUID, Long> pendingSecondShot = new HashMap<>();
    /** Current wander offset (relative to the turret anchor) used as the wither's hover point. */
    private final Map<UUID, Vector> wanderOffset = new HashMap<>();
    /** Tick at which the wander offset should be re-randomised. */
    private final Map<UUID, Long> nextWanderTick = new HashMap<>();
    /** Per-wither cached lookahead ground Y. Refreshed once per second. */
    private final Map<UUID, Integer> cachedGroundY = new HashMap<>();
    /** Tick at which the ground cache for this wither is stale and should be re-sampled. */
    private final Map<UUID, Long> nextGroundSampleTick = new HashMap<>();
    /** Last position the wither was at when it last moved >= STUCK_MIN_MOVEMENT blocks. */
    private final Map<UUID, Vector> lastMovePos = new HashMap<>();
    /** Tick of the last meaningful move; used to time out into the fallback teleport. */
    private final Map<UUID, Long> lastMoveTick = new HashMap<>();
    /** One-time per-wither flag: max HP bumped to {@link #WITHER_MAX_HP} and topped off. */
    private final Set<UUID> witherHpInit = new HashSet<>();
    /** One-time per-wither flag: the half-HP event (glow + skeleton reinforcements) has fired. */
    private final Set<UUID> halfHpTriggered = new HashSet<>();
    /** Per-wither yaw accumulator for the Bedrock-style 360°/s spawn-ritual spin. */
    private final Map<UUID, Float> ritualYaw = new HashMap<>();
    /** Per-wither cached 3D A* flight route to its current aim, so it climbs over / weaves around
     *  obstacles instead of pressing flat against a single block. Empty when flying in the open. */
    private final Map<UUID, FlightPath> flightPaths = new HashMap<>();
    /** Per-wither tick of the last combat-AI block-break sweep. */
    private final Map<UUID, Long> lastBreakTick = new HashMap<>();
    /** Per-wither tick of the last small "nibble" break of the immediate obstruction (faster than the
     *  big sweep) used when the wither is boxed in and can't sidestep around the blocking block(s). */
    private final Map<UUID, Long> lastNibbleTick = new HashMap<>();
    /** Combat no-progress watchdog: closest squared-distance to the target seen so far, and the tick
     *  it was last improved. If it stops improving for ~1.5 s the wither is stuck (even if it never
     *  flagged a collision — e.g. wedged in an air pocket) and force-digs toward the target. */
    private final Map<UUID, Double> combatBestDistSq = new HashMap<>();
    private final Map<UUID, Long> combatProgressTick = new HashMap<>();
    /** Priority-1 retaliation target: the last living entity to damage the wither, and when. */
    private final Map<UUID, UUID> recentAttacker = new HashMap<>();
    private final Map<UUID, Long> recentAttackerTick = new HashMap<>();
    /** The custom-AI wither-skeleton minions spawned at half HP, by entity UUID. */
    private final Set<UUID> witherMinions = new HashSet<>();
    /** minion entity → server tick of its last landed attack (melee/turret cooldown gate). */
    private final Map<UUID, Long> minionLastAttack = new HashMap<>();
    /** minion entity → who recently hit it (priority-1 retaliation) and when. */
    private final Map<UUID, UUID> minionAttacker = new HashMap<>();
    private final Map<UUID, Long> minionAttackerTick = new HashMap<>();
    /** minion entity → last destination we issued to the vanilla navigator (re-issue throttle). */
    private final Map<UUID, Location> minionNavDest = new HashMap<>();
    /** Set only while WE deal a minion's hit, so the deal-damage listener lets ours through and
     *  cancels any stray vanilla skeleton melee (which would double-hit + apply Wither II). */
    private boolean minionManualHit = false;
    /** Per-wither tick the combat AI was last BLOCKED from moving toward its target (its desired
     *  step ran into solid blocks). The dig sweep only fires when this is recent — so the wither
     *  digs to clear a path it actually needs, not just because terrain happens to be nearby. */
    private final Map<UUID, Long> lastBlockedTick = new HashMap<>();
    /** Withers in the custom death sequence → tick it started. While present the wither is frozen,
     *  invulnerable, spinning up, and ends in the final blast + despawn. */
    private final Map<UUID, Long> dyingSince = new HashMap<>();
    /** Active charge (windup → dash) per wither. */
    private final Map<UUID, Charge> activeCharge = new HashMap<>();
    /** Tick after which a wither may attempt another charge (1-min cooldown). */
    private final Map<UUID, Long> chargeReadyAt = new HashMap<>();
    /** Next tick a wither re-probes charge feasibility (5-s probe while off-cooldown). */
    private final Map<UUID, Long> chargeNextCheck = new HashMap<>();

    /** In-progress charge state. The dash aims at {@link #lockedTarget} (the enemy's position at lock
     *  time), NOT the live player — so a moving player can sidestep it. */
    private static final class Charge {
        Location lockedTarget;
        Location startPos;
        long windupStartTick;
        long dashStartTick;
        boolean dashing;
        final Set<UUID> hit = new HashSet<>();
    }
    /** Per-wither cached combat target id (re-scanned every {@link #COMBAT_ACQUIRE_INTERVAL}). */
    private final Map<UUID, UUID> combatTargetId = new HashMap<>();
    /** wither UUID → current orbit angle (radians) around its combat target. */
    private final Map<UUID, Double> combatOrbitAngle = new HashMap<>();
    /** wither UUID → tick at which it next rotates to a new orbit side. */
    private final Map<UUID, Long> combatOrbitNextChange = new HashMap<>();
    /** Tick at which the cached combat target should be re-scanned. */
    private final Map<UUID, Long> nextAcquireTick = new HashMap<>();
    /** wither UUID → owner UUID of the claim it is currently committed to sieging. Sticky: once a
     *  base is acquired the wither keeps it until that base leaves engage range or loses every live
     *  turret, so it doesn't flip-flop between two nearby bases mid-flight. */
    private final Map<UUID, UUID> engagedClaimByWither = new HashMap<>();
    /** Stuck-fallback threshold: if the wither's net displacement stays under
     *  {@link #STUCK_MIN_MOVEMENT} blocks for this many ticks, fire the fallback. 50 ticks at
     *  the 10 Hz wither-tick = 5 seconds. */
    private static final long STUCK_FALLBACK_TICKS = 50L;
    /** Minimum displacement (blocks) to count as "moved" — anything below this and the stuck
     *  timer keeps accumulating. */
    private static final double STUCK_MIN_MOVEMENT = 0.5;
    /** Initial Y boost on fallback teleport (blocks above current position). If that block is
     *  not air, we walk up until we find one. */
    private static final int STUCK_FALLBACK_LIFT = 10;

    public WitherCombatManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.phantomKey = new NamespacedKey(plugin, "turret_phantom");
    }

    public void start() {
        if (task != null) return;
        clearStrayPhantoms();
        // One-shot structure self-heal at boot. If any ALIVE turret's anchor block is air (i.e.
        // the structure was lost — to the wipeOrphanNPCs structure-clear bug we just fixed, a
        // crash mid-build, etc.), rebuild it. Doesn't touch destroyed-state turrets — those
        // restore via respawnDestroyed on schedule.
        Bukkit.getScheduler().runTaskLater(plugin, this::healMissingStructuresOnce, 40L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        // Separate fast task for wither flight control. Runs every server tick because vanilla
        // overwrites velocity at the same cadence — anything slower than this gets out-fought.
        witherTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> { witherTick(); minionTick(); },
                WITHER_TICK_INTERVAL, WITHER_TICK_INTERVAL);
        // Re-adopt any minions that survived a reload (their AI was suppressed, so without us
        // driving them they'd otherwise stand frozen).
        Bukkit.getScheduler().runTaskLater(plugin, this::readoptMinions, 40L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        if (witherTask != null) { witherTask.cancel(); witherTask = null; }
        for (UUID id : new HashSet<>(anchorByPhantom.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        phantomByAnchor.clear();
        anchorByPhantom.clear();
        lastHpShown.clear();
        withersInAttackState.clear();
        lastSkullTick.clear();
        pendingSecondShot.clear();
        wanderOffset.clear();
        nextWanderTick.clear();
        witherHpInit.clear();
        halfHpTriggered.clear();
        ritualYaw.clear();
        flightPaths.clear();
        lastBreakTick.clear();
        lastNibbleTick.clear();
        combatBestDistSq.clear();
        combatProgressTick.clear();
        recentAttacker.clear();
        recentAttackerTick.clear();
        witherMinions.clear();
        minionLastAttack.clear();
        minionAttacker.clear();
        minionAttackerTick.clear();
        minionNavDest.clear();
        lastBlockedTick.clear();
        dyingSince.clear();
        activeCharge.clear();
        chargeReadyAt.clear();
        chargeNextCheck.clear();
        combatTargetId.clear();
        combatOrbitAngle.clear();
        combatOrbitNextChange.clear();
        nextAcquireTick.clear();
    }

    // -- public hooks ---------------------------------------------------------

    public void onTurretActivated(World world, Turret turret) {
        ensurePhantom(world, turret);
    }

    public void onTurretRemoved(Turret turret) {
        removePhantom(turret);
    }

    public boolean isDestroyed(Turret t) { return t.isDestroyed(); }

    // -- master tick ----------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();

        // 1) Respawn any turrets whose downtime has elapsed.
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World w = Bukkit.getWorld(claim.getWorldId());
            if (w == null) continue;
            for (Turret t : claim.getTurrets()) {
                if (t.isDestroyed() && now >= t.getRespawnAtMillis()) {
                    respawnDestroyed(claim, w, t);
                }
            }
        }

        // 2) Ensure exactly ONE phantom per live turret; refresh HP label only on change.
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World w = Bukkit.getWorld(claim.getWorldId());
            if (w == null) continue;
            for (Turret t : claim.getTurrets()) {
                if (t.isDestroyed()) continue;
                if (!w.isChunkLoaded(t.getX() >> 4, t.getZ() >> 4)) continue;
                // Passive self-heal: if the anchor block (layer-1 centre) is air, the structure
                // was somehow lost while the turret stayed alive. Rebuild it now. Cheap check —
                // single block lookup per turret per second.
                org.bukkit.Material anchorMat = w.getBlockAt(t.getX(), t.getY(), t.getZ()).getType();
                if (anchorMat == org.bukkit.Material.AIR
                        || anchorMat == org.bukkit.Material.CAVE_AIR
                        || anchorMat == org.bukkit.Material.VOID_AIR) {
                    try { TurretStructure.place(w, t); } catch (Throwable ignored) {}
                }
                ArmorStand phantom = ensurePhantom(w, t);
                if (phantom == null) continue;
                dedupePhantomsAt(w, t, phantom);
                refreshLabelIfChanged(phantom, claim, t);
            }
        }

        // 3) HP regen pulse — +1 HP to every live turret up to its tier max, every 2 seconds.
        regenCounter++;
        if (regenCounter >= HP_REGEN_INTERVAL_TICKS) {
            regenCounter = 0;
            applyRegen();
        }
    }

    /**
     * Fast tick — exists to drive the Wither's velocity. Skips world iteration entirely when
     * we know no wither is currently tracked (the common case — most of the time there's no
     * wither in any world at all). The world scan is reserved for when at least one wither has
     * either entered attack state or the cache is empty (cold start / plugin reload).
     */
    private void witherTick() {
        // Fast path: if we previously found a wither and it's still tracked, manage that one
        // directly via UUID lookup. Avoids the O(entities-in-world) class filter every 2 ticks.
        if (!withersInAttackState.isEmpty()) {
            for (UUID wid : new java.util.ArrayList<>(withersInAttackState)) {
                Entity e = Bukkit.getEntity(wid);
                if (e instanceof Wither w) manageWither(w);
                else releaseWither(wid);    // gone — clean up state
            }
        }
        // Slow path: scan worlds for any NEW wither that might have spawned and isn't in our
        // tracked set yet. This is the only place a fresh-spawned wither enters attack state.
        // Run once per second (every 20 wither ticks at INTERVAL=1) so a wither spawning from
        // a player's beacon ritual is picked up within ~1 s. Cheap class filter, infrequent.
        scanCounter++;
        if (scanCounter >= 20) {
            scanCounter = 0;
            for (World w : Bukkit.getWorlds()) {
                for (Wither wither : w.getEntitiesByClass(Wither.class)) {
                    if (!withersInAttackState.contains(wither.getUniqueId())) {
                        // First-sighting path. manageWither will either add it to the set
                        // (if eligible) or no-op.
                        manageWither(wither);
                    }
                }
            }
        }
    }
    /** Counter for the slow-path scan inside {@link #witherTick}. */
    private int scanCounter = 0;

    private void applyRegen() {
        boolean anyChanged = false;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            for (Turret t : claim.getTurrets()) {
                if (t.isDestroyed()) continue;
                int max = Turret.maxHpForLevel(claim.getSlotLevel(t.getSlot()));
                if (t.getStructureHp() >= max) continue;
                int next = Math.min(max, t.getStructureHp() + HP_REGEN_AMOUNT);
                t.setStructureHp(next);
                anyChanged = true;
                // Force-refresh the label since the HP changed silently.
                UUID phid = phantomByAnchor.get(anchorKey(t));
                if (phid != null && Bukkit.getEntity(phid) instanceof ArmorStand a) {
                    a.customName(buildHpLabel(next, max));
                    lastHpShown.put(phid, next);
                }
            }
        }
        if (anyChanged) plugin.getClaimManager().save();
    }

    // -- phantom lifecycle ----------------------------------------------------

    private static String anchorKey(Turret t) {
        return t.getX() + ":" + t.getY() + ":" + t.getZ();
    }

    /** Spawn (or look up) the singleton phantom armor stand for this turret. */
    private ArmorStand ensurePhantom(World world, Turret t) {
        String key = anchorKey(t);
        UUID id = phantomByAnchor.get(key);
        if (id != null) {
            Entity e = Bukkit.getEntity(id);
            if (e instanceof ArmorStand a && !a.isDead()) return a;
            phantomByAnchor.remove(key);
            anchorByPhantom.remove(id);
            lastHpShown.remove(id);
        }
        Location at = new Location(world, t.getX() + 0.5, t.getY() + PHANTOM_DY, t.getZ() + 0.5);
        ArmorStand stand = world.spawn(at, ArmorStand.class, a -> {
            a.setVisible(false);
            a.setMarker(false);
            a.setGravity(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setArms(false);
            a.setCanPickupItems(false);
            a.setRemoveWhenFarAway(false);
            a.setCustomNameVisible(true);
            a.setPersistent(true);
            a.getPersistentDataContainer().set(phantomKey, PersistentDataType.STRING, key);
        });
        phantomByAnchor.put(key, stand.getUniqueId());
        anchorByPhantom.put(stand.getUniqueId(), key);
        return stand;
    }

    /**
     * Walk the loaded entities for this turret's chunk and remove every armor stand tagged with
     * our PDC key whose anchor matches this turret AND whose UUID isn't the one we currently
     * track. Cleans up duplicates that could appear from old sessions or hot-reloads.
     */
    private void dedupePhantomsAt(World world, Turret t, ArmorStand keep) {
        String myKey = anchorKey(t);
        UUID keepId = keep.getUniqueId();
        int cx = t.getX() >> 4, cz = t.getZ() >> 4;
        // Inspect a small window around the anchor chunk to catch strays on chunk edges.
        for (int dcx = -1; dcx <= 1; dcx++) {
            for (int dcz = -1; dcz <= 1; dcz++) {
                if (!world.isChunkLoaded(cx + dcx, cz + dcz)) continue;
                for (Entity e : world.getChunkAt(cx + dcx, cz + dcz).getEntities()) {
                    if (!(e instanceof ArmorStand a)) continue;
                    if (a.getUniqueId().equals(keepId)) continue;
                    String k = a.getPersistentDataContainer().get(phantomKey, PersistentDataType.STRING);
                    if (myKey.equals(k)) a.remove();
                }
            }
        }
    }

    private void removePhantom(Turret t) {
        String key = anchorKey(t);
        UUID id = phantomByAnchor.remove(key);
        if (id == null) return;
        anchorByPhantom.remove(id);
        lastHpShown.remove(id);
        Entity e = Bukkit.getEntity(id);
        if (e != null) e.remove();
    }

    /** Find and remove any armor stand we previously tagged as a turret phantom. */
    private void clearStrayPhantoms() {
        for (World w : Bukkit.getWorlds()) {
            for (ArmorStand a : w.getEntitiesByClass(ArmorStand.class)) {
                if (a.getPersistentDataContainer().has(phantomKey, PersistentDataType.STRING)) {
                    a.remove();
                }
            }
        }
    }

    /**
     * Render the HP label only when the HP value actually changed since the last refresh. Avoids
     * resending entity-metadata every tick (which was causing the visible "blurry" overlap that
     * looked like the digits were doubled).
     */
    private void refreshLabelIfChanged(ArmorStand phantom, Claim claim, Turret t) {
        int hp = t.getStructureHp();
        Integer prev = lastHpShown.get(phantom.getUniqueId());
        if (prev != null && prev == hp) return;
        int max = Turret.maxHpForLevel(claim.getSlotLevel(t.getSlot()));
        phantom.customName(buildHpLabel(hp, max));
        lastHpShown.put(phantom.getUniqueId(), hp);
    }

    /** HP label as a proper Component tree (no legacy §-codes — those rendered as glyphs before). */
    static Component buildHpLabel(int hp, int max) {
        NamedTextColor c = hp < 50  ? NamedTextColor.RED
                         : hp < 100 ? NamedTextColor.GOLD
                                    : NamedTextColor.GREEN;
        return Component.text(hp, c)
                .append(Component.text("/", NamedTextColor.GRAY))
                .append(Component.text(max, c))
                .append(Component.text(" HP", NamedTextColor.GRAY));
    }

    // -- damage interception --------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhantomDamaged(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof ArmorStand stand)) return;
        if (!stand.getPersistentDataContainer().has(phantomKey, PersistentDataType.STRING)) return;
        // The armor stand never loses HP itself.
        e.setCancelled(true);
        // Players can't damage turrets — drop and ignore.
        Entity rootAttacker = resolveRootAttacker(e.getDamager());
        if (rootAttacker instanceof Player) return;
        // Non-wither mobs no longer attack turrets, but we leave this path open so a wither
        // skull that happens to score a direct entity hit on the phantom (instead of exploding
        // against blocks) still counts. EntityExplodeEvent below handles the more common case.
        String key = stand.getPersistentDataContainer().get(phantomKey, PersistentDataType.STRING);
        Turret t = findTurretByAnchorKey(key);
        if (t == null || t.isDestroyed()) return;
        int dmg = (int) Math.max(1, Math.round(e.getDamage()));
        applyStructureDamage(t, dmg, stand, rootAttacker);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhantomEnvironmentDamage(EntityDamageEvent e) {
        if (e instanceof EntityDamageByEntityEvent) return;
        if (!(e.getEntity() instanceof ArmorStand stand)) return;
        if (stand.getPersistentDataContainer().has(phantomKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    /**
     * Single funnel for ALL damage to a controlled wither (melee, explosions, environment, {@code
     * /kill}). While DYING it is fully invulnerable; SUFFOCATION/CRAMMING/CONTACT chip damage from
     * grazing terrain is dropped (this also avoids the hurt-i-frame lockout that made arrows bounce);
     * and the LETHAL blow is intercepted to launch our custom death sequence instead of a vanilla
     * death. (Arrow damage is handled earlier in {@link #onProjectileHitWither}, which cancels the
     * projectile hit so no damage event fires for it.)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onWitherDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Wither w)) return;
        if (!withersInAttackState.contains(w.getUniqueId())) return;
        if (dyingSince.containsKey(w.getUniqueId())) { e.setCancelled(true); return; }
        switch (e.getCause()) {
            case SUFFOCATION, CRAMMING, CONTACT, FLY_INTO_WALL, FALLING_BLOCK -> { e.setCancelled(true); return; }
            default -> { }
        }
        // Remember who hit us → priority-1 retaliation target (fight back).
        if (e instanceof EntityDamageByEntityEvent ev) recordAttacker(w, resolveRootAttacker(ev.getDamager()));
        if (e.getFinalDamage() >= w.getHealth()) {
            e.setCancelled(true);          // don't let vanilla kill it — run the death sequence
            startDying(w);
            return;
        }
        // The wither is setSilent(true) while we drive it, so vanilla's hurt sound is swallowed for
        // melee / fist / any non-arrow hit — the player only saw the red flash. (Arrows re-emit it
        // via applyWitherDamage, which cancels the vanilla hit.) Re-emit it here for every
        // non-lethal blow that lands so all weapons get the audible "wither hurt" feedback.
        playToRadius(w.getLocation(), 32.0, Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f);
    }

    /**
     * Record whoever just hit one of our wither-skeleton minions as its priority-1 retaliation
     * target. Only real players and non-NPC living mobs count — not turrets/raiders (NPCs) or the
     * wither — so a turret bullet doesn't make a minion try to melee an out-of-reach shulker
     * instead of pressing the turret it was already attacking.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinionDamaged(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof WitherSkeleton sk)) return;
        if (!witherMinions.contains(sk.getUniqueId())) return;
        Entity root = resolveRootAttacker(e.getDamager());
        if (!(root instanceof LivingEntity) || root instanceof Wither) return;
        if (root.getUniqueId().equals(sk.getUniqueId())) return;
        try { if (net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(root)) return; } catch (Throwable ignored) {}
        minionAttacker.put(sk.getUniqueId(), root.getUniqueId());
        minionAttackerTick.put(sk.getUniqueId(), (long) Bukkit.getCurrentTick());
    }

    /**
     * Suppress vanilla wither-skeleton melee. We keep the minion's AI on so the vanilla navigator
     * drives movement, but its damage must be EXACTLY our 12 + Wither I — so any hit it deals that
     * ISN'T our own controlled one (flagged by {@link #minionManualHit}) is cancelled. Cancelling
     * the vanilla hit also stops the vanilla Wither II it would otherwise tack on.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMinionDealDamage(EntityDamageByEntityEvent e) {
        if (minionManualHit) return;                       // our controlled 12 + Wither I — allow
        if (!(e.getDamager() instanceof WitherSkeleton sk)) return;
        if (!witherMinions.contains(sk.getUniqueId())) return;
        e.setCancelled(true);
    }

    /**
     * We own minion targeting entirely — cancel every vanilla target acquisition so a minion never
     * aggros on its own. With no vanilla target, the vanilla melee/chase goals never fire and so
     * never hijack the navigation our tick drives; we keep the navigator itself (for free 1-block
     * jumps + water avoidance) but decide where it goes and what it hits.
     */
    @EventHandler(ignoreCancelled = true)
    public void onMinionTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof WitherSkeleton sk)) return;
        if (!witherMinions.contains(sk.getUniqueId())) return;
        e.setCancelled(true);
    }

    /**
     * Suppress vanilla wither-skull launches from any wither currently in turret-attack state.
     * Vanilla skulls aim via head-specific internal targets that Bukkit can't override; ours
     * are spawned manually with a controlled aim vector, so we let them through and cancel
     * the rest. Skulls we just spawned are PDC-tagged with {@code custom_skull} so we don't
     * cancel ourselves on the inbound launch event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof WitherSkull skull)) return;
        // Our own custom skulls (turret OR combat) — pass through.
        String tag = skull.getPersistentDataContainer().get(phantomKey, PersistentDataType.STRING);
        if ("custom_skull".equals(tag) || "combat_skull".equals(tag)) return;
        // Vanilla skulls from a wither in attack mode — drop.
        if (skull.getShooter() instanceof Wither w
                && withersInAttackState.contains(w.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    /**
     * Detect a freshly-spawned wither the instant it appears (player ritual or {@code /summon}) and
     * track it immediately, so the spawn-ritual spin + spawn sound start on the very next tick
     * instead of up to a second later when the slow background scan would otherwise first notice it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWitherSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Wither w) withersInAttackState.add(w.getUniqueId());
    }

    /**
     * Block summoning a Wither INSIDE a protected zone (ritual OR {@code /summon}) — that would let an
     * owner trivially farm it under the safety of their own turrets. Outside zones it's allowed: the
     * spec is "summon it nearby and it flies to the closest base". Cancels the spawn and tells any
     * players near the attempted spawn why. (Runs before the MONITOR tracker above, so a cancelled
     * spawn is never tracked.)
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWitherSpawnInZone(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Wither)) return;
        Location at = e.getLocation();
        if (plugin.getClaimManager().getClaimAt(at) == null) return;   // outside any zone → allowed
        e.setCancelled(true);
        // Refund the ritual materials right where it was attempted, so a blocked summon isn't a loss
        // (the ritual consumes 4 soul sand + 3 wither skulls before the spawn event; /summon doesn't
        // consume anything, so only refund the BUILD_WITHER case).
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_WITHER && at.getWorld() != null) {
            at.getWorld().dropItemNaturally(at, new ItemStack(Material.SOUL_SAND, 4));
            at.getWorld().dropItemNaturally(at, new ItemStack(Material.WITHER_SKELETON_SKULL, 3));
        }
        Component msg = Component.text("✦ A Wither can't be summoned inside a protected zone.", NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.text("Summon it outside — it will fly to the nearest base.", NamedTextColor.GRAY));
        if (at.getWorld() != null) {
            for (Player p : at.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(at) <= 256.0) p.sendMessage(msg);   // within ~16 blocks
            }
        }
    }

    /**
     * Play the wither death roar when one of our controlled withers dies. We keep the wither
     * {@code setSilent(true)} while driving it (so it doesn't emit ambient/hurt noise on every
     * teleport), which also swallows the vanilla death sound — so we re-emit it here as a world
     * sound and drop the wither from our tracking maps.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWitherDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Wither w)) return;
        if (withersInAttackState.contains(w.getUniqueId())) {
            playToRadius(w.getLocation(), 50.0, Sound.ENTITY_WITHER_DEATH, 1.1f, 1.0f);
        }
        releaseWither(w.getUniqueId());
    }

    /**
     * Make player arrows actually hurt the wither instead of bouncing off. Vanilla's wither is
     * arrow-immune above half health (shots deflect for no damage) — we cancel that vanilla
     * interaction and subtract the arrow's damage from the wither's HP directly, so a bow is a
     * viable weapon against it. Only player-fired arrows/tridents count; our own skulls and the
     * turret shulker bullets are left alone.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProjectileHitWither(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.AbstractArrow arrow)) return;
        if (!(arrow.getShooter() instanceof Player)) return;
        // Resolve the wither: direct entity hit first, else a wither right at the impact point
        // (covers the arrow grazing the block face "in front of" a wither that's grazing terrain).
        Wither w = (e.getHitEntity() instanceof Wither hw) ? hw : null;
        if (w == null) {
            Location at = arrow.getLocation();
            for (Entity n : at.getWorld().getNearbyEntities(at, 1.5, 1.5, 1.5)) {
                if (n instanceof Wither nw) { w = nw; break; }
            }
        }
        if (WITHER_DEBUG) {
            plugin.getLogger().info("[wither-arrow] hit: entity="
                    + (e.getHitEntity() == null ? "null" : e.getHitEntity().getType())
                    + " block=" + (e.getHitBlock() == null ? "null" : e.getHitBlock().getType())
                    + " resolvedWither=" + (w != null));
        }
        if (w == null) return;
        // Cancel the vanilla hit (the deflect/bounce + any no-damage) and apply the arrow's damage
        // ourselves so a bow actually works; removing the arrow stops the bounce-back.
        e.setCancelled(true);
        if (arrow.getShooter() instanceof Player shooter) recordAttacker(w, shooter);
        double speed = Math.max(0.5, arrow.getVelocity().length());
        double dmg = Math.max(1.0, arrow.getDamage() * speed);
        applyWitherDamage(w, dmg);
        arrow.remove();
        if (WITHER_DEBUG) logWither(w, String.format("ARROW HIT -%.1f → hp=%.0f", dmg, w.getHealth()));
    }

    /**
     * The main wither damage path. When a {@link WitherSkull} explodes inside any claim, credit
     * the explosion's damage to the nearest live turret in that claim. The spec was explicit
     * that turret HP loss "reflects whatever damage the wither inflicts" rather than a custom
     * number; the skull's raw damage is what vanilla would have dealt, so that's what we use.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWitherSkullExplosion(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof WitherSkull skull0)) return;
        // Combat-AI skulls don't credit turret HP — they splash Wither II on entities near the
        // blast and never grief terrain. Handled separately, then we're done.
        String launchTag = skull0.getPersistentDataContainer().get(phantomKey, PersistentDataType.STRING);
        if ("combat_skull".equals(launchTag)) { applyCombatSkullEffect(skull0, e); return; }
        Location at = e.getLocation();
        Claim claim = plugin.getClaimManager().getClaimAt(at);
        if (claim == null) return;
        Turret nearest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Turret t : claim.getTurrets()) {
            if (t.isDestroyed()) continue;
            double dx = (t.getX() + 0.5) - at.getX();
            double dy = (t.getY() + PHANTOM_DY) - at.getY();
            double dz = (t.getZ() + 0.5) - at.getZ();
            double ds = dx * dx + dy * dy + dz * dz;
            if (ds < bestDistSq) { bestDistSq = ds; nearest = t; }
        }
        if (nearest == null) return;
        // Per-skull damage. Per the user's research the canonical wither-skull damage at point-
        // blank is 12 (direct hit + the blast's explosion radius bracket combined), not the
        // bare 8 we had before. Single canonical value rather than reading per-explosion fields —
        // simpler and predictable.
        String k = anchorKey(nearest);
        UUID phid = phantomByAnchor.get(k);
        ArmorStand phantom = (phid != null && Bukkit.getEntity(phid) instanceof ArmorStand a) ? a : null;
        // Wither skulls carry a shooter — credit the wither itself as the damager so the
        // destroy message reads "destroyed by Wither" rather than crediting the skull.
        Entity shooter = null;
        if (e.getEntity() instanceof org.bukkit.entity.WitherSkull skull) {
            org.bukkit.projectiles.ProjectileSource src = skull.getShooter();
            if (src instanceof Entity en) shooter = en;
        }
        applyStructureDamage(nearest, 12, phantom, shooter);
    }

    private Turret findTurretByAnchorKey(String key) {
        if (key == null) return null;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            for (Turret t : claim.getTurrets()) {
                if (anchorKey(t).equals(key)) return t;
            }
        }
        return null;
    }

    private static Entity resolveRootAttacker(Entity damager) {
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Entity e) return e;
            return null;
        }
        return damager;
    }

    // -- wither management ----------------------------------------------------

    /**
     * Per-tick driver for a single Wither. Vanilla AI is wiped at ALL times (NoAI) — the wither runs
     * on one of two strategies that we drive by hand:
     * <ul>
     *   <li><b>Zone AI</b> ({@link #flyTowardAndTarget}) — UNCHANGED. Engaged whenever a claim with
     *       at least one alive turret is in range: cruise in low, hover above the turret, rain
     *       aimed skulls on the structure.</li>
     *   <li><b>Combat AI</b> ({@link #combatAi}) — everything else: hunt the nearest player (then
     *       friendly mob), hover above it and fire aimed Wither-II skulls, tearing through terrain
     *       it gets wedged in.</li>
     * </ul>
     * The only exit is {@link #releaseWither} on death/despawn; we never hand a living wither back
     * to vanilla AI.
     */
    private void manageWither(Wither wither) {
        if (wither.isDead() || !wither.isValid()) {
            releaseWither(wither.getUniqueId());
            return;
        }
        // Custom death sequence takes over completely once it has begun (frozen, spinning up, then
        // the final blast). No normal AI, no target logic.
        if (dyingSince.containsKey(wither.getUniqueId())) { deathSequenceTick(wither); return; }
        // Track from first sighting so the per-tick fast path drives this wither EVERY tick — needed
        // for both a smooth ritual spin and zero-gap flight control.
        boolean newly = withersInAttackState.add(wither.getUniqueId());
        if (newly && WITHER_DEBUG) logWither(wither, "TRACKED");
        // An in-progress charge (windup + dash) pre-empts BOTH movement modes until it finishes.
        if (activeCharge.containsKey(wither.getUniqueId())) {
            applyWitherControl(wither);
            chargeTick(wither, Bukkit.getCurrentTick());
            return;
        }

        // Spawn ritual: getInvulnerabilityTicks() > 0 means the vanilla soul-sand summon charge-up
        // is still running (immobile, not yet full HP). We must let vanilla finish it — but we add
        // the Bedrock-style 360°/s self-spin on top. No AI/HP/invuln changes here (those would abort
        // the ritual); control proper takes over once the countdown hits 0.
        try {
            if (wither.getInvulnerabilityTicks() > 0) { spinDuringRitual(wither); return; }
        } catch (Throwable ignored) {}

        // Mode select. Prefer the (unchanged) zone siege AI when the wither is committed to a claim
        // that still has a live turret; otherwise fall through to the general combat AI.
        Claim claim = resolveEngagedClaim(wither);
        Turret target = null;
        if (claim != null && claim.getWorldId().equals(wither.getWorld().getUID())) {
            target = pickClosestAliveTurret(wither, claim);
        }
        if (target != null) flyTowardAndTarget(wither, target);   // ZONE AI — unchanged
        else combatAi(wither);                                    // NEW general combat AI
    }

    /** Wither-state event logger. */
    private void logWither(Wither wither, String msg) {
        plugin.getLogger().info("[wither " + wither.getUniqueId().toString().substring(0, 8)
                + " hp=" + ((int) wither.getHealth()) + "] " + msg);
    }

    /**
     * The claim this wither is committed to sieging right now, or null. Resolution order:
     * <ol>
     *   <li>If the wither is physically inside a claim that still has a live turret, that claim
     *       wins outright and becomes (or stays) the committed target.</li>
     *   <li>Otherwise keep the previously-committed claim as long as it still exists, is in this
     *       world, still has a live turret, and is within {@link #WITHER_ENGAGE_PROXIMITY} — this is
     *       what stops the wither flip-flopping between two nearby bases as it flies between them.</li>
     *   <li>If there's no valid commitment, acquire the nearest in-range claim with a live turret
     *       (exact-distance ties broken randomly) and commit to it — or null if none is in range.</li>
     * </ol>
     */
    private Claim resolveEngagedClaim(Wither wither) {
        UUID wid = wither.getUniqueId();
        java.util.UUID worldId = wither.getWorld().getUID();

        Claim inside = plugin.getClaimManager().getClaimAt(wither.getLocation());
        if (inside != null && claimHasLiveTurret(inside)) {
            engagedClaimByWither.put(wid, inside.getOwner());
            return inside;
        }

        UUID committedOwner = engagedClaimByWither.get(wid);
        if (committedOwner != null) {
            Claim committed = plugin.getClaimManager().getClaimOf(committedOwner);
            if (committed != null && committed.getWorldId().equals(worldId)
                    && claimHasLiveTurret(committed) && withinEngageRange(wither, committed)) {
                return committed;                  // stick with the current target (anti-oscillation)
            }
            engagedClaimByWither.remove(wid);      // target gone / disarmed / out of range → drop it
        }

        Claim fresh = findNearbyClaimWithLiveTurret(wither);
        if (fresh != null) engagedClaimByWither.put(wid, fresh.getOwner());
        return fresh;
    }

    private static boolean claimHasLiveTurret(Claim c) {
        for (Turret t : c.getTurrets()) if (!t.isDestroyed()) return true;
        return false;
    }

    /** True if the claim's XZ edge is within {@link #WITHER_ENGAGE_PROXIMITY} of the wither. */
    private boolean withinEngageRange(Wither wither, Claim c) {
        Location wloc = wither.getLocation();
        double dxOut = Math.max(0.0, Math.max(c.getMinX() - wloc.getX(), wloc.getX() - c.getMaxX()));
        double dzOut = Math.max(0.0, Math.max(c.getMinZ() - wloc.getZ(), wloc.getZ() - c.getMaxZ()));
        return dxOut * dxOut + dzOut * dzOut <= WITHER_ENGAGE_PROXIMITY * WITHER_ENGAGE_PROXIMITY;
    }

    /** Acquire the nearest claim with a live turret within {@link #WITHER_ENGAGE_PROXIMITY} blocks of
     *  the wither's XZ position, or null. Exact-distance ties are broken randomly so the wither
     *  commits to one of several equidistant bases instead of dithering between them. */
    private Claim findNearbyClaimWithLiveTurret(Wither wither) {
        Location wloc = wither.getLocation();
        java.util.UUID worldId = wither.getWorld().getUID();
        double bestDistSq = Double.MAX_VALUE;
        java.util.List<Claim> tied = new java.util.ArrayList<>();
        double maxSq = WITHER_ENGAGE_PROXIMITY * WITHER_ENGAGE_PROXIMITY;
        for (Claim c : plugin.getClaimManager().all().values()) {
            if (!c.getWorldId().equals(worldId)) continue;
            if (!claimHasLiveTurret(c)) continue;
            double dxOut = Math.max(0.0, Math.max(c.getMinX() - wloc.getX(), wloc.getX() - c.getMaxX()));
            double dzOut = Math.max(0.0, Math.max(c.getMinZ() - wloc.getZ(), wloc.getZ() - c.getMaxZ()));
            double dSq = dxOut * dxOut + dzOut * dzOut;
            if (dSq > maxSq) continue;
            if (dSq < bestDistSq - 1.0e-6) {                  // strictly closer → new sole best
                bestDistSq = dSq;
                tied.clear();
                tied.add(c);
            } else if (Math.abs(dSq - bestDistSq) <= 1.0e-6) { // exact tie → candidate pool
                tied.add(c);
            }
        }
        if (tied.isEmpty()) return null;
        if (tied.size() == 1) return tied.get(0);
        return tied.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(tied.size()));
    }

    /** Closest alive turret in this claim by XZ distance from the wither. */
    private static Turret pickClosestAliveTurret(Wither wither, Claim claim) {
        Location from = wither.getLocation();
        Turret best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Turret t : claim.getTurrets()) {
            if (t.isDestroyed()) continue;
            double dx = (t.getX() + 0.5) - from.getX();
            double dz = (t.getZ() + 0.5) - from.getZ();
            double ds = dx * dx + dz * dz;
            if (ds < bestDistSq) { bestDistSq = ds; best = t; }
        }
        return best;
    }

    /**
     * Drive the wither toward its current hover point each server tick. The hover point
     * <i>wanders</i> within a 5-block-radius cylinder around the turret centre, refreshing
     * every {@link #WANDER_REFRESH_TICKS} so the wither looks like it's circling rather than
     * standing on a flagpole. Hover Y:
     * <ul>
     *   <li>HP &gt; {@value #WITHER_PHASE2_HP} → 10–12 blocks above the anchor (phase-1 altitude)</li>
     *   <li>HP &le; {@value #WITHER_PHASE2_HP} → 8–10 blocks above the anchor (phase-2: vanilla
     *       wither drops down once it loses its armour shield, we mimic that here)</li>
     * </ul>
     *
     * <p><b>Suppression of vanilla shooting</b>: we set the wither's target to {@code null}
     * each tick. With no main target, vanilla's range-attack goal never fires, so neither the
     * skulls nor the shoot SOUND is emitted by the engine. We handle both ourselves below.
     *
     * <p><b>Burst-fire pattern</b>: every {@link #CUSTOM_SKULL_INTERVAL_TICKS} we schedule a
     * burst of two skulls, the second {@link #BURST_SECOND_SHOT_DELAY} ticks after the first,
     * matching vanilla wither's 1-then-pause-then-1 attack cadence. Each skull plays
     * {@link Sound#ENTITY_WITHER_SHOOT}, restoring the audio that {@code setTarget(null)}
     * silenced.
     */
    /**
     * Per-tick drive logic. Two phases:
     * <ol>
     *   <li><b>Cruise:</b> horizontally far from the target turret. Aim point is
     *       {@code (turret.X, ground.Y + cruiseAlt, turret.Z)} — terrain-following: the cruise Y
     *       sits a few blocks above the local ground at the WITHER's XZ, not the turret's. Wither
     *       flies in low and skims over the terrain on the way in.</li>
     *   <li><b>Hover-above:</b> horizontally within {@link #WITHER_HOVER_APPROACH_DIST_XZ} blocks
     *       of the turret. Aim point is the original hover stack:
     *       {@code (turret.X + wander.dx, turret.Y + hoverDy + wander.dy, turret.Z + wander.dz)}
     *       — wanders in a small cylinder above the turret while firing skulls down.</li>
     * </ol>
     * Phase-2 (HP ≤ {@value #PHASE2_HP_THRESHOLD}) drops the cruise altitude and the hover-above
     * altitude. Movement is teleport-based at {@link #WITHER_TRAVEL_SPEED} b/t — engine physics
     * was cancelling setVelocity, so we drive position directly.
     */
    private void flyTowardAndTarget(Wither wither, Turret target) {
        // All per-tick vanilla suppression (NoAI etc.), the 600-HP pool + self-heal, boss-bar sync
        // and the half-HP event live in one shared place now (also used by the combat AI).
        applyWitherControl(wither);

        UUID wid = wither.getUniqueId();
        long now = Bukkit.getCurrentTick();
        boolean phase2 = wither.getHealth() <= PHASE2_HP_THRESHOLD;
        double hoverDy = phase2 ? HOVER_DY_PHASE2 : HOVER_DY_PHASE1;
        double cruiseAlt = phase2 ? CRUISE_ALT_PHASE2 : CRUISE_ALT_PHASE1;

        // Horizontal distance to turret centre — decides cruise vs hover-above.
        Location current = wither.getLocation();
        World world = wither.getWorld();
        double centreDx = (target.getX() + 0.5) - current.getX();
        double centreDz = (target.getZ() + 0.5) - current.getZ();
        double horizDist = Math.sqrt(centreDx * centreDx + centreDz * centreDz);
        boolean hoverPhase = horizDist <= WITHER_HOVER_APPROACH_DIST_XZ;

        // Vertical sin-bob applied on top of the computed aim Y — gentle ±0.5 block up/down
        // wobble like a vanilla wither hovering. Phase comes from current tick count so it
        // doesn't reset every state change.
        double bob = Math.sin((now / WITHER_BOB_PERIOD_TICKS) * 2.0 * Math.PI) * WITHER_BOB_AMPLITUDE;

        // Compute the aim point for this tick.
        Location aim;
        if (hoverPhase) {
            // Wander offset refreshed only while in hover phase, every 3 s.
            Long nextRefresh = nextWanderTick.get(wid);
            if (nextRefresh == null || now >= nextRefresh) {
                wanderOffset.put(wid, pickWanderOffset(phase2));
                nextWanderTick.put(wid, now + WANDER_REFRESH_TICKS);
            }
            Vector wander = wanderOffset.get(wid);
            if (wander == null) wander = new Vector(0, 0, 0);
            aim = new Location(world,
                    target.getX() + 0.5 + wander.getX(),
                    target.getY() + hoverDy + wander.getY() + bob,
                    target.getZ() + 0.5 + wander.getZ());
        } else {
            // Cruise altitude follows the GROUND a few blocks AHEAD of the wither (along the
            // direction of travel), so the wither starts rising BEFORE hitting a mountain face
            // rather than at the mountain. Sample is cached for 1 s to keep CPU minimal.
            int groundY = sampleLookaheadGround(world, wither, current, target);
            // ...but also climb toward the turret's own hover altitude while cruising in, so a
            // turret perched up a mountain pulls the wither UP as it approaches (fly up AND toward
            // it at once) instead of hugging the valley floor until it face-plants the cliff and
            // has to be unstuck-teleported. Take the HIGHER of the terrain-follow floor and the
            // turret hover Y.
            double terrainFloorY = groundY + cruiseAlt;
            double turretHoverY = target.getY() + hoverDy;
            aim = new Location(world,
                    target.getX() + 0.5,
                    Math.max(terrainFloorY, turretHoverY) + bob,
                    target.getZ() + 0.5);
        }

        // Per-axis step clamping. Horizontal and vertical have independent speed limits so
        // vertical movement reads as gentle rises/falls (1 b/s) while horizontal travel stays
        // fast (3 b/s cruise, 1 b/s hover).
        double horizMaxBps = hoverPhase ? WITHER_HOVER_SPEED_BPS : WITHER_CRUISE_SPEED_BPS;
        double horizMaxStep = horizMaxBps * (WITHER_TICK_INTERVAL / 20.0);
        double vertMaxStep = WITHER_VERT_SPEED_BPS * (WITHER_TICK_INTERVAL / 20.0);

        double dx = aim.getX() - current.getX();
        double dy = aim.getY() - current.getY();
        double dz = aim.getZ() - current.getZ();
        double horizDist3d = Math.sqrt(dx * dx + dz * dz);
        double horizStep = Math.min(horizDist3d, horizMaxStep);
        double sx = horizDist3d > 1e-6 ? (dx / horizDist3d) * horizStep : 0.0;
        double sz = horizDist3d > 1e-6 ? (dz / horizDist3d) * horizStep : 0.0;
        double sy = Math.signum(dy) * Math.min(Math.abs(dy), vertMaxStep);
        Vector step = new Vector(sx, sy, sz);

        // Ground-clamp (cruise only): the cached lookahead Y sets a minimum aim Y, but the
        // wither can only rise vertMaxStep per step — so on a steep mountain the aim might be
        // 30 blocks above current but the step only rises 0.1. The wither's XZ still moves at
        // horizMaxStep — that's what would normally cause clipping. The block-collision check
        // below catches that.
        Location next = current.clone().add(step);
        if (!hoverPhase) {
            int aheadGroundY = sampleLookaheadGround(world, wither, current, target);
            double minY = aheadGroundY + cruiseAlt;
            if (next.getY() < minY) next.setY(Math.min(minY, current.getY() + vertMaxStep));
        }

        // Block collision — if the wither's body at NEXT position intersects any solid block,
        // cancel the XZ component and CLIMB to clear it. Critically, never DESCEND while blocked.
        //
        // The previous version kept whatever vertical step the AIM implied here. That caused the
        // "idle forever" wedge the user reported: a blocked wither would get lifted (by the stuck-
        // fallback or its own rise), but because the cruise aim altitude is low (ground + a few),
        // the very next step's dy was NEGATIVE, so it stepped straight back DOWN into the same
        // pocket — rise, sink, rise, sink, pinned at one XZ permanently. Forcing a positive climb
        // (at the horizontal cruise speed, so tall obstacles clear quickly) makes the wither rise
        // monotonically until its XZ path to the turret opens, then normal flight resumes.
        if (wouldCollide(world, next)) {
            // Blocked at 'next' (wall/ledge/overhang ahead). Hold XZ and make UPWARD progress.
            // FIRST preference: if the space directly above the head is clear, rise at full vertical
            // speed — this is the fix for "the wither flies flat against the block without going up"
            // (it should keep climbing past a ledge instead of pinning to its face). Only if the head
            // itself is capped do we scan for the first whole-block clear body slot. Never descend.
            next.setX(current.getX());
            next.setZ(current.getZ());
            double wantUp = aim.getY() - current.getY();
            if (wantUp > 0.0 && !wouldCollide(world, current.clone().add(0.0, vertMaxStep, 0.0))) {
                next.setY(current.getY() + Math.min(wantUp, vertMaxStep));
            } else {
                int clearY = firstClearBodyY(world, current);
                double rise = Math.max(vertMaxStep, horizMaxStep);
                next.setY(clearY < 0 ? current.getY() : Math.min((double) clearY, current.getY() + rise));
            }
        }

        // Rotation:
        //  - Hover phase: face the turret centre (steady look-at, no spin since turret is fixed).
        //  - Cruise phase: face the direction of travel — the wither looks where it's going.
        //    Only updates when the horizontal step is non-trivial (>1 cm) so the wither doesn't
        //    spin when it momentarily stops to rise over a mountain.
        Vector lookDir = null;
        if (hoverPhase) {
            Location turretCentre = new Location(world,
                    target.getX() + 0.5, target.getY() + PHANTOM_DY, target.getZ() + 0.5);
            lookDir = turretCentre.toVector().subtract(next.toVector());
        } else if (horizStep > 0.01) {
            lookDir = new Vector(sx, sy, sz);
        }
        if (lookDir != null && lookDir.lengthSquared() > 1e-6) {
            Location oriented = next.clone();
            oriented.setDirection(lookDir);
            next.setYaw(oriented.getYaw());
            next.setPitch(oriented.getPitch());
        }

        try { wither.teleport(next); } catch (Throwable ignored) {}
        // Re-zero post-teleport so the wither carries no motion into the next physics tick. Our
        // position is fully teleport-driven; any non-zero velocity here is pure drift.
        try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}

        // Stuck-fallback: track meaningful movement. If the wither hasn't budged for 5 s, lift
        // it up by STUCK_FALLBACK_LIFT blocks (more if it's buried inside terrain). Covers the
        // rare case where the per-axis ground-clamp + block-collision logic leaves the wither
        // wedged with no clear up path — e.g. boxed in by a building or cave ceiling.
        Vector lastMove = lastMovePos.get(wid);
        if (lastMove == null || next.toVector().distance(lastMove) >= STUCK_MIN_MOVEMENT) {
            lastMovePos.put(wid, next.toVector());
            lastMoveTick.put(wid, now);
        } else if (now - lastMoveTick.getOrDefault(wid, now) >= STUCK_FALLBACK_TICKS) {
            // Find the first AIR block above current Y + STUCK_FALLBACK_LIFT. Skip the block
            // probing entirely if the column's chunk isn't loaded — don't force-generate terrain
            // on the main thread; just lift by the fixed amount.
            int liftY = next.getBlockY() + STUCK_FALLBACK_LIFT;
            if (world.isChunkLoaded(next.getBlockX() >> 4, next.getBlockZ() >> 4)) {
                for (int i = 0; i < 30; i++) {
                    org.bukkit.Material m = world.getBlockAt(next.getBlockX(), liftY, next.getBlockZ()).getType();
                    if (m == org.bukkit.Material.AIR || m == org.bukkit.Material.CAVE_AIR) break;
                    liftY++;
                }
            }
            Location lifted = new Location(world, next.getX(), liftY, next.getZ(), next.getYaw(), next.getPitch());
            try { wither.teleport(lifted); } catch (Throwable ignored) {}
            lastMovePos.put(wid, lifted.toVector());
            lastMoveTick.put(wid, now);
            if (WITHER_DEBUG) logWither(wither, "STUCK 5s → lifted to Y=" + liftY);
        }

        // Skull fire — only while in hover phase, with proper altitude above turret.
        if (!hoverPhase) return;
        double dyAboveTurret = current.getY() - target.getY();
        if (dyAboveTurret < hoverDy - 4 || dyAboveTurret > hoverDy + 4) return;
        Location skullTarget = new Location(world,
                target.getX() + 0.5, target.getY() + PHANTOM_DY, target.getZ() + 0.5);
        Long pendingSecond = pendingSecondShot.get(wid);
        if (pendingSecond != null && now >= pendingSecond) {
            fireCustomSkull(wither, skullTarget);
            pendingSecondShot.remove(wid);
        } else {
            Long lastBurst = lastSkullTick.get(wid);
            if (lastBurst == null || now - lastBurst >= CUSTOM_SKULL_INTERVAL_TICKS) {
                fireCustomSkull(wither, skullTarget);
                lastSkullTick.put(wid, now);
                pendingSecondShot.put(wid, now + BURST_SECOND_SHOT_DELAY);
            }
        }
    }

    /**
     * Does the wither's FULL body box (real ~0.9-wide × ~3.5-tall footprint, expanded by
     * {@code margin}) intersect any solid block at the given centre? This replaces the old
     * single-column probe that only looked at one block under the wither's feet — which let the body
     * sit ~half a block inside adjacent blocks and made the dig gate miss nearby blocks. Chunk-load
     * safe: an unloaded column is treated as clear so we never force synchronous generation.
     */
    private static boolean bodyHitsSolid(World world, double cx, double yBase, double cz, double margin) {
        double r = WITHER_HALF_WIDTH + margin;
        int minX = (int) Math.floor(cx - r), maxX = (int) Math.floor(cx + r);
        int minZ = (int) Math.floor(cz - r), maxZ = (int) Math.floor(cz + r);
        int minY = (int) Math.floor(yBase), maxY = (int) Math.floor(yBase + WITHER_BODY_HEIGHT + margin);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockAt(x, y, z).getType().isSolid()) return true;
                }
            }
        }
        return false;
    }

    /** Wither body would intersect a solid block at this position? Uses the full body footprint so
     *  the wither stops at block faces instead of flying half-inside them. */
    private static boolean wouldCollide(World world, Location pos) {
        return bodyHitsSolid(world, pos.getX(), pos.getY(), pos.getZ(), WITHER_COLLISION_MARGIN);
    }

    /**
     * Lowest Y at/above {@code footPos} where the wither's full body box is clear of solid blocks,
     * or {@code -1} if none within a 32-block scan (fully capped, e.g. boxed in a cave). Used by the
     * blocked-climb logic so the wither rises by WHOLE blocks until it fits.
     */
    private static int firstClearBodyY(World world, Location footPos) {
        int bx = footPos.getBlockX(), bz = footPos.getBlockZ();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return footPos.getBlockY();   // unloaded → treat clear
        double cx = footPos.getX(), cz = footPos.getZ();
        int startY = footPos.getBlockY();
        for (int y = startY; y <= startY + 32; y++) {
            if (!bodyHitsSolid(world, cx, (double) y, cz, WITHER_COLLISION_MARGIN)) return y;
        }
        return -1;
    }

    /** Return the highest non-air block Y at the lookahead point — a few blocks in the direction
     *  of travel from the wither's current position toward the turret. Cached per-wither and
     *  refreshed once per second so the per-tick cost is essentially zero. */
    private int sampleLookaheadGround(World world, Wither wither, Location current, Turret target) {
        UUID wid = wither.getUniqueId();
        long now = Bukkit.getCurrentTick();
        Long next = nextGroundSampleTick.get(wid);
        if (next != null && now < next) {
            Integer cached = cachedGroundY.get(wid);
            if (cached != null) return cached;
        }
        double dx = (target.getX() + 0.5) - current.getX();
        double dz = (target.getZ() + 0.5) - current.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        int sampleX, sampleZ;
        if (horiz > 1e-6) {
            sampleX = (int) Math.floor(current.getX() + (dx / horiz) * WITHER_GROUND_LOOKAHEAD);
            sampleZ = (int) Math.floor(current.getZ() + (dz / horiz) * WITHER_GROUND_LOOKAHEAD);
        } else {
            sampleX = current.getBlockX();
            sampleZ = current.getBlockZ();
        }
        // Never force-generate the lookahead chunk. If it isn't loaded, reuse the last cached
        // ground (or fall back to the wither's current Y) instead of triggering a synchronous
        // chunk generation on the main thread — this lookahead point sits AHEAD of the wither,
        // exactly where unloaded/ungenerated terrain is most likely, so this is the prime
        // TPS-spike site. The wither just holds altitude until it reaches loaded ground.
        if (!world.isChunkLoaded(sampleX >> 4, sampleZ >> 4)) {
            Integer cached = cachedGroundY.get(wid);
            return cached != null ? cached : current.getBlockY();
        }
        int ground = world.getHighestBlockYAt(sampleX, sampleZ);
        cachedGroundY.put(wid, ground);
        nextGroundSampleTick.put(wid, now + WITHER_GROUND_REFRESH_TICKS);
        return ground;
    }

    /** Random wander offset inside the small hover cylinder above the turret. Phase 1 picks
     *  Y in [10, 12], phase 2 picks Y in [8, 10]. XZ uniform in ±{@link #WITHER_WANDER_RADIUS_XZ}. */
    private static Vector pickWanderOffset(boolean phase2) {
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        double dx = (r.nextDouble() - 0.5) * 2 * WITHER_WANDER_RADIUS_XZ;
        double dz = (r.nextDouble() - 0.5) * 2 * WITHER_WANDER_RADIUS_XZ;
        double yLo = phase2 ? -1.0 : -1.0;     // ± around the hoverDy band centre
        double yHi = phase2 ?  1.0 :  1.0;
        double y = yLo + r.nextDouble() * (yHi - yLo);
        return new Vector(dx, y, dz);
    }

    /** Drop the wither out of attack state and restore vanilla AI / sound / gravity. */
    private void releaseWither(UUID wid) {
        boolean wasInAttack = withersInAttackState.remove(wid);
        lastSkullTick.remove(wid);
        pendingSecondShot.remove(wid);
        wanderOffset.remove(wid);
        nextWanderTick.remove(wid);
        cachedGroundY.remove(wid);
        nextGroundSampleTick.remove(wid);
        lastMovePos.remove(wid);
        lastMoveTick.remove(wid);
        witherHpInit.remove(wid);
        halfHpTriggered.remove(wid);
        ritualYaw.remove(wid);
        flightPaths.remove(wid);
        lastBreakTick.remove(wid);
        lastNibbleTick.remove(wid);
        combatBestDistSq.remove(wid);
        combatProgressTick.remove(wid);
        recentAttacker.remove(wid);
        recentAttackerTick.remove(wid);
        lastBlockedTick.remove(wid);
        dyingSince.remove(wid);
        activeCharge.remove(wid);
        chargeReadyAt.remove(wid);
        chargeNextCheck.remove(wid);
        combatTargetId.remove(wid);
        nextAcquireTick.remove(wid);
        engagedClaimByWither.remove(wid);
        combatOrbitAngle.remove(wid);
        combatOrbitNextChange.remove(wid);
        if (wasInAttack) {
            Entity e = Bukkit.getEntity(wid);
            if (e instanceof Wither w) {
                try { if (!w.isAware()) w.setAware(true); } catch (Throwable ignored) {}
                try { if (!w.hasAI()) w.setAI(true); } catch (Throwable ignored) {}
                try { if (w.isSilent()) w.setSilent(false); } catch (Throwable ignored) {}
                try { if (!w.hasGravity()) w.setGravity(true); } catch (Throwable ignored) {}
                try { if (!w.isCollidable()) w.setCollidable(true); } catch (Throwable ignored) {}
                try { if (w.isInvulnerable()) w.setInvulnerable(false); } catch (Throwable ignored) {}
                if (WITHER_DEBUG) logWither(w, "RELEASED → vanilla AI restored");
            }
        }
    }

    // -- shared wither control + combat AI -------------------------------------

    /**
     * Per-tick wither control shared by BOTH the zone (turret) AI and the combat AI. Fully wipes
     * vanilla AI (NoAI) and keeps the wither silent / weightless / non-collidable so our teleport
     * pathing is authoritative, manages the {@value #WITHER_MAX_HP}-HP pool (self-heal 1 HP/s only
     * while below {@value #WITHER_REGEN_CEILING}), keeps the boss bar in sync (vanilla's broadcast
     * lives in the suppressed aiStep), and fires the one-shot half-HP event (glow + skeleton
     * reinforcements).
     */
    private void applyWitherControl(Wither wither) {
        UUID wid = wither.getUniqueId();

        // One-time HP init: bump max to 600 and top the wither off.
        if (witherHpInit.add(wid)) {
            try {
                org.bukkit.attribute.AttributeInstance attr =
                        wither.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                if (attr != null && attr.getBaseValue() < WITHER_MAX_HP) {
                    attr.setBaseValue(WITHER_MAX_HP);
                    wither.setHealth(WITHER_MAX_HP);
                }
            } catch (Throwable ignored) {}
        }

        // Full vanilla-AI suppression, applied EVERY tick so the goal selector never re-asserts.
        // setAware(false) alone does NOT stop a wither that acquired a player target (its targeted
        // movement runs outside the aware gate) — the full NoAI flag is the hard stop. We drive
        // position by teleport and fire skulls by hand, so losing vanilla AI costs us nothing.
        try { if (wither.getInvulnerabilityTicks() > 0) wither.setInvulnerabilityTicks(0); } catch (Throwable ignored) {}
        try { if (wither.isAware()) wither.setAware(false); } catch (Throwable ignored) {}
        try { if (wither.hasAI()) wither.setAI(false); } catch (Throwable ignored) {}
        try { if (!wither.isSilent()) wither.setSilent(true); } catch (Throwable ignored) {}
        try { if (wither.hasGravity()) wither.setGravity(false); } catch (Throwable ignored) {}
        // MUST stay collidable: a non-collidable entity lets arrows/projectiles pass straight
        // THROUGH it (the shoot-through-NPC trick) — that's why bows did nothing (arrows flew past
        // and stuck in the terrain behind). We don't need non-collidable for shove-protection
        // anyway: NoAI + the per-tick teleport + velocity-zero already pin it on our path.
        try { if (!wither.isCollidable()) wither.setCollidable(true); } catch (Throwable ignored) {}
        // Clear a STUCK invulnerability flag. Only the death sequence sets setInvulnerable(true); if
        // that sequence was ever interrupted (e.g. the dying wither's chunk unloaded → releaseWither
        // cleared dyingSince but the flag persisted on the entity), the wither would come back as a
        // god-mode boss that turrets/players can't scratch. This (running only for NON-dying withers)
        // guarantees the flag is off whenever it isn't actively dying.
        try { if (wither.isInvulnerable()) wither.setInvulnerable(false); } catch (Throwable ignored) {}
        try { wither.setTarget(null); } catch (Throwable ignored) {}
        try { if (wither.getPathfinder() != null) wither.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
        try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}

        // Self-heal: +1 HP/s, but ONLY while below the regen ceiling (300). Direct setHealth (not a
        // Regeneration effect) so nothing lingers and it can't stack.
        if (Bukkit.getCurrentTick() % 20 == 0) {
            try {
                double cur = wither.getHealth();
                if (cur > 0.0 && cur < WITHER_REGEN_CEILING) {
                    wither.setHealth(Math.min(WITHER_MAX_HP, cur + 1.0));
                }
            } catch (Throwable ignored) {}
        }

        // Boss bar sync (vanilla broadcast lives in the suppressed aiStep). Throttled to 4 Hz — it
        // doesn't need a per-tick refresh and the attribute lookup isn't free.
        if (Bukkit.getCurrentTick() % 5 == 0) {
            try {
                org.bukkit.boss.BossBar bar = wither.getBossBar();
                if (bar != null) {
                    double maxHp = wither.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    if (maxHp > 0) bar.setProgress(Math.max(0.0, Math.min(1.0, wither.getHealth() / maxHp)));
                }
            } catch (Throwable ignored) {}
        }

        // Half-HP event: ONCE call in the skeleton reinforcements. Fires in BOTH modes (this runs
        // for the zone AI too), so it also triggers mid turret-fight. No setGlowing here — Bukkit's
        // only glow API is the see-through-walls outline the user rejected, and the wither's native
        // white "charge" shimmer can't be turned on without also making it damage-immune.
        if (wither.getHealth() <= WITHER_HALF_HP && halfHpTriggered.add(wid)) {
            spawnReinforcements(wither, HALF_HP_SKELETONS);
            if (WITHER_DEBUG) logWither(wither, "HALF-HP → " + HALF_HP_SKELETONS + " wither skeletons");
        }
    }

    /**
     * Play {@code sound} so EVERY player within {@code radius} blocks of {@code source} clearly hears
     * it, regardless of distance. A single {@code World#playSound} — even at high volume — attenuates
     * to near-silence within ~12-16 blocks (volume only sets the cutoff, not the loudness curve), which
     * is why "just make it louder" didn't carry. Instead we play it individually for each nearby player
     * at a point a few blocks toward the source, so it stays loud (and roughly directional) out to the
     * full radius.
     */
    private static void playToRadius(Location source, double radius, Sound sound, float volume, float pitch) {
        World w = source.getWorld();
        if (w == null) return;
        double r2 = radius * radius;
        for (Player p : w.getPlayers()) {
            Location pl = p.getLocation();
            double d2 = pl.distanceSquared(source);
            if (d2 > r2) continue;
            double d = Math.sqrt(d2);
            // Seat the sound farther from the listener the farther away they are — ~8 blocks up close,
            // scaling to ~14 at the radius edge. That gives a natural distance falloff (quieter the
            // farther you are) instead of blasting everyone in range at the same near-full volume, while
            // still being audible to the edge. (Was a flat ~6-block seat = uniformly, overwhelmingly loud.)
            double seat = 8.0 + 6.0 * Math.min(1.0, d / radius);
            Location at;
            if (d < 1.0e-3) {
                at = source.clone();
            } else {
                Vector dir = source.toVector().subtract(pl.toVector()).normalize().multiply(seat);
                at = pl.clone().add(dir);
            }
            try { p.playSound(at, sound, volume, pitch); } catch (Throwable ignored) {}
        }
    }

    /**
     * During the spawn ritual (the vanilla invulnerability charge-up) the wither spins on itself,
     * accelerating from 1 revolution/second up to 2.5 rev/s by the end of the ritual. We touch ONLY
     * rotation — never AI, HP or invulnerability — so vanilla's spawn sequence completes normally.
     */
    private void spinDuringRitual(Wither wither) {
        UUID wid = wither.getUniqueId();
        // Ramp the spin speed over the ritual: 1.0 rev/s at the start → 2.5 rev/s at the end. Progress
        // comes from the vanilla spawn-invulnerability countdown (starts ≈220 ticks, ticks down to 0).
        int invuln = Math.max(0, wither.getInvulnerabilityTicks());
        double progress = 1.0 - Math.min(1.0, invuln / 220.0);
        double revsPerSec = 1.0 + 1.5 * progress;                              // 1.0 → 2.5
        float perTick = (float) (revsPerSec * 360.0 * (WITHER_TICK_INTERVAL / 20.0));
        Location loc = wither.getLocation();
        float yaw = (ritualYaw.getOrDefault(wid, loc.getYaw()) + perTick) % 360.0f;
        ritualYaw.put(wid, yaw);
        try { wither.setRotation(yaw, loc.getPitch()); } catch (Throwable ignored) {}
        // Re-play the wither spawn roar once a second while it charges/spins — audible out to 40 blocks.
        if (Bukkit.getCurrentTick() % 20 == 0) {
            playToRadius(loc, 40.0, Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f);
        }
    }

    /** Spawn {@code count} NORMAL wither skeletons on solid ground NEAR the wither's own level, so a
     *  fight underground spawns them on the cave floor — NOT teleported up to the overworld surface
     *  (the old {@code getHighestBlockYAt} bug). */
    private void spawnReinforcements(Wither wither, int count) {
        World world = wither.getWorld();
        Location base = wither.getLocation();
        int refY = base.getBlockY();
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int sx = (int) Math.floor(base.getX() + (r.nextDouble() - 0.5) * 6.0);
            int sz = (int) Math.floor(base.getZ() + (r.nextDouble() - 0.5) * 6.0);
            if (!world.isChunkLoaded(sx >> 4, sz >> 4)) { sx = base.getBlockX(); sz = base.getBlockZ(); }
            Integer standY = findStandingYNear(world, sx, refY, sz);
            if (standY == null) continue;   // no floor with headroom nearby → skip this one
            Location at = new Location(world, sx + 0.5, standY, sz + 0.5);
            try { world.spawn(at, WitherSkeleton.class, this::configureMinion); } catch (Throwable ignored) {}
        }
    }

    /** Register a freshly-spawned reinforcement skeleton as one of our custom-AI minions. We KEEP
     *  vanilla AI on (so the vanilla navigator ticks and gives us free 1-block jumps + water
     *  avoidance) — our tick just clears its vanilla target each tick and drives it via the
     *  navigator. Follow-range is widened so the navigator can path across the base to the next
     *  turret. */
    private void configureMinion(WitherSkeleton sk) {
        try { sk.setTarget(null); } catch (Throwable ignored) {}
        try {
            var fr = sk.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
            if (fr != null) fr.setBaseValue(MINION_FOLLOW_RANGE);
        } catch (Throwable ignored) {}
        try { sk.setRemoveWhenFarAway(false); } catch (Throwable ignored) {}
        try { sk.setPersistent(true); } catch (Throwable ignored) {}
        try { sk.getPersistentDataContainer().set(phantomKey, PersistentDataType.STRING, MINION_TAG); } catch (Throwable ignored) {}
        witherMinions.add(sk.getUniqueId());
    }

    /** After a reload, re-adopt any of our minions still in the world (PDC-tagged) so our tick
     *  picks them back up and keeps driving them. */
    private void readoptMinions() {
        for (World w : Bukkit.getWorlds()) {
            for (WitherSkeleton sk : w.getEntitiesByClass(WitherSkeleton.class)) {
                String tag = sk.getPersistentDataContainer().get(phantomKey, PersistentDataType.STRING);
                if (MINION_TAG.equals(tag)) {
                    try {
                        var fr = sk.getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE);
                        if (fr != null) fr.setBaseValue(MINION_FOLLOW_RANGE);
                    } catch (Throwable ignored) {}
                    witherMinions.add(sk.getUniqueId());
                }
            }
        }
    }

    // -- wither-skeleton minion AI --------------------------------------------

    /** Drive every tracked minion once per server tick: re-suppress its brain, pick a target by the
     *  wither's priority within {@value #MINION_ACQUIRE_RANGE} blocks (retaliator → player → turret →
     *  friendly creature), walk to it, and hit for {@value #MINION_DAMAGE} (+ Wither I) or chew the
     *  turret structure. */
    private void minionTick() {
        if (witherMinions.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        for (UUID id : new java.util.ArrayList<>(witherMinions)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof WitherSkeleton sk) || sk.isDead() || !sk.isValid()) {
                witherMinions.remove(id);
                minionLastAttack.remove(id);
                minionAttacker.remove(id);
                minionAttackerTick.remove(id);
                minionNavDest.remove(id);
                continue;
            }
            driveMinion(sk, now);
        }
    }

    private void driveMinion(WitherSkeleton sk, long now) {
        // We own targeting — clear any vanilla aggro each tick so vanilla goals don't fight the
        // navigation we drive (and so vanilla never melees; our hits are applied manually).
        try { if (sk.getTarget() != null) sk.setTarget(null); } catch (Throwable ignored) {}

        // Target priority: P1 retaliator → P2 player → P3 turret(in a claim) → P4 friendly creature.
        LivingEntity living = retaliationTarget(sk, now);
        Turret turret = null;
        if (living == null) {
            living = nearestPlayer(sk);                 // P2
            if (living == null) {
                turret = nearestTurret(sk);             // P3
                if (turret == null) living = nearestCreature(sk);   // P4
            }
        }

        if (turret != null) {
            if (horizontalDistToTurretBand(sk.getLocation(), turret) <= 0.0) {
                stopNav(sk);
                attackTurret(sk, turret, now);
            } else {
                navTo(sk, turretApproachLoc(sk, turret));
            }
            return;
        }

        if (living != null) {
            double distSq = living.getLocation().distanceSquared(sk.getLocation());
            if (distSq <= MINION_ATTACK_RANGE * MINION_ATTACK_RANGE) {
                stopNav(sk);
                attackLiving(sk, living, now);
            } else {
                navTo(sk, living.getLocation());
            }
            return;
        }
        stopNav(sk);   // nothing in range — idle
    }

    /** Re-issue a navigator path toward {@code dest} only when needed (no active path, or the
     *  destination moved &gt; ~1.5 blocks since the last issue) so we don't reset the path every
     *  tick. The vanilla navigator handles 1-block step-ups and routes around / avoids water. */
    private void navTo(WitherSkeleton sk, Location dest) {
        if (dest == null || dest.getWorld() == null) { stopNav(sk); return; }
        UUID id = sk.getUniqueId();
        Location last = minionNavDest.get(id);
        boolean hasPath;
        try { hasPath = sk.getPathfinder().hasPath(); } catch (Throwable t) { hasPath = false; }
        boolean reissue = !hasPath || last == null || last.getWorld() != dest.getWorld()
                || last.distanceSquared(dest) > 2.25;
        if (reissue) {
            try { sk.getPathfinder().moveTo(dest, MINION_NAV_SPEED); } catch (Throwable ignored) {}
            minionNavDest.put(id, dest.clone());
        }
    }

    private void stopNav(WitherSkeleton sk) {
        try { sk.getPathfinder().stopPathfinding(); } catch (Throwable ignored) {}
        minionNavDest.remove(sk.getUniqueId());
    }

    /** A walkable cell on the turret's +1 protection ring nearest the minion — "1 block in front". */
    private Location turretApproachLoc(WitherSkeleton sk, Turret t) {
        World w = sk.getWorld();
        double cx = t.getX() + 0.5, cz = t.getZ() + 0.5;
        double dx = sk.getLocation().getX() - cx;
        double dz = sk.getLocation().getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-3) { dx = 1.0; dz = 0.0; len = 1.0; }
        double ring = TurretStructure.FOOTPRINT_RADIUS + 1.5;     // just outside the wall, in the ring
        int ax = (int) Math.floor(cx + dx / len * ring);
        int az = (int) Math.floor(cz + dz / len * ring);
        Integer gy = findStandingYNear(w, ax, t.getY() + 1, az);
        int y = gy != null ? gy : t.getY() + 1;
        return new Location(w, ax + 0.5, y, az + 0.5);
    }

    private LivingEntity retaliationTarget(WitherSkeleton sk, long now) {
        UUID aid = minionAttacker.get(sk.getUniqueId());
        Long t = minionAttackerTick.get(sk.getUniqueId());
        if (aid == null || t == null || now - t > MINION_RETALIATE_WINDOW_TICKS) return null;
        Entity ae = Bukkit.getEntity(aid);
        if (!(ae instanceof LivingEntity le) || le.isDead() || !le.isValid() || le instanceof Wither) return null;
        if (!le.getWorld().equals(sk.getWorld())) return null;
        if (le.getLocation().distanceSquared(sk.getLocation()) > MINION_ACQUIRE_RANGE * MINION_ACQUIRE_RANGE) return null;
        return le;
    }

    private Player nearestPlayer(WitherSkeleton sk) {
        Location from = sk.getLocation();
        Player best = null; double bestSq = MINION_ACQUIRE_RANGE * MINION_ACQUIRE_RANGE;
        for (Entity e : sk.getNearbyEntities(MINION_ACQUIRE_RANGE, MINION_ACQUIRE_RANGE, MINION_ACQUIRE_RANGE)) {
            if (e instanceof Player p && isAttackablePlayer(p)) {
                double ds = p.getLocation().distanceSquared(from);
                if (ds < bestSq) { bestSq = ds; best = p; }
            }
        }
        return best;
    }

    private LivingEntity nearestCreature(WitherSkeleton sk) {
        Location from = sk.getLocation();
        LivingEntity best = null; double bestSq = MINION_ACQUIRE_RANGE * MINION_ACQUIRE_RANGE;
        for (Entity e : sk.getNearbyEntities(MINION_ACQUIRE_RANGE, MINION_ACQUIRE_RANGE, MINION_ACQUIRE_RANGE)) {
            if (e instanceof LivingEntity le && isValidMobTarget(le)) {
                double ds = le.getLocation().distanceSquared(from);
                if (ds < bestSq) { bestSq = ds; best = le; }
            }
        }
        return best;
    }

    /**
     * Turret target selection. While the minion is standing <b>inside a claim</b>, it considers
     * EVERY live turret in that claim at any distance — so after felling the nearest one it crosses
     * the base to the next instead of idling. For turrets in other claims (or when the minion is
     * outside any zone) only ones within {@value #MINION_ACQUIRE_RANGE} blocks count, so a minion
     * out in the open chases players rather than getting yanked back to a distant base.
     */
    private Turret nearestTurret(WitherSkeleton sk) {
        Location from = sk.getLocation();
        UUID worldId = sk.getWorld().getUID();
        Claim inClaim = plugin.getClaimManager().getClaimAt(from);
        double acquireSq = MINION_ACQUIRE_RANGE * MINION_ACQUIRE_RANGE;
        Turret best = null; double bestSq = Double.MAX_VALUE;
        for (Claim c : plugin.getClaimManager().all().values()) {
            if (!c.getWorldId().equals(worldId)) continue;
            boolean sameClaim = (inClaim != null && c == inClaim);
            for (Turret t : c.getTurrets()) {
                if (t.isDestroyed() || t.getNpcId() < 0) continue;
                double dx = (t.getX() + 0.5) - from.getX();
                double dz = (t.getZ() + 0.5) - from.getZ();
                double ds = dx * dx + dz * dz;
                if (!sameClaim && ds > acquireSq) continue;   // far turret in another zone → ignore
                if (ds < bestSq) { bestSq = ds; best = t; }
            }
        }
        return best;
    }

    /** Horizontal distance to the turret's structure footprint expanded one block outward (the "+1
     *  ring"), Y ignored — 0 when the minion is standing in the ring, i.e. 1 block in front. */
    private static double horizontalDistToTurretBand(Location from, Turret t) {
        double minX = t.getX() - (TurretStructure.FOOTPRINT_RADIUS + 1.0);
        double maxX = t.getX() + (TurretStructure.FOOTPRINT_RADIUS + 2.0);
        double minZ = t.getZ() - (TurretStructure.FOOTPRINT_RADIUS + 1.0);
        double maxZ = t.getZ() + (TurretStructure.FOOTPRINT_RADIUS + 2.0);
        double dx = from.getX() < minX ? minX - from.getX() : (from.getX() > maxX ? from.getX() - maxX : 0.0);
        double dz = from.getZ() < minZ ? minZ - from.getZ() : (from.getZ() > maxZ ? from.getZ() - maxZ : 0.0);
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void attackLiving(WitherSkeleton sk, LivingEntity target, long now) {
        Long last = minionLastAttack.get(sk.getUniqueId());
        if (last != null && now - last < MINION_ATTACK_COOLDOWN_TICKS) return;
        minionLastAttack.put(sk.getUniqueId(), now);
        // Flag the hit as ours so onMinionDealDamage lets it through (and only this one lands).
        minionManualHit = true;
        try { target.damage(MINION_DAMAGE, sk); }
        catch (Throwable ignored) {}
        finally { minionManualHit = false; }
        try {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                    MINION_WITHER_TICKS, MINION_WITHER_AMPLIFIER, false, true));
        } catch (Throwable ignored) {}
        try { sk.swingMainHand(); } catch (Throwable ignored) {}
    }

    private void attackTurret(WitherSkeleton sk, Turret t, long now) {
        Long last = minionLastAttack.get(sk.getUniqueId());
        if (last != null && now - last < MINION_ATTACK_COOLDOWN_TICKS) return;
        minionLastAttack.put(sk.getUniqueId(), now);
        applyRaidMobDamage(t, (int) MINION_DAMAGE, sk);
        try { sk.swingMainHand(); } catch (Throwable ignored) {}
    }

    /** Standing Y (top of a solid floor with 2 blocks of headroom) closest to {@code refY} at (x,z).
     *  Scans DOWN first (the floor is normally below a hovering wither), then a little up. Searches a
     *  small window so reinforcements land in the cave where the fight is, not the distant surface.
     *  Returns {@code null} if nothing suitable is within range. */
    private static Integer findStandingYNear(World world, int x, int refY, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int dy = 0; dy <= 16; dy++) {           // down toward the cave/ground floor
            int y = refY - dy;
            if (isStandable(world, x, y, z)) return y + 1;
        }
        for (int dy = 1; dy <= 6; dy++) {            // then a bit up
            int y = refY + dy;
            if (isStandable(world, x, y, z)) return y + 1;
        }
        return null;
    }

    /** A solid floor block with two non-solid blocks above it (room to stand). */
    private static boolean isStandable(World world, int x, int y, int z) {
        if (!world.getBlockAt(x, y, z).getType().isSolid()) return false;
        return !world.getBlockAt(x, y + 1, z).getType().isSolid()
            && !world.getBlockAt(x, y + 2, z).getType().isSolid();
    }

    // -- death sequence -------------------------------------------------------

    /**
     * Apply our own (non-vanilla) damage to the wither — used by the arrow path, which cancels the
     * vanilla hit. Clears the hurt i-frames so the hit always lands, plays the red hurt flash + hurt
     * sound (the vanilla flash is skipped when we bypass the damage pipeline), and routes a lethal
     * blow into the custom death sequence instead of a plain death.
     */
    private void applyWitherDamage(Wither w, double dmg) {
        if (dyingSince.containsKey(w.getUniqueId())) return;     // already dying — ignore
        try { w.playHurtAnimation(0.0f); } catch (Throwable ignored) {
            try { w.playEffect(EntityEffect.HURT); } catch (Throwable ignored2) {}
        }
        playToRadius(w.getLocation(), 32.0, Sound.ENTITY_WITHER_HURT, 1.0f, 1.0f);
        double newHp = w.getHealth() - dmg;
        if (newHp <= 0.0) { startDying(w); return; }
        try { w.setNoDamageTicks(0); } catch (Throwable ignored) {}
        w.setHealth(newHp);
    }

    /**
     * Begin the custom death sequence: the wither freezes in place, the boss bar pins at 0 HP, and it
     * spins up over {@link #DEATH_SEQUENCE_TICKS} (1 → 2 rotations/s) before the final blast. Kept
     * technically alive + invulnerable so nothing finishes it the vanilla way mid-sequence.
     */
    private void startDying(Wither w) {
        UUID id = w.getUniqueId();
        if (dyingSince.containsKey(id)) return;
        dyingSince.put(id, (long) Bukkit.getCurrentTick());
        ritualYaw.remove(id);                                   // restart spin accumulator
        try { w.setAI(false); } catch (Throwable ignored) {}
        try { w.setInvulnerable(true); } catch (Throwable ignored) {}
        try { w.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
        try { if (w.getHealth() > 1.0) w.setHealth(1.0); } catch (Throwable ignored) {}
        try {
            org.bukkit.boss.BossBar bar = w.getBossBar();
            if (bar != null) bar.setProgress(0.0);
        } catch (Throwable ignored) {}
        if (WITHER_DEBUG) logWither(w, "DYING sequence started");
    }

    /** Per-tick driver while the wither is in its death spin-up; ends in {@link #finalWitherExplosion}. */
    private void deathSequenceTick(Wither w) {
        UUID id = w.getUniqueId();
        long start = dyingSince.getOrDefault(id, (long) Bukkit.getCurrentTick());
        long elapsed = Bukkit.getCurrentTick() - start;
        World world = w.getWorld();
        Location loc = w.getLocation();

        // Frozen in place, boss bar pinned at 0.
        try { w.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
        try {
            org.bukkit.boss.BossBar bar = w.getBossBar();
            if (bar != null) bar.setProgress(0.0);
        } catch (Throwable ignored) {}

        // Spin-up: 1 rot/s ramping to 2 rot/s across the sequence.
        double frac = Math.min(1.0, (double) elapsed / DEATH_SEQUENCE_TICKS);
        float perTick = (float) ((1.0 + frac) * 360.0 / 20.0);   // 18°/tick → 36°/tick
        float yaw = (ritualYaw.getOrDefault(id, loc.getYaw()) + perTick) % 360.0f;
        ritualYaw.put(id, yaw);
        try { w.setRotation(yaw, loc.getPitch()); } catch (Throwable ignored) {}

        // Charge-up smoke while spinning.
        if (elapsed % 4 == 0) {
            try { world.spawnParticle(Particle.LARGE_SMOKE, loc.clone().add(0, 2, 0), 8, 1.2, 1.6, 1.2, 0.02); }
            catch (Throwable ignored) {}
        }

        if (elapsed >= DEATH_SEQUENCE_TICKS) finalWitherExplosion(w);
    }

    /**
     * The one last blast: a big irregular explosion ({@link #DEATH_BLAST_RADIUS}) — scattered
     * explosion particles, a loud boom, {@value #DEATH_BLAST_CENTER_DMG} damage at the centre fading
     * to {@value #DEATH_BLAST_EDGE_DMG} at the edge, an "ugly" crater, scattered XP + a nether star —
     * then the wither despawns amid the textures so it isn't left standing.
     */
    private void finalWitherExplosion(Wither w) {
        World world = w.getWorld();
        Location center = w.getLocation().clone().add(0, 1.75, 0);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();

        // Explosion textures spread across the blast volume.
        try {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
            for (int i = 0; i < 40; i++) {
                Location p = center.clone().add(
                        (r.nextDouble() - 0.5) * 2 * DEATH_BLAST_RADIUS,
                        (r.nextDouble() - 0.5) * 2 * DEATH_BLAST_RADIUS,
                        (r.nextDouble() - 0.5) * 2 * DEATH_BLAST_RADIUS);
                world.spawnParticle(Particle.EXPLOSION, p, 1);
            }
        } catch (Throwable ignored) {}
        playToRadius(center, 55.0, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);

        // Damage living things by distance: centre → edge linear falloff, nothing beyond the radius.
        for (Entity e : world.getNearbyEntities(center, DEATH_BLAST_RADIUS, DEATH_BLAST_RADIUS, DEATH_BLAST_RADIUS)) {
            if (!(e instanceof LivingEntity le) || e instanceof Wither) continue;
            double d = le.getLocation().distance(center);
            if (d > DEATH_BLAST_RADIUS) continue;
            double dmg = DEATH_BLAST_CENTER_DMG
                    - (d / DEATH_BLAST_RADIUS) * (DEATH_BLAST_CENTER_DMG - DEATH_BLAST_EDGE_DMG);
            try { le.damage(Math.max(DEATH_BLAST_EDGE_DMG, dmg)); } catch (Throwable ignored) {}
        }

        // Ugly crater.
        breakBlob(world, center.getBlockX(), center.getBlockY(), center.getBlockZ(), DEATH_BLAST_RADIUS);

        // Rewards: scattered XP + the signature nether star.
        spawnXp(world, w.getLocation(), WITHER_DEATH_XP);
        try { world.dropItemNaturally(w.getLocation(), new ItemStack(Material.NETHER_STAR)); } catch (Throwable ignored) {}

        UUID id = w.getUniqueId();
        try { w.remove(); } catch (Throwable ignored) {}     // despawn amid the textures
        // Death roar plays AFTER the despawn (per spec) — a world sound at the blast centre, so it
        // lands even though the entity is already gone.
        playToRadius(center, 55.0, Sound.ENTITY_WITHER_DEATH, 1.1f, 1.0f);
        if (WITHER_DEBUG) logWither(w, "FINAL BLAST → despawned");
        releaseWither(id);
    }

    /** Scatter {@code total} XP as several orbs at {@code loc} for players to pick up. */
    private void spawnXp(World world, Location loc, int total) {
        int remaining = total;
        while (remaining > 0) {
            final int amt = Math.min(40, remaining);
            remaining -= amt;
            try { world.spawn(loc, ExperienceOrb.class, o -> o.setExperience(amt)); } catch (Throwable ignored) {}
        }
    }

    // -- charge (dash) attack -------------------------------------------------

    /**
     * Try to begin the telegraphed dash attack. Gated by a 1-min cooldown; while off-cooldown the
     * feasibility is re-probed only every {@link #CHARGE_CHECK_INTERVAL_TICKS} (5 s). Feasible = the
     * target is within {@link #CHARGE_MAX_RANGE} blocks straight-line. On success it LOCKS the
     * target's current position (the dash aims there, not at the live player), freezes the wither,
     * and starts the windup. Returns true if a charge began this tick.
     */
    private boolean maybeStartCharge(Wither wither, LivingEntity target, long now) {
        UUID id = wither.getUniqueId();
        Long readyAt = chargeReadyAt.get(id);
        if (readyAt != null && now < readyAt) return false;                 // 1-min cooldown
        Long nextCheck = chargeNextCheck.get(id);
        if (nextCheck != null && now < nextCheck) return false;            // only probe every 5 s
        chargeNextCheck.put(id, now + CHARGE_CHECK_INTERVAL_TICKS);

        // Never charge while sieging a base: not while inside a claim, nor near a claim that still has
        // live turrets. The dash is a player-hunting move for the open world, not a turret-fight
        // interruption (the "it dashes the player mid turret fight then goes back" bug).
        if (plugin.getClaimManager().getClaimAt(wither.getLocation()) != null) return false;
        if (findNearbyClaimWithLiveTurret(wither) != null) return false;

        Location start = wither.getEyeLocation();
        Location tgt = target.getLocation().clone().add(0, target.getHeight() * 0.5, 0);
        if (start.getWorld() == null || !start.getWorld().equals(tgt.getWorld())) return false;
        if (start.distance(tgt) > CHARGE_MAX_RANGE) return false;          // too far → retry in 5 s

        Charge c = new Charge();
        c.lockedTarget = tgt.clone();        // enemy OLD position
        c.startPos = start.clone();
        c.windupStartTick = now;
        c.dashing = false;
        activeCharge.put(id, c);
        chargeReadyAt.put(id, now + CHARGE_COOLDOWN_TICKS);                 // attempt cooldown starts now
        try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
        // Play the windup warning AT THE TARGET so the player actually hears it even from far away
        // (a sound at the distant wither was inaudible — the particle line showed but no sound).
        playToRadius(tgt, 45.0, Sound.ENTITY_WITHER_AMBIENT, 1.0f, 0.5f);
        if (WITHER_DEBUG) logWither(wither, "CHARGE windup (locked " + (int) start.distance(tgt) + "b away)");
        return true;
    }

    /** Drives the windup then the dash. Called (with priority) from {@link #manageWither}. */
    private void chargeTick(Wither wither, long now) {
        UUID id = wither.getUniqueId();
        Charge c = activeCharge.get(id);
        if (c == null) return;
        World world = wither.getWorld();
        Location current = wither.getLocation();

        // Face the locked target (yaw) with a fixed forward "helicopter" tilt.
        Vector flat = c.lockedTarget.toVector().subtract(current.toVector());
        flat.setY(0);
        float yaw = current.getYaw();
        if (flat.lengthSquared() > 1e-6) {
            Location o = current.clone();
            o.setDirection(flat);
            yaw = o.getYaw();
        }
        try { wither.setRotation(yaw, CHARGE_TILT_PITCH); } catch (Throwable ignored) {}

        if (!c.dashing) {
            // WINDUP: frozen in place; telegraph the line; hold for 1 s, then dash.
            try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
            if ((now - c.windupStartTick) % 4 == 0) drawChargeLine(world, wither.getEyeLocation(), c.lockedTarget);
            if (now - c.windupStartTick >= CHARGE_WINDUP_TICKS) {
                c.dashing = true;
                c.dashStartTick = now;
                c.startPos = current.clone();
                // Play the launch roar AT the target too (audible to a far player) plus at the wither.
                playToRadius(current, 45.0, Sound.ENTITY_WITHER_SHOOT, 1.0f, 0.5f);
            }
            return;
        }

        // DASH: triple-speed straight at the locked position; carve a dirty tunnel; 20 dmg in the path.
        Vector toTarget = c.lockedTarget.toVector().subtract(current.toVector());
        double remaining = toTarget.length();
        if (remaining <= 1.5 || now - c.dashStartTick > CHARGE_DASH_MAX_TICKS
                || c.startPos.distance(current) > CHARGE_MAX_RANGE + 6) {
            endCharge(wither);
            return;
        }
        Vector dir = toTarget.normalize();
        double step = Math.min(CHARGE_SPEED_BPS * (WITHER_TICK_INTERVAL / 20.0), remaining);
        Location next = current.clone().add(dir.clone().multiply(step));
        next.setYaw(yaw);
        next.setPitch(CHARGE_TILT_PITCH);

        // Carve the dirty tunnel a couple blocks AHEAD so the path is clear as the wither arrives.
        Location carve = current.clone().add(dir.clone().multiply(2.0));
        breakBlob(world, carve.getBlockX(), carve.getBlockY(), carve.getBlockZ(), CHARGE_DIG_RADIUS);

        // One-time 20 dmg to anything the dash sweeps past.
        for (Entity e : world.getNearbyEntities(current, CHARGE_HIT_RADIUS, CHARGE_HIT_RADIUS, CHARGE_HIT_RADIUS)) {
            if (!(e instanceof LivingEntity le) || e instanceof Wither) continue;
            if (le.getLocation().distance(current) > CHARGE_HIT_RADIUS) continue;
            if (!c.hit.add(e.getUniqueId())) continue;                     // one-time per charge
            try { le.damage(CHARGE_DAMAGE, wither); } catch (Throwable ignored) {}
        }

        try { wither.teleport(next); } catch (Throwable ignored) {}
        try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
    }

    /** End the charge and un-tilt; the 1-min cooldown was already started at windup. */
    private void endCharge(Wither wither) {
        activeCharge.remove(wither.getUniqueId());
        try {
            Location l = wither.getLocation();
            wither.setRotation(l.getYaw(), 0.0f);
        } catch (Throwable ignored) {}
        if (WITHER_DEBUG) logWither(wither, "CHARGE done");
    }

    /** Telegraph: a line of soul-fire particles from the wither to the locked target during windup. */
    private void drawChargeLine(World world, Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 1e-6) return;
        dir.normalize();
        for (double d = 0; d <= len; d += 1.0) {
            Location p = from.clone().add(dir.clone().multiply(d));
            if (!world.isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) continue;
            try { world.spawnParticle(Particle.SOUL_FIRE_FLAME, p, 1); } catch (Throwable ignored) {}
        }
    }

    /**
     * Non-zone combat AI. Hunts the nearest valid target — real players first, then friendly
     * mobs/animals/golems/villagers/armor stands — hovering {@value #COMBAT_HOVER_PHASE1} blocks
     * above it ({@value #COMBAT_HOVER_PHASE2} once at/under half HP) and raining aimed Wither-II
     * skulls down on it. With no target it idles above the ground. Whenever wedged against terrain
     * it tears a {@value #WITHER_BREAK_RADIUS}-block cube of blocks out every 5 s to free itself.
     */
    /** Horizontal stand-off radius (blocks) the wither keeps from its combat target: it orbits at
     *  this distance instead of hovering straight overhead. */
    private static final double COMBAT_ORBIT_RADIUS = 3.5;
    /** Ticks the wither holds one orbit side before rotating ~90° to the next. 100 ticks = 5 s. */
    private static final long COMBAT_ORBIT_HOLD_TICKS = 100L;

    private void combatAi(Wither wither) {
        applyWitherControl(wither);

        World world = wither.getWorld();
        long now = Bukkit.getCurrentTick();
        Location current = wither.getLocation();
        boolean phase2 = wither.getHealth() <= WITHER_HALF_HP;
        double hoverAbove = phase2 ? COMBAT_HOVER_PHASE2 : COMBAT_HOVER_PHASE1;

        LivingEntity target = resolveCombatTarget(wither, now);

        // Telegraphed dash attack pre-empts normal combat when it fires (then runs via manageWither).
        if (target != null && maybeStartCharge(wither, target, now)) return;

        double bob = Math.sin((now / WITHER_BOB_PERIOD_TICKS) * 2.0 * Math.PI) * WITHER_BOB_AMPLITUDE;
        Location aim;
        Location lookAt;
        if (target != null) {
            final UUID wid = wither.getUniqueId();
            Location t = target.getLocation();
            // Orbit the target instead of hovering straight overhead: stand off ~COMBAT_ORBIT_RADIUS
            // blocks to ONE side and rotate ~90° to a new side every COMBAT_ORBIT_HOLD_TICKS (5 s) with
            // a small random jitter — so the wither circles the player (E→N→W→S-ish), holding each
            // side a few seconds, and the fight feels alive instead of a static rain-from-above.
            Double cur = combatOrbitAngle.get(wid);
            double angle;
            if (cur == null) {                                            // first sighting → random side
                angle = java.util.concurrent.ThreadLocalRandom.current().nextDouble(0.0, Math.PI * 2.0);
                combatOrbitAngle.put(wid, angle);
                combatOrbitNextChange.put(wid, now + COMBAT_ORBIT_HOLD_TICKS);
            } else if (now >= combatOrbitNextChange.getOrDefault(wid, 0L)) {  // 5 s up → rotate ~90°
                angle = cur + Math.toRadians(90.0)
                        + java.util.concurrent.ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
                combatOrbitAngle.put(wid, angle);
                combatOrbitNextChange.put(wid, now + COMBAT_ORBIT_HOLD_TICKS);
            } else {
                angle = cur;                                              // hold the current side
            }
            double offX = Math.cos(angle) * COMBAT_ORBIT_RADIUS;
            double offZ = Math.sin(angle) * COMBAT_ORBIT_RADIUS;
            // Tight cave: do NOT cap the height below the ceiling. Aim for the FULL battle height and
            // press up against the ceiling — holding there trips the no-progress watchdog (~1.5 s)
            // below, which fires the block-break dig sweep that opens the ceiling so the wither reaches
            // its proper altitude. (The old code lowered the hover to fit under the ceiling, so a wither
            // in a low cave never dug up and just fought from too low.)
            aim = new Location(world, t.getX() + offX, t.getY() + hoverAbove + bob, t.getZ() + offZ);
            lookAt = t.clone().add(0, target.getHeight() * 0.5, 0);   // face the target while firing
        } else {
            // No target → sink down and loiter ~1 block above the ground, holding position (it has
            // nothing to do, so it shouldn't hang high in the air or wander off).
            int g = highestGroundY(world, current);
            aim = new Location(world, current.getX(), g + COMBAT_HOVER_IDLE + bob, current.getZ());
            lookAt = null;
        }

        moveWitherToward(wither, current, aim, now, lookAt);

        // No-progress watchdog: if we have a target but haven't gotten meaningfully closer for ~1.5 s,
        // we're stuck somewhere the collision flag never caught (wedged in an air pocket, hovering
        // against a ceiling, etc.) → force a dig toward the target to free ourselves. (In open terrain
        // this is harmless: there are no blocks to break.)
        UUID id = wither.getUniqueId();
        if (target != null) {
            double dsq = current.toVector().distanceSquared(target.getLocation().toVector());
            Double best = combatBestDistSq.get(id);
            if (best == null || dsq + 4.0 < best) {
                combatBestDistSq.put(id, dsq);
                combatProgressTick.put(id, now);
            } else if (now - combatProgressTick.getOrDefault(id, now) >= 30L) {
                lastBlockedTick.put(id, now);                                   // → big dig sweep
                nibbleObstruction(wither, current, target.getLocation(), now);  // → fast break toward target
                combatProgressTick.put(id, now);
            }
        } else {
            combatBestDistSq.remove(id);
            combatProgressTick.remove(id);
        }

        // Priority-3: dig itself free whenever it is wedged against solid blocks.
        maybeBreakBlocks(wither, now);

        // Fire aimed skulls at the target on the SAME burst cadence as the zone AI.
        if (target != null) fireCombatBurst(wither, target, now);
    }

    /**
     * Teleport-drive the wither one step toward {@code aim}, clamped to the configured horizontal
     * (cruise vs hover) and vertical speeds, climbing straight up when its body would intersect a
     * block (never descending while blocked) and lifting it free if it stays wedged for 5 s. When
     * {@code lookAt} is non-null the wither faces that point (faces its target while firing);
     * otherwise it faces its direction of travel. Mirrors the zone AI's movement model.
     */
    private void moveWitherToward(Wither wither, Location current, Location aim, long now, Location lookAt) {
        World world = wither.getWorld();

        // Smarter pathfinding: if the straight line to the aim is blocked, fly along a cached 3D A*
        // route (PathPlanner) instead — it climbs over / around obstacles ("up two, then over"),
        // which the one-step trySidestep below can't figure out. In the open (clear shot) this is a
        // no-op, preserving the hover/orbit feel; if A* finds nothing the reactive sidestep + dig
        // still handle the truly-boxed case.
        aim = flightAim(wither, current, aim, now);

        double dx = aim.getX() - current.getX();
        double dy = aim.getY() - current.getY();
        double dz = aim.getZ() - current.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);

        double horizMaxBps = horizDist <= WITHER_HOVER_APPROACH_DIST_XZ
                ? WITHER_HOVER_SPEED_BPS : WITHER_CRUISE_SPEED_BPS;
        double horizMaxStep = horizMaxBps * (WITHER_TICK_INTERVAL / 20.0);
        double vertMaxStep = WITHER_VERT_SPEED_BPS * (WITHER_TICK_INTERVAL / 20.0);

        double horizStep = Math.min(horizDist, horizMaxStep);
        double sx = horizDist > 1e-6 ? (dx / horizDist) * horizStep : 0.0;
        double sz = horizDist > 1e-6 ? (dz / horizDist) * horizStep : 0.0;
        double sy = Math.signum(dy) * Math.min(Math.abs(dy), vertMaxStep);

        Location next = current.clone().add(sx, sy, sz);

        // Blocked at 'next' (the desired step toward the target runs into solid blocks).
        if (wouldCollide(world, next)) {
            // FIRST try simple navigation: weave past a small obstruction rather than stopping —
            // probe forward at a few vertical offsets (preferring a dip DOWN, then up) and take the
            // first clear one that still heads toward the target. This routes the wither down under
            // a single blocking block and the normal aim climbs it back up afterwards.
            Location nav = trySidestep(world, current, aim, horizMaxStep, vertMaxStep);
            if (nav != null) {
                next = nav;
            } else {
                // Truly boxed in. Hold (climb only if the target is genuinely above and the head is
                // clear), flag for the big dig sweep, AND aggressively nibble the immediate
                // obstruction so a single blocking block is cleared fast (don't wait the full 5 s).
                next.setX(current.getX());
                next.setZ(current.getZ());
                double wantUp = aim.getY() - current.getY();
                if (wantUp > 0.0 && !wouldCollide(world, current.clone().add(0.0, vertMaxStep, 0.0))) {
                    next.setY(current.getY() + Math.min(wantUp, vertMaxStep));
                } else {
                    next.setY(current.getY());
                }
                lastBlockedTick.put(wither.getUniqueId(), now);   // → big 5 s dig sweep
                nibbleObstruction(wither, current, aim, now);     // → fast small forward break
            }
        }

        Vector lookDir = null;
        if (lookAt != null) lookDir = lookAt.toVector().subtract(next.toVector());
        else if (horizStep > 0.01) lookDir = new Vector(sx, sy, sz);
        if (lookDir != null && lookDir.lengthSquared() > 1e-6) {
            Location oriented = next.clone();
            oriented.setDirection(lookDir);
            next.setYaw(oriented.getYaw());
            next.setPitch(oriented.getPitch());
        }

        try { wither.teleport(next); } catch (Throwable ignored) {}
        try { wither.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}

        // NO stuck-fallback teleport in normal combat (per spec). If the combat wither is genuinely
        // wedged it digs out with its 5 s block-break sweep instead of teleporting. The unstuck
        // teleport is reserved for the zone (turret) AI, which still uses it in flyTowardAndTarget.
    }

    private static final long FLIGHT_REPLAN_TICKS = 20L;                 // replan ~1/s while blocked
    private static final double FLIGHT_WAYPOINT_REACHED_SQ = 1.6 * 1.6;

    private static final class FlightPath {
        java.util.List<Vector> waypoints;
        int index;
        long replanAtTick;
        Vector aimAt;
    }

    /**
     * Resolve the point the wither should fly toward THIS step. When the straight shot to {@code aim}
     * is clear we return it unchanged (open-air hover/orbit feel). When it's blocked we follow a cached
     * 3D A* route to it ({@link PathPlanner}), advancing through waypoints — so the wither climbs
     * over / weaves around obstacles instead of pressing flat against one block. Returns {@code aim}
     * unchanged if no route is found (the caller's reactive sidestep + dig then handle the boxed case).
     */
    private Location flightAim(Wither wither, Location current, Location aim, long now) {
        World world = wither.getWorld();
        UUID id = wither.getUniqueId();
        if (!segmentBlocked(world, current, aim)) {        // clear line of sight → fly straight
            flightPaths.remove(id);
            return aim;
        }
        FlightPath fp = flightPaths.get(id);
        boolean aimMoved = fp == null || fp.aimAt == null || fp.aimAt.distanceSquared(aim.toVector()) > 9.0;
        // (Re)plan ONLY on the ~1 s timer or when the goal actually moved — never every tick. Crucially
        // we do NOT replan just because the last search failed: PathPlanner returns null whenever the
        // wither is boxed or the route exceeds the node budget (common now that it presses up against
        // ceilings), and retrying a full 4000-node A* every tick = 20 searches/second for one wither,
        // which visibly drops TPS. On failure we simply fly straight and let the reactive sidestep +
        // dig handle it until the next scheduled replan.
        if (fp == null || aimMoved || now >= fp.replanAtTick) {
            if (fp == null) { fp = new FlightPath(); flightPaths.put(id, fp); }
            fp.waypoints = PathPlanner.findPath(plugin, current, aim);
            fp.index = 0;
            fp.aimAt = aim.toVector();
            fp.replanAtTick = now + FLIGHT_REPLAN_TICKS;
        }
        if (fp.waypoints == null || fp.waypoints.isEmpty()) return aim;     // boxed → reactive fallback
        while (fp.index < fp.waypoints.size() - 1
                && current.toVector().distanceSquared(fp.waypoints.get(fp.index)) <= FLIGHT_WAYPOINT_REACHED_SQ) {
            fp.index++;
        }
        Vector wp = fp.waypoints.get(Math.min(fp.index, fp.waypoints.size() - 1));
        return new Location(world, wp.getX(), wp.getY(), wp.getZ());
    }

    /** True if a solid block sits on the straight segment {@code from → to} (fluids ignored). */
    private static boolean segmentBlocked(World world, Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double dist = dir.length();
        if (dist < 1.0e-3) return false;
        return world.rayTraceBlocks(from, dir.multiply(1.0 / dist), dist,
                org.bukkit.FluidCollisionMode.NEVER, true) != null;
    }

    /** Highest solid block Y at the location's column (chunk-load safe). */
    private static int highestGroundY(World world, Location at) {
        int x = at.getBlockX(), z = at.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return at.getBlockY();
        return world.getHighestBlockYAt(x, z);
    }

    /**
     * Dirt-simple obstacle avoidance (no A*): when a forward step is blocked, probe a forward step
     * at several vertical offsets and return the first that's clear. Preference order is a dip DOWN
     * first (route under a single blocking block — what the user asked for), then up; failing that,
     * a pure vertical reposition. Returns {@code null} only if genuinely boxed in.
     */
    private Location trySidestep(World world, Location current, Location aim, double horizMaxStep, double vertMaxStep) {
        double dx = aim.getX() - current.getX();
        double dz = aim.getZ() - current.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1e-6) return null;
        double fx = (dx / horiz) * horizMaxStep;
        double fz = (dz / horiz) * horizMaxStep;
        // Forward + vertical offsets, preferring the vertical direction TOWARD the target — UP when
        // the target is above us (the wither must rise past the ledge, not dive under it), DOWN when
        // it's below (slip under the block). Preferring down unconditionally was the bug that made it
        // tunnel DOWNWARD away from a player overhead.
        boolean preferUp = (aim.getY() - current.getY()) > 1.0;
        double[] verts = preferUp
                ? new double[]{ vertMaxStep, vertMaxStep * 0.5, -vertMaxStep * 0.5, -vertMaxStep }
                : new double[]{ -vertMaxStep, -vertMaxStep * 0.5, vertMaxStep * 0.5, vertMaxStep };
        for (double vy : verts) {
            Location cand = current.clone().add(fx, vy, fz);
            if (!wouldCollide(world, cand)) return cand;
        }
        // Forward fully blocked → just reposition vertically (toward the target first).
        Location first = current.clone().add(0, preferUp ? vertMaxStep : -vertMaxStep, 0);
        if (!wouldCollide(world, first)) return first;
        Location second = current.clone().add(0, preferUp ? -vertMaxStep : vertMaxStep, 0);
        if (!wouldCollide(world, second)) return second;
        return null;
    }

    /**
     * Aggressive clearing of the immediate obstruction directly ahead when the wither is boxed in
     * and can't sidestep — a small (~3-wide) irregular break ~1.5 blocks forward, on a ~1 s cooldown
     * (much faster than the 5 s full sweep) so a single blocking block gets punched through quickly.
     */
    private void nibbleObstruction(Wither wither, Location current, Location toward, long now) {
        UUID id = wither.getUniqueId();
        Long last = lastNibbleTick.get(id);
        if (last != null && now - last < 20L) return;     // ~1 s throttle
        lastNibbleTick.put(id, now);
        Vector dir = toward.toVector().subtract(current.toVector());   // FULL 3D (can break up/down too)
        if (dir.lengthSquared() < 1e-6) return;
        dir.normalize();
        Location ahead = current.clone().add(dir.multiply(1.5));
        World world = wither.getWorld();
        if (breakBlob(world, ahead.getBlockX(), ahead.getBlockY(), ahead.getBlockZ(), 1.5)) {
            playToRadius(current, 30.0, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 1.2f);
        }
    }

    /**
     * Combat target with caching: reuse the previously-acquired target (tracking its live position)
     * until it dies / leaves range / becomes illegal or until the next scheduled re-scan, so the
     * relatively heavy {@link #acquireCombatTarget} entity sweep runs at most every
     * {@link #COMBAT_ACQUIRE_INTERVAL} ticks instead of every tick.
     */
    private LivingEntity resolveCombatTarget(Wither wither, long now) {
        UUID wid = wither.getUniqueId();
        UUID tid = combatTargetId.get(wid);
        Long nextScan = nextAcquireTick.get(wid);
        if (tid != null && (nextScan == null || now < nextScan)) {
            Entity e = Bukkit.getEntity(tid);
            if (e instanceof LivingEntity le && !le.isDead() && le.isValid()
                    && le.getWorld().equals(wither.getWorld())) {
                // Hysteresis: keep tracking a little past the acquisition range so a target dancing
                // around the 20-block edge isn't dropped/re-found every other scan.
                double keepSq = (COMBAT_ACQUIRE_RANGE * 1.5) * (COMBAT_ACQUIRE_RANGE * 1.5);
                boolean stillLegal = (le instanceof Player p)
                        ? isAttackablePlayer(p)
                        : (isValidMobTarget(le) || isRecentAttacker(wither, le));   // retaliation target stays valid
                if (stillLegal && le.getLocation().distanceSquared(wither.getLocation()) <= keepSq) {
                    return le;
                }
            }
        }
        LivingEntity fresh = acquireCombatTarget(wither);
        nextAcquireTick.put(wid, now + COMBAT_ACQUIRE_INTERVAL);
        if (fresh != null) combatTargetId.put(wid, fresh.getUniqueId());
        else combatTargetId.remove(wid);
        return fresh;
    }

    /**
     * Target priority (outside the turret/zone AI):
     * <ol>
     *   <li><b>Whatever is attacking it</b> — it fights back against its most recent damager (any
     *       living entity, incl. hostiles it wouldn't otherwise notice), while still in range.</li>
     *   <li><b>Any player</b> — nearest attackable player (the wither is drawn to players, so a
     *       nearby zombie that isn't hitting it does NOT distract it from a player further out).</li>
     *   <li><b>Friendly creatures</b> — nearest animal / golem / villager / armour stand.</li>
     * </ol>
     * {@code null} if nothing valid is in range.
     */
    private LivingEntity acquireCombatTarget(Wither wither) {
        Location from = wither.getLocation();
        double rangeSq = COMBAT_ACQUIRE_RANGE * COMBAT_ACQUIRE_RANGE;

        // Priority 1: retaliate against whoever recently hit us (if still alive + in range).
        UUID aid = recentAttacker.get(wither.getUniqueId());
        Long atkTick = recentAttackerTick.get(wither.getUniqueId());
        if (aid != null && atkTick != null && Bukkit.getCurrentTick() - atkTick <= RETALIATE_WINDOW_TICKS) {
            Entity ae = Bukkit.getEntity(aid);
            if (ae instanceof LivingEntity le && !le.isDead() && le.isValid() && !(le instanceof Wither)
                    && le.getWorld().equals(wither.getWorld())
                    && le.getLocation().distanceSquared(from) <= rangeSq) {
                return le;
            }
        }

        // Priority 2: nearest player. Priority 3: nearest friendly creature.
        Player bestPlayer = null; double bestPlayerSq = Double.MAX_VALUE;
        LivingEntity bestMob = null; double bestMobSq = Double.MAX_VALUE;
        for (Entity e : wither.getNearbyEntities(COMBAT_ACQUIRE_RANGE, COMBAT_ACQUIRE_RANGE, COMBAT_ACQUIRE_RANGE)) {
            if (e instanceof Player p) {
                if (!isAttackablePlayer(p)) continue;
                double d = p.getLocation().distanceSquared(from);
                if (d < bestPlayerSq) { bestPlayerSq = d; bestPlayer = p; }
            } else if (e instanceof LivingEntity le && isValidMobTarget(le)) {
                double d = le.getLocation().distanceSquared(from);
                if (d < bestMobSq) { bestMobSq = d; bestMob = le; }
            }
        }
        return bestPlayer != null ? bestPlayer : bestMob;
    }

    /** Record the entity that just damaged the wither as its priority-1 retaliation target, and force
     *  an immediate target re-acquire so it turns on the attacker right away. */
    private void recordAttacker(Wither wither, Entity attacker) {
        if (attacker == null || attacker instanceof Wither || !(attacker instanceof LivingEntity)) return;
        UUID id = wither.getUniqueId();
        recentAttacker.put(id, attacker.getUniqueId());
        recentAttackerTick.put(id, (long) Bukkit.getCurrentTick());
        nextAcquireTick.put(id, (long) Bukkit.getCurrentTick());   // re-acquire now → retaliate
    }

    /** True if {@code e} is the wither's current (still-valid) retaliation target. */
    private boolean isRecentAttacker(Wither wither, Entity e) {
        Long t = recentAttackerTick.get(wither.getUniqueId());
        if (t == null || Bukkit.getCurrentTick() - t > RETALIATE_WINDOW_TICKS) return false;
        return e.getUniqueId().equals(recentAttacker.get(wither.getUniqueId()));
    }

    private static boolean isAttackablePlayer(Player p) {
        if (p.isDead() || !p.isValid()) return false;
        org.bukkit.GameMode gm = p.getGameMode();
        return gm != org.bukkit.GameMode.CREATIVE && gm != org.bukkit.GameMode.SPECTATOR;
    }

    /** Priority-2 target test: friendly mobs only (animals, golems, villagers, ambient/water mobs,
     *  armor stands). Excludes players, the wither itself, hostile monsters (incl. its own
     *  reinforcement skeletons), Citizens NPCs (turrets/raiders) and our turret phantom stands. */
    private boolean isValidMobTarget(LivingEntity le) {
        if (le instanceof Player || le instanceof Wither) return false;
        if (le.isDead() || !le.isValid()) return false;
        if (le instanceof ArmorStand a
                && a.getPersistentDataContainer().has(phantomKey, PersistentDataType.STRING)) return false;
        try { if (net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(le)) return false; } catch (Throwable ignored) {}
        if (le instanceof ArmorStand) return true;     // explicitly wanted
        if (le instanceof Monster) return false;       // exclude hostiles (skeletons, zombies, …)
        return true;                                   // animals / golems / villagers / ambient / water
    }

    /**
     * Priority-3 terrain destruction. Fires ONLY when the wither is actually BLOCKED from moving
     * toward its target (its desired step hit solid blocks within the last second — recorded in
     * {@link #lastBlockedTick}), and at most once every 5 s. This is the "it needs to dig a path"
     * trigger the user asked for: a wither flying freely a couple of blocks above open ground does
     * NOT dig (it isn't blocked), but one whose path into a hole / through a wall / down to a target
     * the player tucked under blocks DOES. Tears out an IRREGULAR ~9-wide blob ({@link #breakBlob});
     * unbreakable blocks, liquids and CLAIMED land are spared.
     */
    private void maybeBreakBlocks(Wither wither, long now) {
        // The dig fires at most every 5 s, so only evaluate ~2×/s.
        if (now % 10L != 0L) return;
        UUID wid = wither.getUniqueId();
        Long last = lastBreakTick.get(wid);
        if (last != null && now - last < WITHER_BREAK_INTERVAL_TICKS) return;
        // Gate: must have been blocked toward the target within the last second. No block in the
        // way → no need to dig, even with terrain close by.
        Long blocked = lastBlockedTick.get(wid);
        if (blocked == null || now - blocked > 20L) return;
        World world = wither.getWorld();
        lastBreakTick.put(wid, now);
        Location c = wither.getLocation();
        if (breakBlob(world, c.getBlockX(), c.getBlockY(), c.getBlockZ(), DIG_BASE_RADIUS)) {
            playToRadius(c, 30.0, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.0f, 1.0f);
        }
    }

    /**
     * Tear out an IRREGULAR roughly-spherical blob of blocks centred on (cx,cy,cz). Each block's
     * cutoff radius is {@code baseRadius} jittered by ±1.5, so the cavity is a rough, "ugly" shape
     * (sometimes 1-2 blocks past the nominal size, sometimes short) rather than a perfect room.
     * Unbreakable blocks, liquids and CLAIMED land are spared. Returns true if it broke anything.
     */
    private boolean breakBlob(World world, int cx, int cy, int cz, double baseRadius) {
        int scan = (int) Math.ceil(baseRadius) + 2;
        boolean checkClaims = cubeIntersectsAnyClaim(world, cx, cz, scan);
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        boolean broke = false;
        for (int dx = -scan; dx <= scan; dx++) {
            for (int dy = -scan; dy <= scan; dy++) {
                for (int dz = -scan; dz <= scan; dz++) {
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double effR = baseRadius + (r.nextDouble() * 3.0 - 1.5);   // ±1.5 → ragged edge
                    if (dist > effR) continue;
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = world.getBlockAt(x, y, z);
                    if (!canWitherBreak(b, checkClaims)) continue;
                    b.setType(Material.AIR, false);   // no physics cascade, no drops
                    broke = true;
                }
            }
        }
        return broke;
    }

    /** True if the (2r+1)² XZ footprint centred on (cx,cz) overlaps any claim in this world — used
     *  to decide whether the block-break sweep needs per-block claim checks at all. */
    private boolean cubeIntersectsAnyClaim(World world, int cx, int cz, int r) {
        java.util.UUID wId = world.getUID();
        for (Claim cl : plugin.getClaimManager().all().values()) {
            if (!cl.getWorldId().equals(wId)) continue;
            if (cx + r < cl.getMinX() || cx - r > cl.getMaxX()) continue;
            if (cz + r < cl.getMinZ() || cz - r > cl.getMaxZ()) continue;
            return true;
        }
        return false;
    }

    /** A block the combat wither may tear out: not air/liquid, not (when {@code checkClaims}) inside
     *  a claim — bases are protected — and not one of the hard-unbreakable blocks. */
    private boolean canWitherBreak(Block b, boolean checkClaims) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) return false;
        switch (m) {
            case BEDROCK, BARRIER, COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW, END_PORTAL_FRAME, END_PORTAL, END_GATEWAY,
                 NETHER_PORTAL, REINFORCED_DEEPSLATE, LIGHT -> { return false; }
            default -> { }
        }
        if (checkClaims && plugin.getClaimManager().getClaimAt(b.getLocation()) != null) return false;
        return true;
    }

    /** Burst-fire scheduler for the combat AI — identical cadence to the zone AI (a 2-shot burst
     *  every {@value #CUSTOM_SKULL_INTERVAL_TICKS} ticks, second shot {@value #BURST_SECOND_SHOT_DELAY}
     *  ticks later), aimed at the current target. Shares the per-wither timing maps with the zone AI. */
    private void fireCombatBurst(Wither wither, LivingEntity target, long now) {
        UUID wid = wither.getUniqueId();
        Long pendingSecond = pendingSecondShot.get(wid);
        if (pendingSecond != null && now >= pendingSecond) {
            fireCombatSkull(wither, target);
            pendingSecondShot.remove(wid);
        } else {
            Long lastBurst = lastSkullTick.get(wid);
            if (lastBurst == null || now - lastBurst >= CUSTOM_SKULL_INTERVAL_TICKS) {
                fireCombatSkull(wither, target);
                lastSkullTick.put(wid, now);
                pendingSecondShot.put(wid, now + BURST_SECOND_SHOT_DELAY);
            }
        }
    }

    private void fireCombatSkull(Wither wither, LivingEntity target) {
        if (target == null || target.isDead() || !target.isValid()) return;
        Location aimAt = target.getLocation().clone().add(0, target.getHeight() * 0.5, 0);
        fireSkull(wither, aimAt, "combat_skull");
    }

    /**
     * Combat-skull impact: never grief terrain (strip the explosion's block list — the priority-3
     * sweep is the only intentional digging), let the blast's entity damage stand, and splash
     * Wither II for {@value #COMBAT_WITHER_TICKS} ticks on every living thing within
     * {@value #COMBAT_SKULL_HIT_RADIUS} block of the explosion (except the wither itself / shooter;
     * undead are naturally immune to Wither so its own skeletons shrug it off).
     */
    private void applyCombatSkullEffect(WitherSkull skull, EntityExplodeEvent e) {
        e.blockList().clear();
        Location at = e.getLocation();
        Entity shooter = (skull.getShooter() instanceof Entity en) ? en : null;
        double rSq = COMBAT_SKULL_HIT_RADIUS * COMBAT_SKULL_HIT_RADIUS;
        for (Entity ent : at.getWorld().getNearbyEntities(at,
                COMBAT_SKULL_HIT_RADIUS, COMBAT_SKULL_HIT_RADIUS, COMBAT_SKULL_HIT_RADIUS)) {
            if (!(ent instanceof LivingEntity le)) continue;
            if (le instanceof Wither) continue;
            if (shooter != null && le.getUniqueId().equals(shooter.getUniqueId())) continue;
            if (le.getLocation().distanceSquared(at) > rSq) continue;
            try {
                le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        COMBAT_WITHER_TICKS, COMBAT_WITHER_AMPLIFIER, false, true));
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Spawn a {@link WitherSkull} aimed straight at the phantom. The skull is just a vanilla
     * projectile, so its impact + explosion go through {@link #onWitherSkullExplosion} like any
     * other wither skull would.
     *
     * <p><b>Spawn-vs-launch ordering</b>: the PDC {@code custom_skull} tag has to be applied
     * inside the {@code spawn} consumer — that runs synchronously during entity construction,
     * <i>before</i> {@link ProjectileLaunchEvent} fires. If we tagged after {@code spawn()}
     * returned, our own suppression listener would see {@code tag == null} on the inbound event
     * and cancel the skull we just created (the bug that made custom skulls silently disappear).
     *
     * <p><b>Self-collision avoidance</b>: spawning at {@code eye + dir × 2} pushes the skull
     * about two blocks past the wither's body. The wither's hitbox is ~3.5 blocks tall and
     * skulls spawning right at the eye were colliding with the wither itself and exploding
     * immediately.
     */
    private void fireCustomSkull(Wither wither, Location aimAt) {
        fireSkull(wither, aimAt, "custom_skull");
    }

    /** Spawn an aimed {@link WitherSkull} at {@code aimAt}, PDC-tagged so our own suppression and
     *  explosion handlers can tell turret skulls ({@code custom_skull}) from combat skulls
     *  ({@code combat_skull}) apart from genuine vanilla skulls. */
    private void fireSkull(Wither wither, Location aimAt, String tag) {
        try {
            Location eye = wither.getEyeLocation();
            Vector dir = aimAt.toVector().subtract(eye.toVector());
            if (dir.lengthSquared() < 1e-6) return;
            dir.normalize();
            // Push the spawn point clear of the wither's bounding box.
            Location spawnAt = eye.clone().add(dir.clone().multiply(2.0));
            Vector velocity = dir.clone().multiply(CUSTOM_SKULL_SPEED);
            wither.getWorld().spawn(spawnAt, WitherSkull.class, s -> {
                // Tag FIRST so the ProjectileLaunchEvent suppression listener (which fires
                // immediately on add-to-world) sees the marker and passes through.
                s.getPersistentDataContainer().set(phantomKey, PersistentDataType.STRING, tag);
                s.setShooter(wither);
                s.setDirection(velocity);
                s.setVelocity(velocity);
                s.setCharged(false);
            });
            // Restore the audio that setTarget(null) silenced — vanilla normally plays this
            // sound right before each skull leaves the wither's head.
            playToRadius(wither.getLocation(), 45.0, Sound.ENTITY_WITHER_SHOOT, 1.0f, 1.0f);
        } catch (Throwable t) {
            plugin.getLogger().warning("fireSkull failed: " + t.getMessage());
        }
    }

    // -- structure damage / destruction ---------------------------------------

    private void applyStructureDamage(Turret t, int amount, ArmorStand phantom) {
        applyStructureDamage(t, amount, phantom, null);
    }

    /**
     * Manual structure damage path for raid mobs. Turrets are structures, not damageable
     * entities, so raider/ravager melee must land here instead of calling damage() on the
     * shulker NPC body.
     */
    public void applyRaidMobDamage(Turret t, int amount, Entity damager) {
        if (t == null || amount <= 0 || t.isDestroyed()) return;
        Claim owningClaim = findClaimOf(t);
        if (owningClaim == null) return;
        World world = Bukkit.getWorld(owningClaim.getWorldId());
        if (world == null) return;
        ArmorStand phantom = ensurePhantom(world, t);
        applyStructureDamage(t, amount, phantom, damager);
    }

    /**
     * Friendly name for whoever just destroyed a turret. Identity is resolved by id, never by the
     * raw Citizens display name — that name can revert to the internal "CIT-&lt;id&gt;" default
     * (the hologram keeps the set name, but {@code npc.getName()} doesn't), which previously leaked
     * into this broadcast. Resolution order:
     * <ol>
     *   <li><b>Player</b> → their name.</li>
     *   <li><b>Turret NPC</b> (id matches a tracked slot) → {@code "Turret #N (owner)"}.</li>
     *   <li><b>Raid raider/ravager</b> (id known to the raid engine) → {@code "<attacker>'s Raider"},
     *       reconstructed from raid state so it survives a name revert.</li>
     *   <li><b>Other Citizens NPC</b> → its display name unless that has reverted to the CIT default,
     *       in which case a neutral {@code "a raider"}.</li>
     *   <li><b>Vanilla mob</b> → its type name.</li>
     * </ol>
     * Null-safe.
     */
    private String readableKillerName(Entity e) {
        if (e == null) return null;

        // Citizens NPC resolution MUST come before the plain-Player branch: our raiders are
        // PLAYER-type NPCs, so `e instanceof Player` is true for them. Checking that first
        // returned the raw entity name — which Citizens periodically reverts to "CIT-<id>" — and
        // leaked it into the turret-destroy message (the user's reported bug). Resolving NPC-ness
        // first lets a raider/ravager/turret always map to its canonical label.
        net.citizensnpcs.api.npc.NPC npc = null;
        try {
            npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getNPC(e);
        } catch (Throwable ignored) {}

        if (npc != null) {
            // (2) Turret killer → canonical slot/owner label.
            for (Claim claim : plugin.getClaimManager().all().values()) {
                for (Turret t : claim.getTurrets()) {
                    if (t.getNpcId() == npc.getId()) {
                        String owner = plugin.getClaimManager().resolveName(claim.getOwner());
                        return "Turret #" + (t.getSlot() + 1) + " (" + owner + ")";
                    }
                }
            }
            // (3) Raid raider/ravager → reconstruct "<attacker>'s Raider" from raid state.
            RaidEntityManager rem = plugin.getRaidEntities();
            if (rem != null && plugin.getRaidManager() != null) {
                UUID raidId = null;
                CustomRaider r = rem.getRaider(e.getUniqueId());
                if (r != null) raidId = r.getRaidId();
                else {
                    CustomRavager rv = rem.getRavager(e.getUniqueId());
                    if (rv != null) raidId = rv.getRaidId();
                }
                if (raidId != null) {
                    ActiveRaid raid = plugin.getRaidManager().getRaid(raidId);
                    if (raid != null) return raid.getAttackerName() + "'s Raider";
                }
            }
            // (4) Some other NPC — use its name unless it reverted to the Citizens default.
            String n = npc.getName();
            if (n != null && !n.isEmpty() && !n.startsWith("CIT-")) return n;
            return "a raider";
        }

        // (5) Real (non-NPC) player → their username.
        if (e instanceof Player p) return p.getName();

        // (6) Non-NPC vanilla entity — Bukkit getName() is the type-ish label.
        try {
            String n = e.getName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return e.getType().name();
    }

    private void applyStructureDamage(Turret t, int amount, ArmorStand phantom, Entity damager) {
        int next = t.getStructureHp() - amount;
        t.setStructureHp(Math.max(0, next));
        if (phantom != null) {
            Claim c = findClaimOf(t);
            int max = c != null ? Turret.maxHpForLevel(c.getSlotLevel(t.getSlot())) : Turret.MAX_STRUCTURE_HP;
            phantom.customName(buildHpLabel(t.getStructureHp(), max));
            lastHpShown.put(phantom.getUniqueId(), t.getStructureHp());
        }
        if (t.getStructureHp() <= 0) destroy(t, damager);
    }

    private void destroy(Turret t, Entity damager) {
        Claim owningClaim = findClaimOf(t);
        if (owningClaim == null) return;
        World world = Bukkit.getWorld(owningClaim.getWorldId());
        if (world == null) return;

        Location anchorAbove = new Location(world,
                t.getX() + 0.5, t.getY() + PHANTOM_DY, t.getZ() + 0.5);
        // True 50-block audibility: a single playSound attenuates to silence within ~12 blocks even
        // at high volume, so play it per-player near each listener instead (see playToRadius).
        playToRadius(anchorAbove, 50.0, Sound.BLOCK_ANVIL_DESTROY, 1.0f, 0.7f);

        String ownerName = plugin.getClaimManager().resolveName(owningClaim.getOwner());
        String killerName = damager != null ? readableKillerName(damager) : null;
        // Uniform death styling: victim (the turret) in RED, the description in GRAY, the
        // attacker in GREEN — matching every other elimination/death message.
        Component msg = Component.text("Turret #" + (t.getSlot() + 1) + " (" + ownerName + ")",
                        NamedTextColor.RED)
                .append(Component.text(" got temporary destroyed", NamedTextColor.GRAY));
        if (killerName != null) {
            msg = msg.append(Component.text(" by ", NamedTextColor.GRAY))
                    .append(Component.text(killerName, NamedTextColor.GREEN));
        }
        double rSq = DESTROY_SOUND_RADIUS * DESTROY_SOUND_RADIUS;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(anchorAbove) <= rSq) p.sendMessage(msg);
        }

        // Final panic-volley: 4 ShulkerBullets fan out in the cardinal directions, each acquiring
        // its own target via the regular bullet retargeting once it clears forced-exit. Has to
        // fire BEFORE the NPC despawn — once the turret entity is gone the combat manager can
        // still steer the bullets via the turret's coordinate anchor, but the audio/visual ties
        // back to the muzzle position. Fired even if the turret killed itself silently (e.g. raid
        // melee finished the structure off) so the destruction reads as "one last burst".
        if (plugin.getTurretCombat() != null) {
            int level = owningClaim.getSlotLevel(t.getSlot());
            plugin.getTurretCombat().firePanicVolley(world, t, level, owningClaim.getOwner());
        }

        if (plugin.getTurretEntities() != null) plugin.getTurretEntities().despawn(t);
        removePhantom(t);

        long respawnAt = System.currentTimeMillis() + RESPAWN_AFTER_MILLIS;
        t.setRespawnAtMillis(respawnAt);
        t.setStructureHp(0);
        // Also mirror the deadline onto the CLAIM SLOT so it survives /HomeSystem turret remove
        // followed by /HomeSystem turret deploy — the user can't shortcut the 5-minute downtime
        // by recycling the slot.
        owningClaim.setSlotRespawnAt(t.getSlot(), respawnAt);
        plugin.getClaimManager().save();

        // Attacker bonus check: if EVERY turret slot in this claim is simultaneously in
        // destroyed state right now (not "the 4th destruction event happened"), pay out the
        // +500 XP "Destroy All 4 Turret Bonus". The distinction matters: a turret respawns
        // after 5 min, so an attacker who downs #1-#3 quickly but takes 6 min to reach #4
        // ends up with #1 already back online when #4 falls → no bonus. They have to keep
        // all four down at the same moment. The check is cheap so we run it on every
        // destruction event; the markAllTurretsBonusAwarded flag makes the payout one-shot
        // per raid even if turrets respawn and all four get downed again.
        maybeAwardAllTurretsBonus(owningClaim);

        // Quest: a turret can only be destroyed by raiders during a raid — credit the raid's
        // attacker with one turret destruction.
        if (plugin.getQuests() != null && plugin.getRaidManager() != null) {
            ActiveRaid raid = plugin.getRaidManager().getRaidOnZone(owningClaim.getOwner());
            if (raid != null) {
                plugin.getQuests().add(raid.getAttackerId(), Quest.DESTROY_TURRETS, 1);   // lifetime
                raid.addTurretDestroyedThisRaid();
                if (raid.getTurretsDestroyedThisRaid() >= 10) {                            // in one raid
                    plugin.getQuests().complete(raid.getAttackerId(), Quest.DESTROY_TURRETS_ONE_RAID);
                }
            }
        }
    }

    /**
     * Pay the +500 XP "Destroy All 4 Turret Bonus" if and only if every turret slot in this
     * claim is currently in destroyed state AND an active raid is targeting the claim AND the
     * bonus hasn't been paid yet this raid. The "currently destroyed" check is the gate the
     * user explicitly asked for — a turret that respawned mid-rampage breaks the all-down
     * condition, so the bonus is genuinely about keeping all four offline at once.
     */
    private void maybeAwardAllTurretsBonus(Claim claim) {
        if (!allTurretsDestroyed(claim)) return;
        ActiveRaid raid = plugin.getRaidManager() == null
                ? null : plugin.getRaidManager().getRaidOnZone(claim.getOwner());
        if (raid == null) return;
        if (raid.isAllTurretsBonusAwarded()) return;
        raid.markAllTurretsBonusAwarded();
        plugin.getRaidManager().awardAttackerSideBonus(raid, 500,
                "Successfully Raided");
        // Punishment for losing every turret: leak the base's centre coordinates to ALL chat.
        // Rides the same one-shot flag as the bonus, so it fires once per raid at the exact
        // "everything is down at the same moment" instant the bonus itself is gated on.
        String ownerName = plugin.getClaimManager().resolveName(claim.getOwner());
        int centerX = (claim.getMinX() + claim.getMaxX()) / 2;
        int centerZ = (claim.getMinZ() + claim.getMaxZ()) / 2;
        RaidMessages.broadcastNegative(ownerName + "'s base has lost all its turrets — it lies at "
                + centerX + ", " + centerZ + ".");
    }

    /**
     * True iff NO turret is currently standing in this claim — i.e. every <em>deployed</em> turret is
     * in destroyed state, OR the claim has no turrets at all. This is the live "is the base wide open
     * right now" gate shared by the "Successfully Raided" payout, the chest lockout, and the
     * loot-steal ticker.
     *
     * <p><b>Count-agnostic by design.</b> Turrets are optional, so a base may have 0..{@link
     * Claim#MAX_TURRETS} deployed. We deliberately do NOT require a specific count: the only thing
     * that matters is whether anything is still defending. So a turretless base is breached the moment
     * a raid goes active (nothing to knock down → the attacker got lucky with a defenceless target),
     * and a partly-turreted base (1, 2, 3 turrets) is breached once its <em>last standing</em> turret
     * falls. A turret that respawns mid-raid flips this back to false until it's downed again.
     *
     * <p>The earlier implementation hard-coded "all 4 slots deployed AND destroyed", which made any
     * base with fewer than 4 turrets permanently un-breachable — i.e. building <em>fewer</em> turrets
     * made you safer. That inversion is the bug this fix removes.
     */
    public boolean allTurretsDestroyed(Claim claim) {
        if (claim == null) return false;
        for (Turret t : claim.getTurrets()) {      // getTurrets() = deployed slots only
            if (!t.isDestroyed()) return false;    // a live turret stands → not breached
        }
        return true;                               // nothing standing (incl. zero turrets) → breached
    }

    private void respawnDestroyed(Claim claim, World world, Turret t) {
        t.setRespawnAtMillis(0L);
        claim.setSlotRespawnAt(t.getSlot(), 0L);    // mirror clear
        int level = claim.getSlotLevel(t.getSlot());
        t.setStructureHp(Turret.maxHpForLevel(level));
        // Rebuild the structure. Normally the blocks survive downtime (destroy() doesn't clear
        // them), so this is a no-op idempotent. But if anything cleared them in the meantime —
        // a bug we already fixed, a future bug, or a player manually breaking through the cover
        // during downtime — respawn restores them so a destroyed-and-respawned turret is always
        // visually complete. TurretStructure.build is safe to call on already-built blocks.
        try { TurretStructure.place(world, t); } catch (Throwable ignored) {}
        if (plugin.getTurretEntities() != null) {
            int newId = plugin.getTurretEntities().spawn(world, t, level);
            if (newId >= 0) t.setNpcId(newId);
        }
        ensurePhantom(world, t);
        plugin.getClaimManager().save();
        plugin.getLogger().info("Turret #" + (t.getSlot() + 1) + " at " + t + " respawned after destruction.");
    }

    /**
     * One-shot startup heal: walks every claim's turrets and re-runs {@link TurretStructure#place}
     * on any alive turret whose anchor block is empty. The anchor block (turret.x, turret.y,
     * turret.z) is the bottom-center of the structure — layer 1 — and is the blackstone we place
     * first. If it's currently {@code AIR}, the structure was lost; rebuild.
     *
     * <p>Skips chunks that aren't loaded — the next time a player walks past, a future periodic
     * check could pick those up, but right now we just don't force-load. A user-noted broken
     * turret is by definition in a chunk the user just stood in, so the chunk's loaded.
     */
    private void healMissingStructuresOnce() {
        int rebuilt = 0;
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World w = Bukkit.getWorld(claim.getWorldId());
            if (w == null) continue;
            for (Turret t : claim.getTurrets()) {
                if (t.isDestroyed()) continue;
                if (!w.isChunkLoaded(t.getX() >> 4, t.getZ() >> 4)) continue;
                org.bukkit.Material anchorMat = w.getBlockAt(t.getX(), t.getY(), t.getZ()).getType();
                if (anchorMat == org.bukkit.Material.AIR
                        || anchorMat == org.bukkit.Material.CAVE_AIR
                        || anchorMat == org.bukkit.Material.VOID_AIR) {
                    try {
                        TurretStructure.place(w, t);
                        rebuilt++;
                        plugin.getLogger().info("Self-heal: rebuilt missing structure for slot #"
                                + (t.getSlot() + 1) + " at " + t);
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (rebuilt > 0) plugin.getLogger().info("Self-heal: " + rebuilt + " turret structure(s) rebuilt.");
    }

    private Claim findClaimOf(Turret t) {
        for (Claim claim : plugin.getClaimManager().all().values()) {
            for (Turret tt : claim.getTurrets()) if (tt == t) return claim;
        }
        return null;
    }

    // -- accessors ------------------------------------------------------------

    /**
     * Public lookup used by other managers (e.g. the entity manager when a turret is freshly
     * deployed and we want to initialise its HP to the level's tier max).
     */
    public int maxHpFor(int level) { return Turret.maxHpForLevel(level); }
}
