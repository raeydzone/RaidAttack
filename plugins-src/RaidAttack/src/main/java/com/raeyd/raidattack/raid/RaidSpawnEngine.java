package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.core.ComputePool;
import com.raeyd.raidattack.pathfinding.GroundPathPlanner;
import com.raeyd.raidattack.pathfinding.NavGrid;
import com.raeyd.raidattack.turret.Turret;
import com.raeyd.raidattack.turret.TurretCombatTuning;
import com.raeyd.raidattack.turret.TurretEntityManager;
import com.raeyd.raidattack.turret.TurretStructure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * The heart of an active raid: drives phase transitions, spawns raiders/ravagers at the
 * perimeter on a 1–3 s cadence, and runs a per-NPC AI tick that picks targets and steers
 * the Citizens navigator.
 *
 * <h3>Phase machine</h3>
 * <ul>
 *   <li>{@code DELAY} — boss bar visible, no spawns yet. When {@code now ≥ delayUntilMillis}
 *       we flip to {@code SPAWNING}.</li>
 *   <li>{@code SPAWNING} — while {@code remainingToSpawn > 0}, if {@code aliveCount <
 *       activeGoal} and the per-raid spawn cooldown elapsed, drop one new raider on the
 *       perimeter band (95% raider / 5% ravager). When the spawn budget is exhausted, flip
 *       to {@code DRAINING}.</li>
 *   <li>{@code DRAINING} — no new spawns; we just wait for {@link RaiderDamageListener}'s
 *       death event to call {@code noteDied} until the auto-end fires.</li>
 * </ul>
 *
 * <h3>Crash recovery</h3>
 * On {@link #start()}, every raid loaded from {@code raids.yml} that's already in
 * {@code SPAWNING}/{@code DRAINING} gets its {@code aliveCount} re-instantiated by spawning
 * fresh raiders at random perimeter spots. We reset the in-memory alive counter to 0 first
 * so {@code spawnOne(raid, countAsNew=false)} can bring it back up naturally — without
 * touching {@code spawnedSoFar}, which already accounted for those raiders pre-crash.
 *
 * <h3>AI — minimal viable</h3>
 * Per tick, each alive raider:
 * <ol>
 *   <li>Picks the nearest hostile target inside the zone (player who isn't friendly to the
 *       raid's attacker, or any deployed turret NPC).</li>
 *   <li>Updates the Citizens navigator destination if the cached target moved &gt; 2 blocks
 *       since the last update (avoids per-tick path replans).</li>
 *   <li>If within attack range, applies the configured damage on the cooldown timer.</li>
 *   <li>If no target exists, walks toward the zone centre so the raider doesn't loiter at
 *       the spawn perimeter forever.</li>
 * </ol>
 * Refinements like turret reach-with-Y-ignored, 20%-of-centre drift, and chase-break on
 * zone exit are slated for a follow-up pass once the base flow is verified in-game.
 */
public final class RaidSpawnEngine {

    /** Engine tick interval (server ticks). 5 = 4 Hz, plenty for both cadence and AI. */
    private static final long TICK_INTERVAL = 5L;

    /**
     * Flip to {@code true} to enable per-second AI/engine diagnostic log spam. Default
     * {@code true} while we're stabilising the AI — once the user confirms raiders move
     * false during normal testing to keep console and disk I/O quiet.
     */
    public static final boolean DEBUG_AI = false;

    /** Raider attack cooldown (server ticks). Vanilla iron sword is ~12.5 ticks → 13. */
    private static final int RAIDER_ATTACK_COOLDOWN_TICKS = 26;

    /** Random 1-3 s cadence between spawns when alive &lt; goal. */
    private static final long SPAWN_CADENCE_MIN_MS = 1_000L;
    private static final long SPAWN_CADENCE_MAX_MS = 3_000L;

    /** Probability of a ravager vs. raider on each spawn roll (per spec: 95/5). */
    private static final double RAVAGER_CHANCE = 0.05;

    /** Active-phase duration that triggers the attacker's "Sustain 15 min" bonus. */
    private static final long SUSTAINED_BONUS_THRESHOLD_MS = 15L * 60L * 1000L;

    /** Velocity applied by the local ground walker. Values are blocks per tick. */
    private static final double RAIDER_PATH_SPEED = 0.368;
    private static final double RAVAGER_PATH_SPEED = 0.247;

    /** A* replan interval. Replans for a raider that already has a path run OFF the main thread
     *  (see {@link #followGroundPath}), so we can afford to refresh paths more often than the old
     *  single-thread budget allowed — fresher pursuit, less "stale path" drift. */
    private static final long PATH_REPLAN_TICKS = 30L;
    /** How long a per-claim chunk snapshot is reused before being rebuilt (ticks). */
    private static final long NAV_TTL_TICKS = 100L;
    /** Node budget for OFF-THREAD replans — much higher than the synchronous first path since it
     *  doesn't block the tick, so raiders find longer/cleaner routes through big bases. */
    private static final int ASYNC_MAX_NODES = 24_000;
    /** Cardinal offsets for the 1-block "stay off lava edges" buffer in {@link #isPassableColumn}. */
    private static final int[][] LAVA_BUFFER_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Distance from a path waypoint before advancing to the next one. */
    private static final double WAYPOINT_REACHED_SQ = 0.85 * 0.85;

    /** How thick the perimeter spawn band is (inside-side), in blocks. */
    private static final int PERIMETER_DEPTH = 10;

    /** Pathfinder range for Citizens. Default is 16; way too small for 200×200 zones. */
    private static final float NAV_RANGE = 250.0f;

    /**
     * Stuck-teleport thresholds. A raider that hasn't moved more than
     * {@value #STUCK_MOVE_THRESHOLD} blocks (XZ) for {@value #STUCK_TELEPORT_TICKS} ticks AND
     * hasn't landed an attack in the last {@value #ATTACK_FRESHNESS_TICKS} ticks gets teleported
     * to a fresh perimeter spawn point. Same rule applies on newly-spawned raiders (their last-
     * move timer initialises to spawn-tick, so a raider that never finds traction gets a second
     * shot at it after 5 s). HP, target, and combat state survive the teleport.
     */
    private static final double STUCK_MOVE_THRESHOLD = 2.0;
    private static final double STUCK_MOVE_THRESHOLD_SQ = STUCK_MOVE_THRESHOLD * STUCK_MOVE_THRESHOLD;
    /**
     * 6 s @ 20 TPS. Was 5 s — bumped ~20% per user feedback ("teleport is a bit too aggressive,
     * I saw a raider about to attack the turret but got TP'd"). The longer window means the
     * raider has more time to commit to a fight before being yanked.
     */
    private static final long STUCK_TELEPORT_TICKS = 120L;
    private static final long ATTACK_FRESHNESS_TICKS = 40L;    // 2 s grace — recent hit = busy
    /**
     * If a raider has a target within this radius (blocks² in XZ) when the stuck timer would
     * otherwise fire, skip the teleport. They're engaged — even pre-first-swing approaches read
     * as engaged from the player's perspective, so yanking them out of a fight feels wrong.
     */
    private static final double ENGAGEMENT_RADIUS_SQ = 25.0;     // 5 blocks

    /**
     * Inner-rect fraction for "centre wander" when the AI has no target to chase. 0.20 = the
     * middle 20% of the zone (i.e. 20×20 inside a 100×100 zone). Raiders without a target pick
     * random points inside this rect and pace between them rather than camping on one block.
     */
    private static final double CENTRE_WANDER_FRACTION = 0.20;
    /** Re-pick the wander destination once the raider is within this many blocks (XZ) of it. */
    private static final double WANDER_REACHED_DISTANCE_SQ = 9.0;     // 3 blocks

    // -- anti-wall "impossible-to-reach" turret fallback --------------------------------------
    /**
     * PER-TURRET window. If, across ALL raid mobs currently targeting a given turret, NONE is in
     * melee range AND NONE has a viable GROUND path to it for this long, the turret is flagged
     * unreachable for the rest of the raid. The reachability test keys off the raiders' own ground
     * pathfinding — not whether the turret is hitting them — so a strong open turret that kills
     * besiegers before melee is left alone, while a 1-block projectile gap or a lava moat (no ground
     * route) trips the flag. A single besieger with a viable path resets the clock.
     */
    private static final long EMERGENCY_SIEGE_TICKS = 600L;           // 30 s
    /**
     * Anti-thrash gap between successive emergency teleports of the SAME raider. Once a turret is
     * flagged unreachable, every raider targeting it is teleported onto the +1 protection ring in
     * front of it the moment it holds that goal (effectively instant — no acquisition delay); this
     * just stops a raider that lands slightly off-band from re-teleporting every single tick.
     */
    private static final long EMERGENCY_TP_COOLDOWN_TICKS = 60L;      // 3 s

    private final HomeSystemPlugin plugin;
    /** raid id → next spawn epoch-ms. */
    private final Map<UUID, Long> nextSpawnAtByRaid = new HashMap<>();
    /** raider entity id → server tick of last successful attack (cooldown gate). */
    private final Map<UUID, Long> lastAttackTickByEntity = new HashMap<>();
    /** raider entity id -> current planned ground path + movement bookkeeping. */
    private final Map<UUID, WalkState> walkStateByEntity = new HashMap<>();
    /** Per-claim chunk-snapshot cache feeding off-thread A* replans. Built on the main thread,
     *  read by {@link ComputePool} workers. */
    private final NavGrid navGrid = new NavGrid();
    /** raider entity id → server tick at which we last detected meaningful movement. */
    private final Map<UUID, Long> lastMoveTickByEntity = new HashMap<>();
    /** raider entity id → position recorded on that last-move tick. */
    private final Map<UUID, Vector> lastMovePosByEntity = new HashMap<>();
    /** raider entity id → current centre-wander destination (re-picked on arrival). */
    private final Map<UUID, Location> wanderTargetByEntity = new HashMap<>();
    /** raid mob entity id → turret slot it currently has as its goal (-1 = none / a player). */
    private final Map<UUID, Integer> goalSlotByEntity = new HashMap<>();
    /** raid mob entity id → server tick of its last emergency teleport (anti-thrash cooldown). */
    private final Map<UUID, Long> lastEmergencyTpByEntity = new HashMap<>();
    /** raid id → (turret slot → per-turret siege state). Pruned when the raid ends. */
    private final Map<UUID, Map<Integer, TurretSiege>> siegesByRaid = new HashMap<>();

    /**
     * Engine-callback counter. Increments by 1 on every tick(). Used in place of
     * {@code Bukkit.getCurrentTick() % 20} for the debug-log gate: with TICK_INTERVAL=5,
     * the absolute server tick at each callback is {@code T + 5n} for some startup offset
     * {@code T}, which only hits {@code % 20 == 0} when T itself is 0/5/10/15 mod 20.
     * That's a coin flip on every startup — sometimes the logs never fired, which is what
     * misled the user into thinking "the AI doesn't run at all". Using a private counter
     * makes the cadence deterministic.
     */
    private int tickCount = 0;

    private BukkitTask task;

    public RaidSpawnEngine(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        recoverActiveRaids();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        nextSpawnAtByRaid.clear();
        lastAttackTickByEntity.clear();
        walkStateByEntity.clear();
        navGrid.clearAll();
        lastMoveTickByEntity.clear();
        lastMovePosByEntity.clear();
        wanderTargetByEntity.clear();
        goalSlotByEntity.clear();
        lastEmergencyTpByEntity.clear();
        siegesByRaid.clear();
    }

    /**
     * Hook called by {@link RaidManager#noteDied} the moment a raider falls. If we're still in
     * {@code SPAWNING} with spawns remaining, advance the per-raid cadence timer to "right now"
     * so the next engine tick immediately drops a replacement (drip behaviour, no waves).
     * Without this, a stale 1-3 s deadline could sit between us and the next spawn.
     */
    public void onRaiderDied(UUID raidId) {
        if (raidId == null) return;
        nextSpawnAtByRaid.put(raidId, System.currentTimeMillis());
    }

    /**
     * If a raid has been active (SPAWNING/DRAINING) for ≥15 minutes and the bonus hasn't
     * been paid yet, award the attacker-side "Sustain 15 min" +500 XP. One-shot per raid;
     * the markSustainedBonusAwarded flag stops a repeat payout on subsequent ticks.
     */
    private void maybeAwardSustainedBonus(ActiveRaid raid, long now) {
        if (raid.isSustainedBonusAwarded()) return;
        long start = raid.getActivePhaseStartMillis();
        if (start == 0L) return;     // active phase hasn't begun yet (still in DELAY)
        if (now - start < SUSTAINED_BONUS_THRESHOLD_MS) return;
        raid.markSustainedBonusAwarded();
        plugin.getRaidManager().awardAttackerSideBonus(raid, 500,
                "Sustain 15 min Bonus");
    }

    // -- crash recovery -------------------------------------------------------

    /**
     * Restore the alive cohort for every raid already past the pre-spawn delay. We reset the
     * in-memory aliveCount to 0 and spawn the saved alive count back without ticking the
     * spawned counter forward — the saved value already accounts for those raiders.
     */
    private void recoverActiveRaids() {
        for (ActiveRaid r : plugin.getRaidManager().allActive()) {
            if (r.getPhase() != ActiveRaid.Phase.SPAWNING && r.getPhase() != ActiveRaid.Phase.DRAINING) {
                continue;
            }
            int target = r.getAliveCount();
            if (target == 0) continue;
            r.restoreCounters(r.getSpawnedSoFar(), 0, r.getPhase());
            int respawned = 0;
            for (int i = 0; i < target; i++) {
                if (spawnOne(r, /* countAsNew= */ false)) respawned++;
            }
            plugin.getLogger().info("Raid " + shortId(r.getRaidId())
                    + " recovered: re-spawned " + respawned + "/" + target + " from saved alive count.");
        }
        plugin.getRaidManager().save();
    }

    // -- tick -----------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();
        long currentTick = Bukkit.getCurrentTick();
        tickCount++;
        // Log every 4th callback = every ~1 s (since TICK_INTERVAL = 5 ticks). Self-counter
        // avoids the modulo-alignment trap noted in the field-level comment.
        boolean logNow = DEBUG_AI && (tickCount % 4 == 0);

        if (logNow) {
            plugin.getLogger().info(String.format(
                    "Engine tick #%d: %d raid(s), %d raider(s) + %d ravager(s) tracked",
                    tickCount,
                    plugin.getRaidManager().allActive().size(),
                    plugin.getRaidEntities().allRaiders().size(),
                    plugin.getRaidEntities().allRavagers().size()));
        }

        // Phase + spawn loop. Snapshot list so endRaid mutations don't break the iterator.
        for (ActiveRaid raid : new ArrayList<>(plugin.getRaidManager().allActive())) {
            Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
            if (zone == null) {
                // Owner unclaimed mid-raid — end cleanly.
                plugin.getRaidManager().endRaid(raid.getRaidId(), "zone unclaimed mid-raid");
                continue;
            }
            switch (raid.getPhase()) {
                case DELAY:
                    if (now >= raid.getDelayUntilMillis()) {
                        raid.setPhase(ActiveRaid.Phase.SPAWNING);
                        raid.markActivePhaseStartedIfNeeded(now);
                        nextSpawnAtByRaid.put(raid.getRaidId(), now);  // first spawn ASAP
                        plugin.getRaidManager().save();
                        plugin.getLogger().info("Raid " + shortId(raid.getRaidId())
                                + " entering SPAWNING phase.");
                    }
                    break;
                case SPAWNING:
                    handleSpawning(raid, now);
                    maybeAwardSustainedBonus(raid, now);
                    break;
                case DRAINING:
                    maybeAwardSustainedBonus(raid, now);
                    break;
                case ENDED:
                default:
                    break;
            }
        }

        // AI loop. Snapshot so concurrent deaths (which trim the manager's lists) don't blow up.
        for (CustomRaider r : new ArrayList<>(plugin.getRaidEntities().allRaiders())) {
            if (r.getRaidId() == null || !r.isAlive()) continue;
            ActiveRaid raid = plugin.getRaidManager().getRaid(r.getRaidId());
            if (raid == null) continue;
            tickAI(r.getNpc(), r.getEntity(), raid,
                    CustomRaider.ATTACK_RANGE_BLOCKS, CustomRaider.ATTACK_DAMAGE,
                    RAIDER_ATTACK_COOLDOWN_TICKS, currentTick, logNow);
        }
        for (CustomRavager r : new ArrayList<>(plugin.getRaidEntities().allRavagers())) {
            if (r.getRaidId() == null || !r.isAlive()) continue;
            ActiveRaid raid = plugin.getRaidManager().getRaid(r.getRaidId());
            if (raid == null) continue;
            tickAI(r.getNpc(), r.getEntity(), raid,
                    CustomRavager.ATTACK_RANGE_BLOCKS, CustomRavager.ATTACK_DAMAGE,
                    CustomRavager.ATTACK_COOLDOWN_TICKS, currentTick, logNow);
        }

        // Per-turret unreachability detection. Runs AFTER the AI loop so goal slots are fresh for
        // this tick; the fallback teleport (in tickAI) reads the flag set here on the next tick.
        List<ActiveRaid> active = new ArrayList<>(plugin.getRaidManager().allActive());
        for (ActiveRaid raid : active) updateTurretSieges(raid, currentTick);
        if (!siegesByRaid.isEmpty()) {
            Set<UUID> activeIds = new HashSet<>();
            for (ActiveRaid raid : active) activeIds.add(raid.getRaidId());
            siegesByRaid.keySet().retainAll(activeIds);   // drop ended raids' "unreachable" flags
        }
    }

    // -- spawn cadence --------------------------------------------------------

    private void handleSpawning(ActiveRaid raid, long now) {
        if (raid.remainingToSpawn() == 0) {
            // Budget exhausted — flip to draining. From here noteDied auto-ends when the last
            // alive raider falls.
            raid.setPhase(ActiveRaid.Phase.DRAINING);
            plugin.getRaidManager().save();
            return;
        }
        if (raid.getAliveCount() >= raid.getActiveGoal()) return;
        Long nextAt = nextSpawnAtByRaid.get(raid.getRaidId());
        if (nextAt != null && now < nextAt) return;

        spawnOne(raid, true);
        // Schedule next spawn 1-3s out.
        long delay = SPAWN_CADENCE_MIN_MS
                + ThreadLocalRandom.current().nextLong(SPAWN_CADENCE_MAX_MS - SPAWN_CADENCE_MIN_MS + 1);
        nextSpawnAtByRaid.put(raid.getRaidId(), now + delay);
    }

    /**
     * Spawn one raider or ravager (95/5 roll) at a random perimeter location. Returns true on
     * success. When {@code countAsNew} is true we bump the spawn counter; pass false for the
     * crash-recovery path so we don't double-count raiders that pre-existed the restart.
     */
    private boolean spawnOne(ActiveRaid raid, boolean countAsNew) {
        Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (zone == null) return false;
        World world = Bukkit.getWorld(raid.getWorldId());
        if (world == null) return false;
        Location at = pickPerimeterSpawn(world, zone);
        if (at == null) {
            plugin.getLogger().warning("Raid " + shortId(raid.getRaidId())
                    + ": could not pick a perimeter spawn location after 30 attempts.");
            return false;
        }
        boolean isRavager = ThreadLocalRandom.current().nextDouble() < RAVAGER_CHANCE;
        boolean success;
        NPC npc;
        if (isRavager) {
            CustomRavager rv = new CustomRavager(plugin, raid.getAttackerName(), raid.getRaidId());
            success = rv.spawn(at);
            npc = success ? rv.getNpc() : null;
            if (success) plugin.getRaidEntities().register(rv);
        } else {
            CustomRaider rd = new CustomRaider(plugin, raid.getAttackerName(), raid.getRaidId());
            success = rd.spawn(at);
            npc = success ? rd.getNpc() : null;
            if (success) plugin.getRaidEntities().register(rd);
        }
        if (!success) return false;
        tuneNavigator(npc);
        if (countAsNew) {
            plugin.getRaidManager().noteSpawned(raid);
        } else {
            // Crash recovery: aliveCount was reset to 0 by recoverActiveRaids; we bump it back
            // up here manually so the saved cohort matches. spawnedSoFar is untouched.
            raid.restoreCounters(raid.getSpawnedSoFar(), raid.getAliveCount() + 1, raid.getPhase());
        }
        return true;
    }

    /**
     * Tune the per-NPC navigator parameters. Citizens defaults to a 16-block search range
     * which is far too small for a 200×200 base, and the legacy pathfinder struggles with
     * complex interior geometry. The new pathfinder ("MCMMO" path engine) handles overhangs,
     * ladders, and stair-step heuristics much better, so we opt in explicitly per-NPC.
     */
    private static void tuneNavigator(NPC npc) {
        if (npc == null) return;
        try {
            var params = npc.getNavigator().getLocalParameters();
            params.range(NAV_RANGE);
            params.speedModifier(1.0f);
            params.useNewPathfinder(true);
        } catch (Throwable t) {
            // Citizens API surface varies a bit between builds — don't crash the engine on a
            // failed tuning, just leave the defaults.
        }
    }

    // -- perimeter spawn picker -----------------------------------------------

    /**
     * Pick a random standable Y on a random perimeter cell within the 10-block-thick band
     * just inside the zone's edge. Tries up to 30 cells; gives up if every one is in an
     * unloaded chunk or has no surface (very rare for normal terrain).
     *
     * <p><b>Safety check.</b> Beyond "chunk loaded + highest block exists", we also verify the
     * spot is genuinely walkable: solid (or water) block directly below, and two blocks of
     * passable air above for clearance. Without this, {@code getHighestBlockYAt} happily returns
     * leaf-canopy Y values — a raider teleported onto leaves falls through and immediately re-
     * triggers the stuck-detection on the next cycle, producing the "teleport loop" the user saw.
     */
    private Location pickPerimeterSpawn(World world, Claim zone) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int sizeX = zone.sizeX();
        int sizeZ = zone.sizeZ();
        for (int attempt = 0; attempt < 30; attempt++) {
            int side = r.nextInt(4);  // 0=N, 1=S, 2=W, 3=E
            int depth = r.nextInt(PERIMETER_DEPTH);
            int x, z;
            switch (side) {
                case 0 -> { x = zone.getMinX() + r.nextInt(sizeX); z = zone.getMinZ() + depth; }
                case 1 -> { x = zone.getMinX() + r.nextInt(sizeX); z = zone.getMaxZ() - depth; }
                case 2 -> { x = zone.getMinX() + depth; z = zone.getMinZ() + r.nextInt(sizeZ); }
                default -> { x = zone.getMaxX() - depth; z = zone.getMinZ() + r.nextInt(sizeZ); }
            }
            int cx = x >> 4, cz = z >> 4;
            if (!world.isChunkLoaded(cx, cz)) continue;
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 1 || y > world.getMaxHeight() - 1) continue;
            if (!isSafeStandable(world, x, y, z)) continue;
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /**
     * Verify a cell is a sensible place to drop a raider. Requires:
     * <ul>
     *   <li>The block at {@code y-1} is solid OR water (water is fine — they swim).</li>
     *   <li>The block at {@code y} and {@code y+1} is passable (clearance for the player body).</li>
     * </ul>
     * This rejects the common bad cases: leaf canopies (passable below = raider falls through),
     * fence posts (solid above the spawn = head clips), and 1-block ceilings.
     */
    private static boolean isSafeStandable(World world, int x, int y, int z) {
        if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) return false;
        org.bukkit.block.Block below = world.getBlockAt(x, y - 1, z);
        org.bukkit.Material belowMat = below.getType();
        boolean groundLike = belowMat.isSolid() || belowMat == org.bukkit.Material.WATER;
        if (!groundLike) return false;
        if (!world.getBlockAt(x, y, z).isPassable()) return false;
        if (!world.getBlockAt(x, y + 1, z).isPassable()) return false;
        return true;
    }

    // -- AI tick --------------------------------------------------------------

    private void tickAI(NPC npc, LivingEntity self, ActiveRaid raid,
                        double attackRange, double attackDmg, int cooldownTicks,
                        long currentTick, boolean logNow) {
        if (npc == null || self == null) return;
        Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (zone == null) return;

        LivingEntity target = findNearestTarget(raid, zone, self);

        // Record which turret (if any) this mob is currently going for, and when it acquired that
        // goal — feeds the per-turret siege detector and the anti-wall fallback teleport below.
        Turret goalTurret = (target != null) ? turretForTarget(zone, target) : null;
        updateGoal(self.getUniqueId(), goalTurret);

        // -- anti-wall fallback (takes priority over the perimeter stuck-teleport) ------------
        // The moment this mob's goal turret is flagged unreachable for the raid, teleport it onto
        // the +1 protection ring in front of the turret (instant — no acquisition delay), unless
        // it's already in attack range. A short per-mob cooldown only prevents re-teleport thrash
        // if it lands slightly off-band. Only the mobs targeting THAT turret are moved; current
        // and future raiders that pick it up get pulled in too.
        if (goalTurret != null && isTurretUnreachable(raid.getRaidId(), goalTurret.getSlot())) {
            boolean inRange = canRaidMobHitTurret(self, target, goalTurret, attackRange);
            Long lastTp = lastEmergencyTpByEntity.get(self.getUniqueId());
            if (!inRange && (lastTp == null || currentTick - lastTp >= EMERGENCY_TP_COOLDOWN_TICKS)) {
                int clearance = self instanceof Ravager ? 3 : 2;
                Location front = turretApproachPoint(zone, goalTurret, self.getLocation(), clearance);
                if (front != null) {
                    // Use the Citizens-aware teleport: a plain entity teleport can leave a player-NPC
                    // "frozen" until something nudges it (the symptom where a manual 1-block push
                    // re-activates the raider). Cancel its navigator and give a small shove toward the
                    // turret to re-kick the physics tick and settle it inside the damage band.
                    boolean tped = false;
                    try {
                        npc.teleport(front, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
                        tped = true;
                    } catch (Throwable ignored) {}
                    if (!tped) { try { self.teleport(front); } catch (Throwable ignored) {} }
                    try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}
                    Vector shove = new Vector(goalTurret.getX() + 0.5 - front.getX(), 0.0,
                            goalTurret.getZ() + 0.5 - front.getZ());
                    try {
                        if (shove.lengthSquared() > 1.0e-4) self.setVelocity(shove.normalize().multiply(0.18));
                        else self.setVelocity(new Vector(0.0, -0.1, 0.0));
                    } catch (Throwable ignored) {}
                    lastEmergencyTpByEntity.put(self.getUniqueId(), currentTick);
                    walkStateByEntity.remove(self.getUniqueId());
                    lastMovePosByEntity.put(self.getUniqueId(), front.toVector());
                    lastMoveTickByEntity.put(self.getUniqueId(), currentTick);
                    return;
                }
            }
        }

        // -- stuck-detection teleport ---------------------------------------------------------
        // Per-raider tracker: if XZ displacement < 2 blocks for ≥ 6 s AND no attack landed in
        // the last 2 s AND we're NOT engaged with a target within 5 blocks, yank the raider to
        // a fresh perimeter spawn point. State (HP, target intent, attack cooldown) survives;
        // only walk plans get blown away so the new position doesn't chase stale waypoints.
        // Target lookup happens first so the engaged-check has the answer; the cost is the same
        // as running it after — findNearestTarget is the next step anyway.
        if (handleStuckTeleport(self, zone, currentTick, target)) {
            return;     // teleport happened; let the next tick pick a target from the new spot
        }

        if (logNow) {
            String tgt = target == null ? "NONE"
                    : (target instanceof Player p ? p.getName() : target.getType().name());
            double dist = target == null ? -1.0
                    : self.getLocation().distance(target.getLocation());
            plugin.getLogger().info(String.format(
                    "AI npc#%d (%s): tgt=%s dist=%.1f pos=%s",
                    npc.getId(),
                    self instanceof Player ? "raider" : "ravager",
                    tgt, dist, fmtLoc(self.getLocation())));
        }

        // Step distance per AI callback. Engine ticks every TICK_INTERVAL=5 server ticks, so
        // this is "blocks per 250 ms". Tuned to feel like a brisk human walk for raiders and
        // an unhurried charge for ravagers; precise spec compliance can come from feedback.
        double speed = movementSpeed(self);

        if (target != null) {
            // For turret targets, "reach" ignores Y so a turret on a tall column is still
            // attackable from the ground square in front of it (spec). For players the regular
            // 3D distance is correct. (goalTurret was already resolved above.)
            Turret turret = goalTurret;
            int clearance = self instanceof Ravager ? 3 : 2;
            Location attackPoint = turret != null
                    ? turretApproachPoint(zone, turret, self.getLocation(), clearance)
                    : target.getLocation();
            if (attackPoint == null) attackPoint = target.getLocation();
            double dist = turret != null
                    ? horizontalDistanceToTurretDamageBand(self.getLocation(), turret)
                    : self.getLocation().distance(attackPoint);

            boolean inAttackRange = turret != null
                    ? canRaidMobHitTurret(self, target, turret, attackRange)
                    : dist <= attackRange + 0.25;
            if (inAttackRange) {
                stopHorizontalMotion(self);
                Long lastAttack = lastAttackTickByEntity.get(self.getUniqueId());
                if (lastAttack == null || currentTick - lastAttack >= cooldownTicks) {
                    try { npc.faceLocation(target.getLocation()); } catch (Throwable ignored) {}
                    if (turret != null) {
                        performTurretAttack(self, turret, attackDmg);
                    } else {
                        performAttack(self, target, attackDmg);
                    }
                    lastAttackTickByEntity.put(self.getUniqueId(), currentTick);
                }
                return;
            }
            // Follow a planned ground path using velocity so clients see real walking rather
            // than teleport snapping.
            followGroundPath(npc, self, target.getUniqueId(), attackPoint, zone, speed, currentTick);
            return;
        }

        // No target — wander between random points inside the centre-20% rect of the zone.
        // Pacing in the middle reads as "raiders looking for something to do" while still being
        // in the spot a defender is most likely to engage them. New destination is picked on
        // arrival (XZ distance < 3 b) or when the cached one is stale (different world / lost).
        Location wander = pickOrRefreshWanderDestination(self, zone);
        if (wander != null) {
            followGroundPath(npc, self, null, wander, zone, speed, currentTick);
        } else {
            stopHorizontalMotion(self);
        }
    }

    /**
     * Step the stuck-tracker for this raider and teleport if the thresholds were hit.
     * Returns {@code true} iff a teleport happened — the caller short-circuits the rest of the
     * AI tick so the new position is re-evaluated on the next callback.
     */
    private boolean handleStuckTeleport(LivingEntity self, Claim zone, long currentTick,
                                        LivingEntity target) {
        UUID id = self.getUniqueId();
        Vector currentPos = self.getLocation().toVector();
        Vector lastPos = lastMovePosByEntity.get(id);
        Long lastMoveAt = lastMoveTickByEntity.get(id);
        if (lastPos == null || lastMoveAt == null) {
            // First sighting (just spawned / first AI tick): seed the tracker. Next tick onwards
            // the 6 s clock starts here, so a never-moving raider gets teleported 6 s after spawn.
            lastMovePosByEntity.put(id, currentPos);
            lastMoveTickByEntity.put(id, currentTick);
            return false;
        }
        double dx = currentPos.getX() - lastPos.getX();
        double dz = currentPos.getZ() - lastPos.getZ();
        if (dx * dx + dz * dz >= STUCK_MOVE_THRESHOLD_SQ) {
            // Meaningful move — reset the timer and keep going.
            lastMovePosByEntity.put(id, currentPos);
            lastMoveTickByEntity.put(id, currentTick);
            return false;
        }
        if (currentTick - lastMoveAt < STUCK_TELEPORT_TICKS) return false;

        // Idle long enough — but skip the teleport if the raider's been swinging recently. That
        // covers fights against a turret where the raider is correctly stationary while attacking.
        Long lastAttack = lastAttackTickByEntity.get(id);
        if (lastAttack != null && currentTick - lastAttack < ATTACK_FRESHNESS_TICKS) return false;

        // Also skip when the raider has a target within engagement range. Approach-without-yet-
        // swinging reads as "engaged" to the player; pulling them out is what the user noticed
        // as "raider about to attack but got teleported".
        if (target != null) {
            double tx = target.getLocation().getX() - currentPos.getX();
            double tz = target.getLocation().getZ() - currentPos.getZ();
            if (tx * tx + tz * tz <= ENGAGEMENT_RADIUS_SQ) return false;
        }

        // Re-use the perimeter spawn picker — it's the proven good-spot logic already used at
        // raid start, so a teleported raider always lands in a sensible band rather than e.g.
        // mid-air inside a tree.
        Location to = pickPerimeterSpawn(self.getWorld(), zone);
        if (to == null) return false;
        try { self.teleport(to); } catch (Throwable ignored) { return false; }
        // Reset trackers so the new position is the baseline.
        lastMovePosByEntity.put(id, to.toVector());
        lastMoveTickByEntity.put(id, currentTick);
        // Drop stale walk plans + wander cache so the next tick re-plans from the new position.
        walkStateByEntity.remove(id);
        wanderTargetByEntity.remove(id);
        if (DEBUG_AI) plugin.getLogger().info(String.format(
                "AI stuck-teleport: entity %s → %s",
                id.toString().substring(0, 8), fmtLoc(to)));
        return true;
    }

    /**
     * Pick a random point inside the inner {@value #CENTRE_WANDER_FRACTION}-fraction rect of the
     * zone, cached per-raider so they walk to it rather than re-routing every tick. Re-picks on
     * arrival (within 3 b XZ) or when the cached destination is no longer valid.
     */
    private Location pickOrRefreshWanderDestination(LivingEntity self, Claim zone) {
        UUID id = self.getUniqueId();
        Location cached = wanderTargetByEntity.get(id);
        World world = self.getWorld();
        boolean stale = cached == null
                || cached.getWorld() == null
                || cached.getWorld() != world
                || horizontalDistanceSquared(self.getLocation(), cached) <= WANDER_REACHED_DISTANCE_SQ;
        if (!stale) return cached;
        Location fresh = pickInnerWanderPoint(world, zone);
        if (fresh != null) {
            wanderTargetByEntity.put(id, fresh);
            return fresh;
        }
        return cached;     // keep the old one if we couldn't pick a new one
    }

    /**
     * Random standable point inside the centre-{@value #CENTRE_WANDER_FRACTION}-fraction rect of
     * {@code zone}. Returns null only if every probe landed in unloaded chunks (rare for normal
     * worlds — the centre of an active raid zone is virtually always loaded).
     */
    private static Location pickInnerWanderPoint(World world, Claim zone) {
        if (world == null || zone == null) return null;
        int sizeX = zone.sizeX();
        int sizeZ = zone.sizeZ();
        int innerSizeX = Math.max(1, (int) Math.round(sizeX * CENTRE_WANDER_FRACTION));
        int innerSizeZ = Math.max(1, (int) Math.round(sizeZ * CENTRE_WANDER_FRACTION));
        int innerMinX = zone.getMinX() + (sizeX - innerSizeX) / 2;
        int innerMinZ = zone.getMinZ() + (sizeZ - innerSizeZ) / 2;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = innerMinX + r.nextInt(innerSizeX);
            int z = innerMinZ + r.nextInt(innerSizeZ);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 1 || y > world.getMaxHeight() - 1) continue;
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void followGroundPath(NPC npc, LivingEntity self, UUID targetId, Location destination,
                                  Claim zone, double speed, long currentTick) {
        if (destination == null || destination.getWorld() == null) {
            stopHorizontalMotion(self);
            return;
        }
        Location current = self.getLocation();
        if (current.getWorld() == null || current.getWorld() != destination.getWorld()) {
            stopHorizontalMotion(self);
            return;
        }

        UUID entityId = self.getUniqueId();
        WalkState state = walkStateByEntity.get(entityId);
        if (state == null) {
            state = new WalkState(targetId, destination.clone(), current.clone(), currentTick);
            walkStateByEntity.put(entityId, state);
        }

        boolean targetChanged = !sameTarget(state.targetId, targetId)
                || state.destination == null
                || state.destination.getWorld() != destination.getWorld()
                || state.destination.distanceSquared(destination) > 9.0;
        if (targetChanged) {
            state.reset(targetId, destination.clone(), current.clone(), currentTick);
        }

        int clearance = self instanceof Ravager ? 3 : 2;

        // Apply a finished off-thread replan (computed on a worker against an immutable snapshot).
        if (state.pendingPath != null && state.pendingPath.isDone()) {
            try {
                List<Location> p = state.pendingPath.get();
                if (p != null && !p.isEmpty()) {
                    state.waypoints = p;
                    state.waypointIndex = 0;
                    state.pathFailed = false;
                }
            } catch (Throwable ignored) { /* cancelled / failed → keep current path */ }
            state.pendingPath = null;
        }

        boolean hasUsablePath = state.waypoints != null && state.waypointIndex < state.waypoints.size();
        boolean needsPath = currentTick >= state.replanAtTick
                || (!state.pathFailed && (state.waypoints == null || state.waypointIndex >= state.waypoints.size()));
        if (needsPath) {
            state.replanAtTick = currentTick + PATH_REPLAN_TICKS + ThreadLocalRandom.current().nextLong(0, 16);
            boolean async = plugin.isAsyncPathfinding() && plugin.getComputePool() != null;
            if (async && hasUsablePath && state.pendingPath == null) {
                // Replan in the BACKGROUND while the raider keeps walking its current path. Snapshot
                // the claim's blocks on this (main) thread, then run the A* on a worker thread.
                World w = current.getWorld();
                NavGrid.SnapshotBlockSource src = navGrid.forClaim(w, zone, currentTick, NAV_TTL_TICKS);
                final Location from = current.clone();
                final Location to = destination.clone();
                final int cl = clearance;
                try {
                    state.pendingPath = plugin.getComputePool().submit(
                            () -> GroundPathPlanner.findPath(src, from, to, zone, cl, ASYNC_MAX_NODES));
                } catch (Throwable t) {
                    state.pendingPath = null;   // pool unavailable → synchronous next time
                }
            } else if (state.pendingPath == null) {
                // No path to follow yet (first path / exhausted) or async disabled — compute it
                // synchronously NOW against the live world so the raider never freezes waiting.
                List<Location> path = GroundPathPlanner.findPath(
                        new NavGrid.LiveBlockSource(current.getWorld()), current, destination, zone, clearance);
                if (path != null && !path.isEmpty()) {
                    state.waypoints = path;
                    state.waypointIndex = 0;
                    state.pathFailed = false;
                } else {
                    state.waypoints = null;
                    state.waypointIndex = 0;
                    state.pathFailed = true;
                }
            }
        }

        Location moveGoal = destination;
        if (state.waypoints != null && !state.waypoints.isEmpty()) {
            while (state.waypointIndex < state.waypoints.size() - 1
                    && horizontalDistanceSquared(current, state.waypoints.get(state.waypointIndex)) <= WAYPOINT_REACHED_SQ) {
                state.waypointIndex++;
            }
            if (state.waypointIndex < state.waypoints.size()) {
                moveGoal = state.waypoints.get(state.waypointIndex);
            }
        }

        Vector desired = moveGoal.toVector().subtract(current.toVector());
        desired.setY(0.0);
        if (desired.lengthSquared() < 0.04) {
            stopHorizontalMotion(self);
            return;
        }
        desired.normalize();

        boolean stuck = updateWalkState(state, current, currentTick);
        MoveChoice choice = chooseWalkDirection(self, desired, zone, clearance, speed, stuck, state.sideSign);
        if (choice == null && stuck) {
            state.sideSign = -state.sideSign;
            choice = chooseWalkDirection(self, desired, zone, clearance, speed, true, state.sideSign);
        }
        if (choice == null) {
            stopHorizontalMotion(self);
            faceHorizontally(npc, self, desired);
            return;
        }

        if (choice.sideSign != 0) state.sideSign = choice.sideSign;
        applyWalkVelocity(npc, self, choice.direction, speed, choice.jump, state, currentTick);
    }

    private static boolean sameTarget(UUID a, UUID b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean updateWalkState(WalkState state, Location current, long currentTick) {
        if (currentTick - state.lastCheckTick < 10) return state.stuckChecks >= 2;
        double moved = horizontalDistanceSquared(current, state.lastCheckLocation);
        if (moved < 0.09) state.stuckChecks++;
        else state.stuckChecks = 0;
        state.lastCheckLocation = current.clone();
        state.lastCheckTick = currentTick;
        return state.stuckChecks >= 2;
    }

    private static MoveChoice chooseWalkDirection(LivingEntity self, Vector desired, Claim zone,
                                                  int clearance, double speed, boolean stuck,
                                                  int sideSign) {
        int sign = sideSign >= 0 ? 1 : -1;
        double[] angles = stuck
                ? new double[] { 35.0 * sign, 70.0 * sign, 110.0 * sign,
                        -35.0 * sign, -70.0 * sign, -110.0 * sign,
                        0.0, 160.0 * sign, -160.0 * sign }
                : new double[] { 0.0, 25.0 * sign, -25.0 * sign,
                        50.0 * sign, -50.0 * sign, 80.0 * sign, -80.0 * sign };
        for (double angle : angles) {
            MoveChoice choice = probeWalk(self, desired, angle, zone, clearance, speed);
            if (choice != null) return choice;
        }
        return null;
    }

    private static MoveChoice probeWalk(LivingEntity self, Vector desired, double angleDegrees,
                                        Claim zone, int clearance, double speed) {
        Location current = self.getLocation();
        World world = current.getWorld();
        if (world == null) return null;

        Vector direction = rotateY(desired, angleDegrees);
        if (direction.lengthSquared() < 0.0001) return null;
        direction.normalize();

        double lookAhead = Math.max(0.7, Math.min(1.4, speed * TICK_INTERVAL + 0.35));
        Location probe = current.clone().add(direction.clone().multiply(lookAhead));
        int x = probe.getBlockX();
        int y = current.getBlockY();
        int z = probe.getBlockZ();

        if (isStandableAt(world, x, y, z, zone, clearance)) {
            return new MoveChoice(direction, false, signOf(angleDegrees));
        }
        if (canDropInto(world, x, y, z, zone, clearance)) {
            return new MoveChoice(direction, false, signOf(angleDegrees));
        }
        if (isOnGround(self) && isStandableAt(world, x, y + 1, z, zone, clearance)) {
            return new MoveChoice(direction, true, signOf(angleDegrees));
        }
        return null;
    }

    private static void applyWalkVelocity(NPC npc, LivingEntity self, Vector direction,
                                          double speed, boolean jump, WalkState state,
                                          long currentTick) {
        // -- swim branch ----------------------------------------------------------------------
        // When the raider is submerged, keep full horizontal speed (the 0.35× air-multiplier
        // would otherwise crawl them along the riverbed) and apply a gentle upward Y bias so
        // they swim forward near the surface instead of sinking. This is what makes a raider
        // crossing a pond feel like swimming rather than walking underwater.
        boolean inWater = false;
        try { inWater = self.isInWater(); } catch (Throwable ignored) {}
        if (inWater) {
            Vector swimDir = direction.clone().normalize().multiply(speed);
            faceHorizontally(npc, self, direction);
            // +0.08 b/t ≈ floats up at ~1.6 b/s — enough to clear into the surface layer within
            // a couple of ticks and stay there. Vanilla water gravity is ~-0.04/t, so the net
            // upward motion is ~+0.04/t which feels like a relaxed swim stroke.
            self.setVelocity(new Vector(swimDir.getX(), 0.08, swimDir.getZ()));
            return;
        }

        boolean grounded = isOnGround(self);
        double appliedSpeed = grounded || jump ? speed : speed * 0.35;
        Vector horizontal = direction.clone().normalize().multiply(appliedSpeed);

        double y = self.getVelocity().getY();
        if (jump && grounded) {
            y = Math.max(y, 0.42);
            state.lastJumpTick = currentTick;
        } else if (grounded) {
            y = 0.0;
        } else if (currentTick - state.lastJumpTick > 10L && y > 0.0) {
            y = 0.0;
        }

        faceHorizontally(npc, self, direction);
        self.setVelocity(new Vector(horizontal.getX(), y, horizontal.getZ()));
    }

    private static void faceHorizontally(NPC npc, LivingEntity self, Vector direction) {
        Location look = self.getLocation().clone().add(direction.clone().setY(0.0));
        try { npc.faceLocation(look); } catch (Throwable ignored) {}
    }

    private static Vector rotateY(Vector v, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vector(v.getX() * cos - v.getZ() * sin, 0.0,
                v.getX() * sin + v.getZ() * cos);
    }

    private static int signOf(double v) {
        if (v > 0.0) return 1;
        if (v < 0.0) return -1;
        return 0;
    }

    private static boolean isOnGround(LivingEntity self) {
        try { return self.isOnGround(); }
        catch (Throwable ignored) { return false; }
    }

    private static boolean isStandableAt(World world, int x, int y, int z, Claim zone, int clearance) {
        if (!isPassableColumn(world, x, y, z, zone, clearance)) return false;
        return world.getBlockAt(x, y - 1, z).getType().isSolid();
    }

    private static boolean canDropInto(World world, int x, int y, int z, Claim zone, int clearance) {
        if (y <= world.getMinHeight() + 1) return false;
        if (!isPassableColumn(world, x, y, z, zone, clearance)) return false;
        return !world.getBlockAt(x, y - 1, z).getType().isSolid();
    }

    private static boolean isPassableColumn(World world, int x, int y, int z,
                                            Claim zone, int clearance) {
        if (!inLoadedChunk(world, x, z)) return false;
        if (!insideZone(world, x, z, zone)) return false;
        if (y <= world.getMinHeight() || y + clearance - 1 >= world.getMaxHeight()) return false;
        for (int i = 0; i < clearance; i++) {
            var b = world.getBlockAt(x, y + i, z);
            // Reject lava/fire even though it's "passable" — raiders must never walk through a lava
            // moat to a turret (the same hazard rule the A* planner uses). They route around it.
            if (!b.isPassable() || NavGrid.isPathHazard(b.getType())) return false;
        }
        // 1-block buffer: also reject a cell whose neighbour (feet or floor level) is lava/fire, so a
        // raider's hitbox/velocity can't clip a lava edge while walking past a moat.
        for (int[] o : LAVA_BUFFER_OFFSETS) {
            if (NavGrid.isPathHazard(world.getBlockAt(x + o[0], y, z + o[1]).getType())) return false;
            if (NavGrid.isPathHazard(world.getBlockAt(x + o[0], y - 1, z + o[1]).getType())) return false;
        }
        return true;
    }

    private static boolean inLoadedChunk(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }

    private static boolean insideZone(World world, int x, int z, Claim zone) {
        if (zone == null) return true;
        if (!world.getUID().equals(zone.getWorldId())) return false;
        return x >= zone.getMinX() && x <= zone.getMaxX()
                && z >= zone.getMinZ() && z <= zone.getMaxZ();
    }

    private static void stopHorizontalMotion(LivingEntity self) {
        Vector v = self.getVelocity();
        self.setVelocity(new Vector(0.0, v.getY(), 0.0));
    }

    private static double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private static double movementSpeed(LivingEntity self) {
        return self instanceof Ravager ? RAVAGER_PATH_SPEED : RAIDER_PATH_SPEED;
    }

    private Turret turretForTarget(Claim zone, LivingEntity target) {
        UUID id = target.getUniqueId();
        for (Turret t : zone.getTurrets()) {
            if (t.getNpcId() < 0) continue;
            NPC tn = CitizensAPI.getNPCRegistry().getById(t.getNpcId());
            if (tn != null && tn.getEntity() != null && tn.getEntity().getUniqueId().equals(id)) return t;
        }
        return null;
    }

    private Location turretApproachPoint(Claim zone, Turret turret, Location from, int clearance) {
        World world = from.getWorld();
        if (world == null) return null;
        Location best = null;
        double bestSq = Double.MAX_VALUE;
        int baseY = turret.getY() + 1;
        for (int ring = TurretStructure.FOOTPRINT_RADIUS + 1;
             ring <= TurretStructure.FOOTPRINT_RADIUS + 5; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int x = turret.getX() + dx;
                    int z = turret.getZ() + dz;
                    Location candidate = GroundPathPlanner.findStandableNear(
                            new NavGrid.LiveBlockSource(world), x, baseY, z, 8, 24, zone, clearance);
                    if (candidate == null) continue;
                    double dSq = candidate.distanceSquared(from);
                    if (dSq < bestSq) {
                        bestSq = dSq;
                        best = candidate;
                    }
                }
            }
            if (best != null) return best;
        }

        int radius = TurretStructure.FOOTPRINT_RADIUS;
        double minX = turret.getX() - radius - 0.5;
        double maxX = turret.getX() + radius + 1.5;
        double minZ = turret.getZ() - radius - 0.5;
        double maxZ = turret.getZ() + radius + 1.5;
        return new Location(world,
                clamp(from.getX(), minX, maxX),
                from.getY(),
                clamp(from.getZ(), minZ, maxZ));
    }

    /**
     * Distance to the turret's structure footprint expanded one block outward, ignoring Y.
     * Raiders are meant to damage a turret while standing on the ground around its base, even
     * if the shulker/HP label is many blocks above them.
     */
    private static double horizontalDistanceToTurretDamageBand(Location from, Turret turret) {
        double minX = turret.getX() - TurretStructure.FOOTPRINT_RADIUS - 1.0;
        double maxX = turret.getX() + TurretStructure.FOOTPRINT_RADIUS + 2.0;
        double minZ = turret.getZ() - TurretStructure.FOOTPRINT_RADIUS - 1.0;
        double maxZ = turret.getZ() + TurretStructure.FOOTPRINT_RADIUS + 2.0;

        double dx = 0.0;
        if (from.getX() < minX) dx = minX - from.getX();
        else if (from.getX() > maxX) dx = from.getX() - maxX;

        double dz = 0.0;
        if (from.getZ() < minZ) dz = minZ - from.getZ();
        else if (from.getZ() > maxZ) dz = from.getZ() - maxZ;

        return Math.sqrt(dx * dx + dz * dz);
    }

    private boolean canRaidMobHitTurret(LivingEntity self, LivingEntity turretEntity,
                                        Turret turret, double attackRange) {
        // Raider has to be standing INSIDE the +1 expanded damage band (i.e. directly adjacent
        // to the 5×5 structure wall, on the +1 cell). The 0.75-block tolerance we used to allow
        // here meant raiders stopped roughly 3/4 of a block short of the wall, which looked
        // wrong — they appeared to swing at empty air. Zero tolerance forces them to fully reach
        // the touch cell before they start attacking. The +1 expansion itself still acts as the
        // safety fallback for the rare worst-case where the pathfinder can't quite seat them.
        if (horizontalDistanceToTurretDamageBand(self.getLocation(), turret) > 0.0) return false;
        double heightDelta = Math.abs(self.getLocation().getY() - (turret.getY() + 1.0));
        if (heightDelta <= attackRange + 1.0) return true;
        return hasClearLineToTurret(self, turretEntity, turret);
    }

    private boolean hasClearLineToTurret(LivingEntity self, LivingEntity turretEntity, Turret turret) {
        Location from = self.getEyeLocation();
        Location to;
        try {
            to = turretEntity.getEyeLocation();
        } catch (Throwable ignored) {
            to = turretEntity.getLocation().clone().add(0.0, Math.max(0.5, turretEntity.getHeight() * 0.5), 0.0);
        }
        World world = from.getWorld();
        if (world == null || to.getWorld() != world) return false;
        Vector dir = to.toVector().subtract(from.toVector());
        double distance = dir.length();
        if (distance < 0.5) return true;
        dir.multiply(1.0 / distance);
        double step = TurretCombatTuning.REACHABILITY_STEP;
        int steps = (int) (distance / step);
        for (int i = 1; i < steps; i++) {
            Location pt = from.clone().add(dir.clone().multiply(i * step));
            if (isInsideThisTurretZone(pt, turret)) continue;
            if (pt.getBlock().getType().isOccluding()) return false;
        }
        return true;
    }

    private static boolean isInsideThisTurretZone(Location pt, Turret turret) {
        int bx = pt.getBlockX();
        int by = pt.getBlockY();
        int bz = pt.getBlockZ();
        return Math.abs(bx - turret.getX()) <= TurretStructure.FOOTPRINT_RADIUS
                && Math.abs(bz - turret.getZ()) <= TurretStructure.FOOTPRINT_RADIUS
                && by >= turret.getY()
                && by <= turret.getY() + TurretStructure.ZONE_MAX_DY;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class WalkState {
        private UUID targetId;
        private Location destination;
        private List<Location> waypoints;
        private int waypointIndex;
        private long replanAtTick;
        private boolean pathFailed;
        /** In-flight off-thread replan, if any. Result applied (main thread) once {@code isDone()}. */
        private Future<List<Location>> pendingPath;
        private Location lastCheckLocation;
        private long lastCheckTick;
        private int stuckChecks;
        private int sideSign = 1;
        private long lastJumpTick = Long.MIN_VALUE / 2;

        private WalkState(UUID targetId, Location destination,
                          Location lastCheckLocation, long lastCheckTick) {
            reset(targetId, destination, lastCheckLocation, lastCheckTick);
        }

        private void reset(UUID targetId, Location destination,
                           Location lastCheckLocation, long lastCheckTick) {
            this.targetId = targetId;
            this.destination = destination;
            this.waypoints = null;
            this.waypointIndex = 0;
            this.replanAtTick = 0L;
            this.pathFailed = false;
            this.pendingPath = null;     // abandon any in-flight replan for the previous target
            this.lastCheckLocation = lastCheckLocation;
            this.lastCheckTick = lastCheckTick;
            this.stuckChecks = 0;
        }
    }

    private static final class MoveChoice {
        private final Vector direction;
        private final boolean jump;
        private final int sideSign;

        private MoveChoice(Vector direction, boolean jump, int sideSign) {
            this.direction = direction;
            this.jump = jump;
            this.sideSign = sideSign;
        }
    }

    /**
     * Damage + animation in one call. We deliberately bypass {@link LivingEntity#attack(org.bukkit.entity.Entity)}
     * because for Citizens player NPCs holding a sword, the vanilla path adds the weapon's
     * attack-damage modifier on top of the attribute, doubling the damage we configured. Manual
     * damage + manual swing animation keeps the numbers exact.
     */
    private static void performAttack(LivingEntity self, LivingEntity target, double dmg) {
        try {
            target.damage(dmg, self);
        } catch (Throwable ignored) {
            // Defensive — if Paper rejects the damage (e.g. target became invulnerable this
            // tick from another source) we still want to play the animation so the player
            // sees the swing land.
        }
        playAttackAnimation(self);
    }

    private void performTurretAttack(LivingEntity self, Turret turret, double dmg) {
        if (plugin.getWitherCombatManager() != null) {
            plugin.getWitherCombatManager().applyRaidMobDamage(
                    turret, Math.max(1, (int) Math.round(dmg)), self);
        }
        playAttackAnimation(self);
    }

    private static void playAttackAnimation(LivingEntity self) {
        if (self instanceof Player p) p.swingMainHand();
        if (self instanceof Ravager r) {
            try { r.playEffect(EntityEffect.RAVAGER_ATTACK); } catch (Throwable ignored) {}
        }
    }

    /**
     * Closest non-friendly target the raider should pursue. Two buckets, ranked by Euclidean
     * distance with no internal priority:
     * <ul>
     *   <li>Real players in the zone who aren't friendly to the raid's attacker.</li>
     *   <li>Deployed turret NPCs in the zone.</li>
     * </ul>
     *
     * <p>Critically, Citizens player NPCs are filtered out of the player bucket. Raiders are
     * themselves Citizens player-entity NPCs, so without this filter every raider would see
     * every other raider (and itself) as a hostile target — symptom: {@code tgt=dev's Raider
     * dist=0.0 nav=false} in the AI logs, the raider just stands on its spawn point staring
     * at its own bounding box.
     *
     * <p><b>Tier A</b> (priority) — hostile players + alive turret NPCs. When Tier A is empty,
     * fall through to <b>Tier B</b> — villagers, animals, iron golems, anything that "could be
     * from the player". Tier B is filtered to skip hostile mobs (vanilla caves), Citizens NPCs
     * (turrets + other raiders), and the raider itself. If both tiers are empty the caller
     * (tickAI) drops into the centre-20% wander instead.
     */
    private LivingEntity findNearestTarget(ActiveRaid raid, Claim zone, LivingEntity self) {
        World world = self.getWorld();
        boolean citizensLoaded = Bukkit.getPluginManager().getPlugin("Citizens") != null;

        // -- Tier A: hostile players + alive turrets --------------------------------------------
        LivingEntity nearestA = null;
        double nearestASq = Double.MAX_VALUE;
        for (Player p : world.getPlayers()) {
            if (p.isDead() || !p.isValid()) continue;
            // Skip Citizens NPCs — raiders themselves appear in world.getPlayers().
            if (citizensLoaded && CitizensAPI.getNPCRegistry().isNPC(p)) continue;
            GameMode gm = p.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
            if (!zone.contains(p.getLocation())) continue;
            // Skip only raider-ALLIES (attacker-side players who aren't ALSO defending this base).
            // Shared predicate with RaiderDamageListener so targeting and friendly-fire stay in
            // lockstep: a defender who happens to be friended/allied to the attacker (e.g. the dev
            // base tester) is still a valid target — raiders won't ignore the person they're raiding.
            if (plugin.getRaidManager().isRaiderAlly(raid, p.getUniqueId())) continue;
            double dSq = p.getLocation().distanceSquared(self.getLocation());
            if (dSq < nearestASq) { nearestASq = dSq; nearestA = p; }
        }
        for (Turret t : zone.getTurrets()) {
            if (t.isDestroyed() || t.getNpcId() < 0) continue;
            NPC turretNpc = CitizensAPI.getNPCRegistry().getById(t.getNpcId());
            if (turretNpc == null || !turretNpc.isSpawned()) continue;
            // A turret NPC is always a Shulker. Requiring that here prevents a raider whose
            // Citizens id collided with a stored turret id (a PLAYER-type LivingEntity) from
            // being mistaken for a turret and added as a target — that is raiders "targeting
            // themselves". The slot's id is repaired separately by TurretEntityManager's disown
            // guard; here we just refuse to treat a non-Shulker as a turret.
            if (!(turretNpc.getEntity() instanceof org.bukkit.entity.Shulker te)) continue;
            if (te.isDead() || !te.isValid()) continue;
            double dSq = te.getLocation().distanceSquared(self.getLocation());
            if (dSq < nearestASq) { nearestASq = dSq; nearestA = te; }
        }
        if (nearestA != null) return nearestA;

        // -- Tier B: villagers / animals / iron golems / anything else "from the player" -------
        // Catches anything alive in the zone that isn't a hostile mob (caves) or another NPC
        // (turrets, raid mobs). Includes Villager, IronGolem, all Animal subtypes (wolf, cat,
        // cow, pig, ...). The world-wide getLivingEntities sweep is bounded because most server
        // worlds have a handful of these and the zone contains-check cuts it down further.
        LivingEntity nearestB = null;
        double nearestBSq = Double.MAX_VALUE;
        for (LivingEntity le : world.getLivingEntities()) {
            if (le == self) continue;
            if (le.isDead() || !le.isValid()) continue;
            if (le instanceof Player) continue;                          // Tier A handled this
            if (le instanceof org.bukkit.entity.Monster) continue;       // skip hostile mobs
            if (citizensLoaded && CitizensAPI.getNPCRegistry().isNPC(le)) continue;
            if (!zone.contains(le.getLocation())) continue;
            double dSq = le.getLocation().distanceSquared(self.getLocation());
            if (dSq < nearestBSq) { nearestBSq = dSq; nearestB = le; }
        }
        return nearestB;
    }

    /**
     * Resolve the location Citizens nav should aim at. For a turret-NPC target we return the
     * walkable surface at the turret's anchor (anchor + 1Y), because the shulker entity's own
     * coordinate sits inside the 5-block structure column and the pathfinder rejects solid
     * destinations as unreachable. For player targets we return their feet location.
     */
    private Location resolveNavTarget(Claim zone, LivingEntity target) {
        UUID targetId = target.getUniqueId();
        for (Turret t : zone.getTurrets()) {
            if (t.getNpcId() < 0) continue;
            NPC turretNpc = CitizensAPI.getNPCRegistry().getById(t.getNpcId());
            if (turretNpc == null || turretNpc.getEntity() == null) continue;
            if (!turretNpc.getEntity().getUniqueId().equals(targetId)) continue;
            return new Location(target.getWorld(),
                    t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5);
        }
        return target.getLocation();
    }

    // -- per-turret unreachability detection ----------------------------------

    /** Record which turret slot a mob is currently going for (feeds the per-turret siege aggregate). */
    private void updateGoal(UUID id, Turret goalTurret) {
        goalSlotByEntity.put(id, goalTurret == null ? -1 : goalTurret.getSlot());
    }

    public boolean isTurretUnreachable(UUID raidId, int slot) {
        Map<Integer, TurretSiege> sieges = siegesByRaid.get(raidId);
        if (sieges == null) return false;
        TurretSiege s = sieges.get(slot);
        return s != null && s.unreachable;
    }

    /**
     * Once per engine tick, per active raid: aggregate every live raid mob currently targeting each
     * turret and advance that turret's siege clock. The clock resets when ANY mob can reach the
     * turret (case 1) or the closest mob gets meaningfully nearer (case 2: progress); if neither
     * happens for {@link #EMERGENCY_SIEGE_TICKS} the turret is flagged unreachable for the rest of
     * the raid (case 3), which arms the per-mob fallback teleport in {@link #tickAI}.
     */
    private void updateTurretSieges(ActiveRaid raid, long currentTick) {
        Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
        if (zone == null) return;

        // slot -> can ANY besieging mob reach it OR find a viable GROUND path to it? A present key
        // means at least one mob is currently besieging that slot.
        Map<Integer, Boolean> reachable = new HashMap<>();
        for (CustomRaider r : new ArrayList<>(plugin.getRaidEntities().allRaiders())) {
            if (!raid.getRaidId().equals(r.getRaidId()) || !r.isAlive()) continue;
            foldSiege(r.getEntity(), CustomRaider.ATTACK_RANGE_BLOCKS, zone, reachable);
        }
        for (CustomRavager r : new ArrayList<>(plugin.getRaidEntities().allRavagers())) {
            if (!raid.getRaidId().equals(r.getRaidId()) || !r.isAlive()) continue;
            foldSiege(r.getEntity(), CustomRavager.ATTACK_RANGE_BLOCKS, zone, reachable);
        }

        Map<Integer, TurretSiege> sieges = siegesByRaid.computeIfAbsent(raid.getRaidId(), k -> new HashMap<>());
        for (Turret t : zone.getTurrets()) {
            if (t.isDestroyed() || t.getNpcId() < 0) continue;
            int slot = t.getSlot();
            TurretSiege s = sieges.computeIfAbsent(slot, k -> new TurretSiege(currentTick));
            if (s.unreachable) continue;                         // permanent for the raid

            long delta = Math.max(0L, currentTick - s.lastTick); // ~TICK_INTERVAL; robust to lag/skips
            s.lastTick = currentTick;

            Boolean canReach = reachable.get(slot);
            if (canReach == null) {
                // Nobody besieging it this tick → PAUSE: neither accumulate nor reset, so death
                // churn (raiders dying before a replacement re-acquires it) can't zero the clock.
            } else if (canReach) {
                // A besieger is in melee range OR has a viable ground path → reachable → reset.
                s.accumTicks = 0L;
            } else {
                // ≥1 besieger present, none can reach it → accumulate the unreachable time. Once it
                // totals the window it's genuinely walled / moated (the turret may still be shooting
                // them through a gap — that's the exploit we catch). Flag it; the per-mob fallback
                // teleports the besiegers onto the ring.
                s.accumTicks += delta;
                if (s.accumTicks >= EMERGENCY_SIEGE_TICKS) {
                    s.unreachable = true;
                    plugin.getLogger().info("Raid " + shortId(raid.getRaidId()) + ": turret #"
                            + (slot + 1) + " flagged UNREACHABLE — " + (EMERGENCY_SIEGE_TICKS / 20)
                            + "s cumulative with no ground path; besieging raiders will be teleported in.");
                }
            }
        }
    }

    /**
     * Fold one besieging mob into the per-turret reachability map. A turret counts as reachable if
     * any mob targeting it is in melee range OR has a viable GROUND path to it (its last A* to the
     * approach point succeeded). This deliberately keys off the RAIDERS' own ground pathfinding —
     * NOT the turret's projectile A* (which threads gaps a ground unit can't) and NOT whether the
     * turret is killing them (a 1-block gap or a lava moat kills besiegers while staying unreachable).
     */
    private void foldSiege(LivingEntity self, double attackRange, Claim zone, Map<Integer, Boolean> reachable) {
        if (self == null) return;
        Integer slot = goalSlotByEntity.get(self.getUniqueId());
        if (slot == null || slot < 0) return;
        Turret t = zone.getSlotTurret(slot);
        if (t == null || t.isDestroyed() || t.getNpcId() < 0) return;
        LivingEntity te = turretLivingEntity(t);
        if (te == null) return;
        boolean reached = canRaidMobHitTurret(self, te, t, attackRange);
        WalkState ws = walkStateByEntity.get(self.getUniqueId());
        boolean viablePath = ws != null && !ws.pathFailed;
        reachable.merge(slot, reached || viablePath, Boolean::logicalOr);
    }

    /** The Shulker living-entity for a turret, or null if its NPC isn't spawned. */
    private LivingEntity turretLivingEntity(Turret t) {
        NPC npc = CitizensAPI.getNPCRegistry().getById(t.getNpcId());
        if (npc == null || !npc.isSpawned()) return null;
        return npc.getEntity() instanceof org.bukkit.entity.Shulker s ? s : null;
    }

    /** Per-turret siege bookkeeping for one raid. */
    private static final class TurretSiege {
        long accumTicks;   // cumulative ticks spent "besieged but unreachable"
        long lastTick;     // last aggregation tick — used to add the real elapsed delta
        boolean unreachable;
        TurretSiege(long now) { this.lastTick = now; }
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }

    private static String fmtLoc(Location l) {
        return String.format("(%.1f,%.1f,%.1f)", l.getX(), l.getY(), l.getZ());
    }
}
