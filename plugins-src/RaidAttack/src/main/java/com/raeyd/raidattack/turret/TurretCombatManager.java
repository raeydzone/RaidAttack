package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.combat.WitherCombatManager;
import com.raeyd.raidattack.core.NmsReflection;
import com.raeyd.raidattack.pathfinding.PathPlanner;
import com.raeyd.raidattack.quest.Quest;
import com.raeyd.raidattack.raid.ActiveRaid;
import com.raeyd.raidattack.raid.CustomRaider;
import com.raeyd.raidattack.raid.CustomRavager;
import com.raeyd.raidattack.raid.RaidEntityManager;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.ShulkerBullet;
import org.bukkit.entity.Wither;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Drives turret aim+fire and the manual movement / hit logic for every projectile in flight.
 *
 * <h3>Targeting rules</h3>
 * <ul>
 *   <li><b>Active enemy gate.</b> A turret only fires at a player who has been inside its
 *       claim within the last 60 s (see {@link TurretEnemyTracker}). Random passers-by are
 *       ignored.</li>
 *   <li><b>Owner / friend immunity.</b> Bullets pass through members of the spawning claim
 *       and never damage them, even on direct collision.</li>
 *   <li><b>Out-of-zone follow.</b> Once a bullet leaves the spawning claim, it keeps chasing
 *       its current target if any, but cannot acquire new ones.</li>
 *   <li><b>Mob fallback.</b> If no eligible player is found, falls through to closest
 *       {@link Monster} iff the owning claim's {@link Claim#attacksHostileMobs()} is on.</li>
 * </ul>
 *
 * <h3>Hit detection</h3>
 * Each tick, after moving, we scan entities whose bounding box (expanded by
 * {@link TurretCombatTuning#HIT_RADIUS}) contains the bullet's centre. First non-member living
 * entity wins → explode on it. Handles tall mobs (skeletons, endermen) correctly.
 *
 * <h3>Projectile HP</h3>
 * Bullets are vulnerable. Sword / arrow damage is intercepted by {@link TurretBulletListener},
 * routed through {@link #applyDamageTo}, and decremented from the in-memory HP counter. At ≤ 0
 * the bullet silently despawns (no explosion, no damage).
 */
public final class TurretCombatManager {

    /** Bukkit metadata key marking bullets as "ours" so the listener knows to suppress vanilla. */
    public static final String BULLET_META = "homesystem-turret-level";
    /**
     * Player metadata key set transiently when a turret bullet damages them. Value format:
     * {@code "<slotNumber>|<claimOwnerUuid>"}. TurretKillListener falls back to this when the
     * regular damager-NPC path can't resolve a turret (notably the panic-volley case where the
     * source NPC despawns before the bullet lands).
     */
    public static final String TURRET_KILL_META = "homesystem-turret-kill";

    private static final long COMBAT_TICK_INTERVAL = 10L;
    private static final long PEEK_OPEN_TICKS = 12L;
    /** Reusable zero-velocity vector for bullets we want vanilla to leave stationary. */
    private static final Vector ZERO = new Vector(0, 0, 0);
    /** Flip to {@code false} to silence the diagnostic logs once combat is dialled-in. */
    public static final boolean DEBUG_LOG = false;
    /**
     * Per-tick verbose trace for every projectile. Spammy — generates ~20 lines per bullet per
     * second. Use to inspect why a specific bullet is choosing a particular direction, what its
     * waypoint state is, when it triggers a replan or collision, etc.
     * Default OFF — leaving this on was eating ~10-20% of one core in log I/O at 60 turrets.
     */
    public static final boolean DEBUG_TICK_LOG = false;

    private final HomeSystemPlugin plugin;
    /** turret NPC id → tick of last shot. Keyed by NPC id so remove+redeploy resets cooldown. */
    private final Map<Integer, Long> lastShotTickByNpc = new HashMap<>();
    /** All projectiles we currently steer. */
    private final Map<UUID, TurretProjectile> projectiles = new HashMap<>();
    /**
     * Reachability cache. target-entity UUID → tick after which it's worth re-checking. While
     * the current tick is below the stored value, we skip the candidate without raycasting.
     * Bounds per-tick work when many enemies are around but all unreachable (e.g. a cave of
     * mobs under the turret).
     */
    private final Map<UUID, Long> unreachableUntilTick = new HashMap<>();

    private BukkitTask combatTask;
    private BukkitTask projectileTask;

    public TurretCombatManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (combatTask == null) {
            combatTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::combatTick, COMBAT_TICK_INTERVAL, COMBAT_TICK_INTERVAL);
        }
        if (projectileTask == null) {
            projectileTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, this::projectileTick, 1L, 1L);
        }
    }

    public void stop() {
        if (combatTask != null) { combatTask.cancel(); combatTask = null; }
        if (projectileTask != null) { projectileTask.cancel(); projectileTask = null; }
        for (UUID id : projectiles.keySet()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        projectiles.clear();
        lastShotTickByNpc.clear();
        unreachableUntilTick.clear();
    }

    /** Called by the bullet listener when a projectile we own is gone (vanilla hit etc.). */
    public void forgetProjectile(UUID id) {
        projectiles.remove(id);
    }

    /** Lookup for the remove-listener. */
    public TurretProjectile getProjectile(UUID id) {
        return projectiles.get(id);
    }

    /**
     * Vanilla just despawned our bullet (its internal {@code onHit} always calls {@code discard()}
     * on block raycast hit, regardless of whether {@link
     * org.bukkit.event.entity.ProjectileHitEvent} was cancelled). Spawn a fresh bullet at the
     * same location, transfer all projectile state across to the new entity-id, continue as if
     * nothing happened. Capped at {@link TurretCombatTuning#MAX_RESPAWNS} per projectile so a
     * truly unrecoverable position eventually gives up.
     */
    public void respawnAt(TurretProjectile oldProj, Location at) {
        if (!projectiles.containsKey(oldProj.entityId)) return;
        if (oldProj.markedForCleanup) return;
        if (oldProj.respawnCount >= TurretCombatTuning.MAX_RESPAWNS) {
            if (DEBUG_LOG) plugin.getLogger().info(String.format(
                    "[bullet %s] RESPAWN-CAP reached (%d times) — giving up at %s",
                    shortId(oldProj.entityId), oldProj.respawnCount, fmtLoc(at)));
            projectiles.remove(oldProj.entityId);
            return;
        }
        World world = at.getWorld();
        if (world == null) return;

        LivingEntity tgt = oldProj.targetId != null
                && Bukkit.getEntity(oldProj.targetId) instanceof LivingEntity le ? le : null;

        ShulkerBullet newBullet = world.spawn(at, ShulkerBullet.class, b -> {
            b.setMetadata(BULLET_META, new FixedMetadataValue(plugin, oldProj.level));
            b.setTarget(tgt);
            b.setSilent(true);
            b.setVisibleByDefault(false);
        });
        NmsReflection.setNoPhysics(newBullet, true);
        newBullet.setVelocity(ZERO);

        // Transfer state: same projectile, new entity id.
        TurretProjectile newProj = new TurretProjectile(
                newBullet.getUniqueId(), oldProj.level, oldProj.spawnTick,
                oldProj.spawningClaimOwner, oldProj.firingSlot);
        newProj.targetId = oldProj.targetId;
        newProj.lastDirection = oldProj.lastDirection;
        newProj.lastRetargetTick = oldProj.lastRetargetTick;
        newProj.path = oldProj.path;
        newProj.pathIndex = oldProj.pathIndex;
        newProj.lastReplanTick = oldProj.lastReplanTick;
        newProj.pathRetryAfterTick = oldProj.pathRetryAfterTick;
        newProj.bestProgressDistSq = oldProj.bestProgressDistSq;
        newProj.bestProgressTick = oldProj.bestProgressTick;
        newProj.forcedExitDirection = oldProj.forcedExitDirection;
        newProj.forcedExitBlocksRemaining = oldProj.forcedExitBlocksRemaining;
        newProj.hp = oldProj.hp;
        newProj.respawnCount = oldProj.respawnCount + 1;

        projectiles.remove(oldProj.entityId);
        projectiles.put(newBullet.getUniqueId(), newProj);

        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                "[bullet %s] RESPAWN #%d @ %s (vanilla killed previous %s)",
                shortId(newBullet.getUniqueId()), newProj.respawnCount,
                fmtLoc(at), shortId(oldProj.entityId)));
    }

    /**
     * Called by {@link TurretBulletListener} when an external damage source hits one of our
     * bullets. Returns true if the bullet survived, false if HP dropped to ≤ 0 and it was
     * removed.
     */
    public boolean applyDamageTo(UUID bulletId, double dmg) {
        TurretProjectile proj = projectiles.get(bulletId);
        if (proj == null) return false;
        proj.hp -= (int) Math.ceil(dmg);
        Entity e = Bukkit.getEntity(bulletId);
        if (proj.hp <= 0) {
            if (DEBUG_LOG) plugin.getLogger().info(String.format(
                    "[bullet %s] HP-DEPLETED (took external damage, dies silently)",
                    shortId(bulletId)));
            proj.markedForCleanup = true;
            if (e != null) {
                Location at = e.getLocation();
                World w = at.getWorld();
                if (w != null) {
                    w.spawnParticle(Particle.SMOKE, at, 12, 0.1, 0.1, 0.1, 0.02);
                    w.playSound(at, Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1.0f, 1.5f);
                }
                e.remove();
            }
            projectiles.remove(bulletId);
            return false;
        }
        if (e != null) {
            // Hit feedback so the attacker can see the bullet's flinching.
            World w = e.getWorld();
            w.spawnParticle(Particle.CRIT, e.getLocation(), 6, 0.1, 0.1, 0.1, 0.1);
            w.playSound(e.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.4f, 1.6f);
        }
        return true;
    }

    // =====================================================================
    // Turret tick — pick targets, fire.
    // =====================================================================

    private void combatTick() {
        long now = Bukkit.getCurrentTick();
        pruneReachabilityCache();
        for (Claim claim : plugin.getClaimManager().all().values()) {
            World world = Bukkit.getWorld(claim.getWorldId());
            if (world == null) continue;
            for (Turret turret : claim.getTurrets()) {
                if (turret.getNpcId() < 0) continue;
                if (turret.isDestroyed()) continue;            // 2-minute downtime — no firing
                if (plugin.getTurretQueue().isPending(turret)) continue;
                if (!world.isChunkLoaded(turret.getX() >> 4, turret.getZ() >> 4)) continue;

                int level = claim.getSlotLevel(turret.getSlot());
                long intervalTicks = TurretCombatTuning.attackIntervalTicks(level);
                long last = lastShotTickByNpc.getOrDefault(turret.getNpcId(), -intervalTicks);
                if (now - last < intervalTicks) continue;

                NPC npc = CitizensAPI.getNPCRegistry().getById(turret.getNpcId());
                if (npc == null || !npc.isSpawned()) {
                    if (DEBUG_LOG) plugin.getLogger().info(String.format(
                            "[turret #%d L%d] NPC missing (id=%d, %s) — skipping",
                            turret.getSlot() + 1, level, turret.getNpcId(),
                            npc == null ? "no registry entry" : "not spawned"));
                    continue;
                }
                Location muzzle = npc.getEntity().getLocation().clone().add(0, 0.5, 0);

                // Get every eligible enemy in range, sorted nearest-first. We then walk that list
                // and A*-test each one — first reachable hit wins. This is what gives the user
                // the "GUARANTEED 35-block range" property: a buried creeper that happens to be
                // closest can no longer cause us to silently skip the shot while a clearly-shootable
                // player or zombie stands 30 blocks away in open ground. Capped at MAX_PATH_TRIES
                // candidates so worst-case A* spend per turret-shot is bounded.
                //
                // preferPlayer is rolled fresh PER SHOT here so the 25% wither/siege-override is a
                // true independent per-projectile probability — not a stateful timer. On a hit it
                // promotes hostile players to the front of the candidate list (see findEligibleTargets);
                // on the 75% it leaves siege first. Rolling at fire-time (rather than only at bullet
                // retarget) is the fix for "feels like an on/off timer instead of every shot 25%".
                boolean preferPlayer = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                        < WITHER_OVERRIDE_PLAYER_CHANCE;
                List<LivingEntity> candidates = findEligibleTargets(claim, muzzle, TurretCombatTuning.RANGE, preferPlayer);
                if (candidates.isEmpty()) {
                    // Diagnostic: did the turret pass its other gates (cooldown, NPC alive, etc.)
                    // but find nothing to shoot? Count nearby Monster entities and any players so
                    // the user can tell whether the issue is "nothing in range" vs "in range but
                    // filtered out" (e.g. claim membership, mob-attack toggle off, gamemode immune).
                    if (DEBUG_LOG) {
                        int mobs = 0, players = 0;
                        double r = TurretCombatTuning.RANGE;
                        double rSq = r * r;
                        for (Entity e : world.getNearbyEntities(muzzle, r, r, r)) {
                            if (e.getLocation().distanceSquared(muzzle) > rSq) continue;
                            if (e instanceof Monster) mobs++;
                            else if (e instanceof Player p && !isImmuneGameMode(p)) players++;
                        }
                        if (mobs > 0 || players > 0) {
                            plugin.getLogger().info(String.format(
                                    "[turret #%d L%d] 0 eligible candidates but %d mob(s) + %d player(s) within %.0fb"
                                    + " (attackMobs=%s, claim=%s)",
                                    turret.getSlot() + 1, level, mobs, players, r,
                                    claim.attacksHostileMobs(), claim.getOwner().toString().substring(0, 8)));
                            // Per-entity breakdown so a still-filtered raider tells us exactly why.
                            for (Entity e : world.getNearbyEntities(muzzle, r, r, r)) {
                                if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
                                if (le.getLocation().distanceSquared(muzzle) > rSq) continue;
                                boolean raidNpc = isRaidNpc(le);
                                if (!raidNpc && !(e instanceof Player)) continue;
                                UUID rid = raidNpcIdFor(le);
                                ActiveRaid rd = rid == null ? null : plugin.getRaidManager().getRaid(rid);
                                boolean friendly = rd != null && plugin.getRaidManager()
                                        .isFriendlyToAttacker(rd.getAttackerId(), claim.getOwner());
                                plugin.getLogger().info(String.format(
                                        "    └ %s: raidNpc=%s raidId=%s raidFound=%s friendlyToOwner=%s reachable=%s",
                                        le instanceof Player pl ? pl.getName() : le.getType().name(),
                                        raidNpc, rid == null ? "null" : rid.toString().substring(0, 8),
                                        rd != null, friendly, isReachable(muzzle, le)));
                            }
                        }
                    }
                    continue;
                }

                LivingEntity target = null;
                Vector cardinal = null;
                List<Vector> path = null;
                int tries = 0;
                for (LivingEntity candidate : candidates) {
                    if (tries++ >= TurretCombatTuning.MAX_PATH_TRIES) break;
                    Vector card = pickCardinalToward(muzzle, candidate.getLocation());
                    Location postExitOrigin = muzzle.clone().add(
                            card.clone().multiply(TurretCombatTuning.FORCED_EXIT_BLOCKS));
                    // Try two goal anchor points on the target. The 4-goal sweep we tried earlier
                    // was a TPS killer — 4 × MAX_NODES of wasted work per failed candidate. With
                    // the cheap raycast pre-filter doing reachability culling upstream, we only
                    // need a small backup here: eye (canonical) + above-head (catches the rare
                    // "tall mob's head clips ceiling" case where eye lands in a solid block).
                    Location feet = candidate.getLocation();
                    double h = candidate.getHeight();
                    Location[] goals = {
                            candidate.getEyeLocation(),
                            feet.clone().add(0, h + 1.0, 0),
                    };
                    String[] goalLabels = {"eye", "above"};
                    List<Vector> p = null;
                    String pickedGoal = null;
                    for (int gi = 0; gi < goals.length; gi++) {
                        List<Vector> cand = PathPlanner.findPath(plugin, postExitOrigin, goals[gi]);
                        // A* is the sole authority on whether a shot is takeable. The planner only
                        // routes through genuinely passable cells (Block#isPassable), so a non-empty
                        // result IS a flyable road — no detour/backtrack/corner heuristics needed to
                        // second-guess it. No path → no shot.
                        if (cand != null && !cand.isEmpty()) {
                            p = cand; pickedGoal = goalLabels[gi]; break;
                        }
                    }
                    if (p != null && !p.isEmpty()) {
                        target = candidate; cardinal = card; path = p;
                        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                                "[turret #%d L%d] reached %s @ %s via %s (path=%d wp)",
                                turret.getSlot() + 1, level,
                                candidate instanceof Player p3 ? p3.getName() : candidate.getType().name(),
                                fmtLoc(candidate.getLocation()), pickedGoal, p.size()));
                        break;
                    }
                    // No followable path to this candidate — cache it as unreachable so the cheap
                    // pre-filter skips it next poll instead of re-running A* every combat tick.
                    unreachableUntilTick.put(candidate.getUniqueId(),
                            now + TurretCombatTuning.UNREACHABLE_CACHE_TICKS);
                    if (DEBUG_LOG) plugin.getLogger().info(String.format(
                            "[turret #%d L%d] candidate %s @ %s UNREACHABLE (no direct path) — trying next",
                            turret.getSlot() + 1, level,
                            candidate instanceof Player p2 ? p2.getName() : candidate.getType().name(),
                            fmtLoc(candidate.getLocation())));
                }
                if (target == null) {
                    if (DEBUG_LOG) plugin.getLogger().info(String.format(
                            "[turret #%d L%d] no reachable target among %d candidate(s) — skipping shot",
                            turret.getSlot() + 1, level, Math.min(candidates.size(), TurretCombatTuning.MAX_PATH_TRIES)));
                    continue;
                }

                fire(world, muzzle, npc, target, level, claim.getOwner(), turret.getSlot(),
                        cardinal, path);
                lastShotTickByNpc.put(turret.getNpcId(), now);
            }
        }
    }

    private void fire(World world, Location muzzle, NPC npc, LivingEntity target,
                      int level, UUID claimOwner, int firingSlot,
                      Vector cardinal, List<Vector> precomputedPath) {
        if (npc.getEntity() instanceof Shulker shulker) {
            shulker.setPeek(1.0f);
            Bukkit.getScheduler().runTaskLater(
                    plugin, () -> { if (shulker.isValid()) shulker.setPeek(0.0f); }, PEEK_OPEN_TICKS);
        }
        ShulkerBullet bullet = world.spawn(muzzle, ShulkerBullet.class, b -> {
            b.setMetadata(BULLET_META, new FixedMetadataValue(plugin, level));
            b.setTarget(target);
            b.setSilent(true);
            // Hide the actual ShulkerBullet entity from all clients. We render our own
            // particle trail each tick. This kills the vanilla SHULKER_BULLET_HIT visual
            // that fires on every false-positive vanilla discard — clients never see the
            // entity at all, so the entity-event packet has nothing to render against.
            b.setVisibleByDefault(false);
        });
        NmsReflection.setNoPhysics(bullet, true);
        TurretProjectile proj = new TurretProjectile(
                bullet.getUniqueId(), level, Bukkit.getCurrentTick(), claimOwner, firingSlot);
        proj.targetId = target.getUniqueId();
        proj.lastRetargetTick = Bukkit.getCurrentTick();

        // Cardinal + path were computed and verified in combatTick before firing.
        proj.forcedExitDirection = cardinal;
        proj.forcedExitBlocksRemaining = TurretCombatTuning.FORCED_EXIT_BLOCKS;
        proj.lastDirection = cardinal.clone().multiply(TurretCombatTuning.BLOCKS_PER_TICK);
        proj.path = precomputedPath;
        proj.pathIndex = 0;
        proj.lastReplanTick = Bukkit.getCurrentTick();

        projectiles.put(bullet.getUniqueId(), proj);
        world.playSound(muzzle, Sound.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f);

        if (DEBUG_LOG) {
            String tName = (target instanceof Player p) ? p.getName() : target.getType().name();
            double dist = muzzle.distance(target.getLocation());
            int waypoints = (proj.path == null) ? -1 : proj.path.size();
            plugin.getLogger().info(String.format(
                    "[bullet %s] FIRE L%d → %s @ %.1fb, path=%d wp, cardinal=%s",
                    shortId(bullet.getUniqueId()), level, tName, dist, waypoints,
                    cardinalName(cardinal)));
        }
    }

    /**
     * Final-burst panic volley fired the moment a turret is destroyed: four ShulkerBullets, one
     * per cardinal direction, launched simultaneously from the dying turret's muzzle. Each bullet
     * has no preset target — once it clears the forced-exit phase it enters normal free-mode
     * homing and acquires the nearest eligible target via the regular retarget tick. So the
     * volley reads as "the turret takes one last shot in every direction before going down" and
     * actually lands hits if anything is in range.
     *
     * <p>Called from {@link WitherCombatManager#destroy} BEFORE the NPC despawn so the muzzle
     * location is still valid. Idempotent on a missing / unspawned NPC (no-op).
     */
    public void firePanicVolley(World world, Turret turret, int level, UUID claimOwner) {
        if (world == null || turret == null) return;
        // Compute the muzzle from the turret anchor — stable even if the NPC entity has already
        // been despawned by a racing cleanup. Layer-5 centre + 0.5Y matches what the regular
        // combatTick uses for a healthy turret.
        Location muzzle = new Location(world,
                turret.getX() + 0.5, turret.getY() + 4.5, turret.getZ() + 0.5);

        Vector[] cardinals = {
                new Vector(1, 0, 0),
                new Vector(-1, 0, 0),
                new Vector(0, 0, 1),
                new Vector(0, 0, -1),
        };
        for (Vector cardinal : cardinals) {
            spawnPanicBullet(world, muzzle, cardinal, level, claimOwner, turret.getSlot());
        }
        // Sharper, lower-pitched shoot sound so the final burst is audibly distinct from a
        // regular salvo — sells the "last gasp" feel.
        world.playSound(muzzle, Sound.ENTITY_SHULKER_SHOOT, 1.2f, 0.7f);
        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                "[turret #%d L%d] PANIC-VOLLEY fired (4 bullets, cardinals)",
                turret.getSlot() + 1, level));
    }

    /** One panic-volley bullet. Same wiring as {@link #fire} but target=null. */
    private void spawnPanicBullet(World world, Location muzzle, Vector cardinal, int level,
                                  UUID claimOwner, int firingSlot) {
        ShulkerBullet bullet = world.spawn(muzzle, ShulkerBullet.class, b -> {
            b.setMetadata(BULLET_META, new FixedMetadataValue(plugin, level));
            b.setSilent(true);
            b.setVisibleByDefault(false);
        });
        NmsReflection.setNoPhysics(bullet, true);
        TurretProjectile proj = new TurretProjectile(
                bullet.getUniqueId(), level, Bukkit.getCurrentTick(), claimOwner, firingSlot);
        // No initial target — once forced-exit ends, the retarget tick (if inside the spawning
        // claim) acquires whatever's nearest, and the bullet homes in normally from there.
        proj.targetId = null;
        proj.lastRetargetTick = 0;     // force an immediate retarget after forced-exit
        proj.forcedExitDirection = cardinal.clone();
        proj.forcedExitBlocksRemaining = TurretCombatTuning.FORCED_EXIT_BLOCKS;
        proj.lastDirection = cardinal.clone().multiply(TurretCombatTuning.BLOCKS_PER_TICK);
        proj.path = null;
        proj.lastReplanTick = 0;
        projectiles.put(bullet.getUniqueId(), proj);
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
    private static String cardinalName(Vector v) {
        if (v.getX() > 0.5) return "+X"; if (v.getX() < -0.5) return "-X";
        if (v.getZ() > 0.5) return "+Z"; if (v.getZ() < -0.5) return "-Z";
        return "?";
    }
    private static String fmtLoc(Location l) {
        return String.format("(%.1f,%.1f,%.1f)", l.getX(), l.getY(), l.getZ());
    }

    /** Closest unit vector among {+X, -X, +Z, -Z} to the horizontal bearing from from→to. */
    private static Vector pickCardinalToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        // Degenerate (target directly above / below): default to +X arbitrarily.
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) return new Vector(1, 0, 0);
        if (Math.abs(dx) >= Math.abs(dz)) return new Vector(Math.signum(dx), 0, 0);
        return new Vector(0, 0, Math.signum(dz));
    }

    // =====================================================================
    // Projectile tick — move, retarget, collide.
    // =====================================================================

    private void projectileTick() {
        if (projectiles.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, TurretProjectile>> it = projectiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TurretProjectile> entry = it.next();
            TurretProjectile proj = entry.getValue();
            Entity entity = Bukkit.getEntity(proj.entityId);
            if (!(entity instanceof ShulkerBullet bullet) || !bullet.isValid()) {
                if (DEBUG_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s] VANISHED (entity invalid/null) after %d ticks — vanilla likely killed it",
                        shortId(proj.entityId), now - proj.spawnTick));
                it.remove();
                continue;
            }
            if (now - proj.spawnTick >= TurretCombatTuning.LIFETIME_TICKS) {
                if (DEBUG_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s] EXPIRE after 15s at %s",
                        shortId(proj.entityId), fmtLoc(bullet.getLocation())));
                proj.markedForCleanup = true;
                bullet.remove();
                it.remove();
                continue;
            }

            Location loc = bullet.getLocation();
            World world = loc.getWorld();
            if (world == null) {
                proj.markedForCleanup = true;
                bullet.remove();
                it.remove();
                continue;
            }

            // Proximity-hit check FIRST. Two-mode: while we have a tracked target, ONLY that
            // target counts as a hit — bullets phase straight through every other entity in
            // their path. AOE on detonation still damages anyone in the splash zone, so an
            // adjacent zombie at the impact point still gets hit, just doesn't preempt the
            // intended shot. When the target is lost, we fall back to "hit any non-member".
            LivingEntity hit = findProximityHit(loc, proj.spawningClaimOwner, proj.targetId);
            if (hit != null) {
                if (DEBUG_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s] PROXIMITY-HIT %s at %s",
                        shortId(proj.entityId),
                        hit instanceof Player p ? p.getName() : hit.getType().name(),
                        fmtLoc(loc)));
                explodeOn(bullet, proj, hit);
                it.remove();
                continue;
            }

            boolean inGrace = (now - proj.spawnTick) < TurretCombatTuning.GRACE_PERIOD_TICKS;

            // Forced-exit phase: locked cardinal direction, no pathfind, no retarget. Stop
            // EARLY if the next step would land in a non-turret-zone solid block — otherwise
            // we'd teleport the bullet inside a tree / wall and explode on the first
            // normal-pathfind tick. Let avoidance kick in while we're still in clear air.
            if (proj.forcedExitBlocksRemaining > 0) {
                proj.lastDirection = proj.forcedExitDirection.clone()
                        .multiply(TurretCombatTuning.BLOCKS_PER_TICK);
                Location next = loc.clone().add(proj.lastDirection);
                boolean wouldEnterSolid = isSolid(next)
                        && !plugin.isInTurretZone(next);
                if (wouldEnterSolid) {
                    if (DEBUG_TICK_LOG) plugin.getLogger().info(String.format(
                            "[bullet %s t%d] FORCED-EXIT-ABORT @ %s (next %s is solid, hand to pathfind)",
                            shortId(proj.entityId), now - proj.spawnTick, fmtLoc(loc), fmtLoc(next)));
                    proj.forcedExitBlocksRemaining = 0;
                    proj.lastRetargetTick = 0;
                    proj.path = null;
                    proj.lastReplanTick = 0;
                } else {
                    proj.forcedExitBlocksRemaining -= TurretCombatTuning.BLOCKS_PER_TICK;
                    if (proj.forcedExitBlocksRemaining <= 0) {
                        proj.lastRetargetTick = 0;
                        proj.path = null;
                        proj.lastReplanTick = 0;
                    }
                    if (DEBUG_TICK_LOG) plugin.getLogger().info(String.format(
                            "[bullet %s t%d] FORCED-EXIT @ %s → %s (remaining=%.2fb)",
                            shortId(proj.entityId), now - proj.spawnTick,
                            fmtLoc(loc), fmtLoc(next), proj.forcedExitBlocksRemaining));
                    world.spawnParticle(Particle.END_ROD, loc, 4, 0.05, 0.05, 0.05, 0.0);
                    bullet.teleport(next);
                    bullet.setVelocity(ZERO);
                    continue;
                }
            }

            // Emergency: if we're somehow inside a solid block already (forced exit ended
            // in-block, vanilla collision-bounce pushed us in, etc.) and we're not in grace
            // or a turret zone, teleport straight up to the nearest clear cell rather than
            // exploding pointlessly. Capped at 6 blocks of escape.
            if (isSolid(loc) && !plugin.isInTurretZone(loc)) {
                Location escape = null;
                for (int up = 1; up <= 6; up++) {
                    Location probe = loc.clone().add(0, up, 0);
                    if (!isSolid(probe) || plugin.isInTurretZone(probe)) {
                        escape = probe;
                        break;
                    }
                }
                if (escape != null) {
                    if (DEBUG_LOG) plugin.getLogger().info(String.format(
                            "[bullet %s] ESCAPE %s → %s (stuck in solid)",
                            shortId(proj.entityId), fmtLoc(loc), fmtLoc(escape)));
                    bullet.teleport(escape);
                    bullet.setVelocity(ZERO);
                    continue;
                }
                if (DEBUG_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s] EXPLODE-BURIED at %s (no escape up to 6b)",
                        shortId(proj.entityId), fmtLoc(loc)));
                explodeOn(bullet, proj, null);
                it.remove();
                continue;
            }

            // Retarget rules:
            //   - Bullet inside the spawning claim → can acquire a new target.
            //   - Bullet outside  → keeps existing target if alive; otherwise coasts.
            Claim currentClaim = plugin.getClaimManager().getClaimAt(loc);
            Claim spawnClaim = plugin.getClaimManager().getClaimOf(proj.spawningClaimOwner);
            boolean insideSpawning = currentClaim != null && spawnClaim != null
                    && currentClaim.getOwner().equals(spawnClaim.getOwner());

            LivingEntity target = resolveTarget(proj);
            if (insideSpawning && now - proj.lastRetargetTick >= TurretCombatTuning.RETARGET_INTERVAL) {
                LivingEntity better = findEligibleTarget(spawnClaim, loc, TurretCombatTuning.BULLET_HOMING_RANGE);
                if (better != null) {
                    if (target == null || !better.getUniqueId().equals(proj.targetId)) {
                        proj.path = null;
                        // New target → reset progress baseline so the no-progress watchdog
                        // measures against the new target, not the old one.
                        proj.bestProgressDistSq = Double.MAX_VALUE;
                        proj.bestProgressTick = 0;
                        proj.pathRetryAfterTick = 0;
                    }
                    target = better;
                    proj.targetId = better.getUniqueId();
                } else if (target == null) {
                    proj.targetId = null;
                }
                proj.lastRetargetTick = now;
            }

            if (target != null
                    && now >= proj.pathRetryAfterTick
                    && (proj.path == null
                        || now - proj.lastReplanTick >= TurretCombatTuning.REPLAN_INTERVAL_TICKS)) {
                List<Vector> newPath = PathPlanner.findPath(plugin, loc, target.getEyeLocation());
                int sz = newPath == null ? 0 : newPath.size();
                if (newPath != null && !newPath.isEmpty()) {
                    proj.path = newPath;
                    proj.pathIndex = 0;
                    proj.pathRetryAfterTick = 0;     // success — clear the failure backoff
                } else {
                    // FAILED: target unreachable from here. Back off so we don't re-run a full
                    // A* every tick (the spinning-bullet TPS sink). Bullet coasts meanwhile.
                    proj.path = null;
                    proj.pathRetryAfterTick = now + TurretCombatTuning.PATH_FAIL_BACKOFF_TICKS;
                }
                proj.lastReplanTick = now;
                if (DEBUG_TICK_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s t%d] REPLAN-SCHEDULED @ %s → target %s, new path=%d wp",
                        shortId(proj.entityId), now - proj.spawnTick, fmtLoc(loc),
                        fmtLoc(target.getEyeLocation()), sz));
            }

            // Heading.
            Vector aimAt = null;
            boolean onPath = proj.path != null && !proj.path.isEmpty();

            // No-progress watchdog — the spinning/stuck-bullet killer. Track the closest the
            // bullet has ever been to its target. Out of grace, if it hasn't improved on that
            // best distance for NO_PROGRESS_TIMEOUT_TICKS it's stuck — orbiting an unreachable
            // point, or piled up at a dead end after exhausting a path it couldn't complete — so
            // expire it. (No onPath guard: a fast projectile that hasn't closed ANY distance on
            // its target in 3 s is stuck whether or not it nominally still holds a path.)
            if (target != null) {
                double dsq = loc.toVector().distanceSquared(target.getEyeLocation().toVector());
                if (dsq + TurretCombatTuning.NO_PROGRESS_EPSILON_SQ < proj.bestProgressDistSq) {
                    proj.bestProgressDistSq = dsq;
                    proj.bestProgressTick = now;
                } else if (!inGrace && proj.bestProgressTick != 0
                        && now - proj.bestProgressTick >= TurretCombatTuning.NO_PROGRESS_TIMEOUT_TICKS) {
                    if (DEBUG_LOG) plugin.getLogger().info(String.format(
                            "[bullet %s] NO-PROGRESS expire — orbiting %d ticks without closing on target",
                            shortId(proj.entityId), now - proj.bestProgressTick));
                    proj.markedForCleanup = true;
                    bullet.remove();
                    it.remove();
                    continue;
                }
            }

            int wpBefore = proj.pathIndex;
            if (onPath) {
                while (proj.pathIndex < proj.path.size()
                        && proj.path.get(proj.pathIndex).distance(loc.toVector())
                            < TurretCombatTuning.WAYPOINT_REACHED_DISTANCE) {
                    proj.pathIndex++;
                }
                if (proj.pathIndex < proj.path.size()) {
                    aimAt = proj.path.get(proj.pathIndex);
                } else if (target != null && hasClearShot(loc, target.getEyeLocation())) {
                    // End of the planned road — only steer straight at the target if a genuine
                    // clear line exists. Never home through solid blocks.
                    aimAt = target.getEyeLocation().toVector();
                }
            } else if (target != null && hasClearShot(loc, target.getEyeLocation())) {
                // No A* road to the target: aim directly ONLY with clear line of sight. Otherwise
                // leave the heading unchanged (coast) so the bullet can't barrel through a wall —
                // the no-progress watchdog reaps it shortly if it never closes on the target.
                aimAt = target.getEyeLocation().toVector();
            }
            if (aimAt != null) {
                Vector dir = aimAt.clone().subtract(loc.toVector());
                if (dir.lengthSquared() > 1e-6) {
                    proj.lastDirection = dir.normalize().multiply(TurretCombatTuning.BLOCKS_PER_TICK);
                }
            }
            if (proj.lastDirection == null) {
                if (DEBUG_TICK_LOG) plugin.getLogger().info(String.format(
                        "[bullet %s t%d] NO-DIRECTION (no target, no path, coasting impossible)",
                        shortId(proj.entityId), now - proj.spawnTick));
                continue;
            }

            // Reactive avoidance only when freelancing (no plan).
            boolean avoidanceFired = false;
            if (!onPath) {
                Vector dirNormal = proj.lastDirection.clone().normalize();
                Location lookAhead = loc.clone().add(
                        dirNormal.clone().multiply(TurretCombatTuning.AVOIDANCE_LOOKAHEAD));
                if (blocksMovement(lookAhead, inGrace)) {
                    Vector avoidance = computeAvoidanceDirection(loc, dirNormal, inGrace);
                    if (avoidance != null) {
                        double fwd = TurretCombatTuning.AVOIDANCE_FORWARD_WEIGHT;
                        Vector blended = dirNormal.clone().multiply(fwd)
                                .add(avoidance.clone().multiply(1.0 - fwd));
                        if (blended.lengthSquared() > 1e-6) {
                            proj.lastDirection = blended.normalize()
                                    .multiply(TurretCombatTuning.BLOCKS_PER_TICK);
                            avoidanceFired = true;
                        }
                    }
                }
            }

            Location candidate = loc.clone().add(proj.lastDirection);
            boolean candidateBlocked = blocksMovement(candidate, inGrace);
            boolean replannedThisTick = false;
            boolean nudgedUp = false;
            if (candidateBlocked) {
                if (target != null
                        && now >= proj.pathRetryAfterTick
                        && now - proj.lastReplanTick > TurretCombatTuning.REPLAN_INTERVAL_TICKS / 2) {
                    List<Vector> retry = PathPlanner.findPath(plugin, loc, target.getEyeLocation());
                    proj.lastReplanTick = now;     // stamp on ATTEMPT so a failure can't replan every tick
                    if (retry != null && !retry.isEmpty()) {
                        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                                "[bullet %s t%d] REPLAN-COLLISION @ %s → %d wp",
                                shortId(proj.entityId), now - proj.spawnTick,
                                fmtLoc(loc), retry.size()));
                        proj.path = retry;
                        proj.pathIndex = 0;
                        proj.pathRetryAfterTick = 0;
                        replannedThisTick = true;
                    } else {
                        // Unreachable from here — back off (coast straight) instead of hammering A*.
                        proj.pathRetryAfterTick = now + TurretCombatTuning.PATH_FAIL_BACKOFF_TICKS;
                        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                                "[bullet %s t%d] REPLAN-FAIL @ %s (no path to %s)",
                                shortId(proj.entityId), now - proj.spawnTick,
                                fmtLoc(loc), fmtLoc(target.getEyeLocation())));
                    }
                }
                if (replannedThisTick) continue;
                Location up = candidate.clone().add(0, 1, 0);
                if (!blocksMovement(up, inGrace)) {
                    candidate = up;
                    nudgedUp = true;
                }
            }

            if (DEBUG_TICK_LOG) {
                String wpInfo;
                if (onPath) {
                    wpInfo = String.format("wp=%d/%d", proj.pathIndex, proj.path.size());
                    if (proj.pathIndex < proj.path.size()) {
                        Vector w = proj.path.get(proj.pathIndex);
                        wpInfo += String.format("@(%.1f,%.1f,%.1f)", w.getX(), w.getY(), w.getZ());
                    }
                } else {
                    wpInfo = "wp=NONE(direct)";
                }
                String tName = target == null ? "NO-TARGET"
                        : (target instanceof Player p ? p.getName() : target.getType().name());
                plugin.getLogger().info(String.format(
                        "[bullet %s t%d] MOVE @ %s → %s | %s | tgt=%s%s%s%s%s%s",
                        shortId(proj.entityId), now - proj.spawnTick,
                        fmtLoc(loc), fmtLoc(candidate), wpInfo, tName,
                        (wpBefore != proj.pathIndex ? " WP-ADV" : ""),
                        (avoidanceFired ? " AVOID" : ""),
                        (candidateBlocked ? " BLOCKED" : ""),
                        (nudgedUp ? " +Y" : ""),
                        (inGrace ? " GRACE" : "")));
            }

            world.spawnParticle(Particle.END_ROD, loc, 4, 0.05, 0.05, 0.05, 0.0);
            bullet.teleport(candidate);
            // Zero out velocity so vanilla's next-tick internal raycast covers only the tiny
            // 0.05-block target-chase adjustment — far less likely to graze a corner block and
            // trigger a false-positive vanilla discard (which would cost us a respawn + the
            // visible particle blip).
            bullet.setVelocity(ZERO);
        }
    }

    private LivingEntity resolveTarget(TurretProjectile proj) {
        if (proj.targetId == null) return null;
        Entity e = Bukkit.getEntity(proj.targetId);
        if (e instanceof LivingEntity le && le.isValid() && !le.isDead()) return le;
        return null;
    }

    private static boolean isSolid(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        // Never force a chunk load from a hot-path collision check: reading a block in an
        // unloaded chunk triggers synchronous chunk load/generation on the main thread (the
        // root cause of the TPS spikes). An unloaded cell is treated as non-solid so the bullet
        // simply coasts through instead of stalling the tick to generate terrain.
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        // Block#isPassable() (block-state-accurate) instead of Material#isSolid() (enum-level, can
        // misreport) — same single definition of "wall" the A* planner uses, so the bullet's
        // collision and the path it was given agree on what's solid.
        return !loc.getBlock().isPassable();
    }

    /**
     * True iff there is a clear, all-passable straight line from {@code from} to {@code toEye} —
     * the bullet could fly directly at the target without crossing a wall. Turret-structure cells
     * count as clear (the bullet phases those). This gates direct homing: a bullet with no A* road
     * only steers straight at its target when this holds, so it never barrels through solid blocks.
     */
    private boolean hasClearShot(Location from, Location toEye) {
        World w = from.getWorld();
        if (w == null || toEye.getWorld() == null || !toEye.getWorld().equals(w)) return false;
        Vector delta = toEye.toVector().subtract(from.toVector());
        double dist = delta.length();
        if (dist < 0.5) return true;
        Vector unit = delta.multiply(1.0 / dist);
        double stepLen = 0.25;
        int steps = (int) (dist / stepLen);
        for (int i = 1; i < steps; i++) {
            Location pt = from.clone().add(unit.clone().multiply(i * stepLen));
            if (!w.isChunkLoaded(pt.getBlockX() >> 4, pt.getBlockZ() >> 4)) return false;
            if (plugin.isInTurretZone(pt)) continue;
            if (isSolid(pt)) return false;
        }
        return true;
    }

    /**
     * Whether a candidate cell should block a projectile this tick. Three layers:
     *   1. During the 2 s spawn grace, nothing blocks (so the bullet can clear its own dome).
     *   2. Outside grace, cells inside any turret zone are still pass-through (so neighbouring
     *      turret structures, or the same turret on a second pass, don't make the bullet pop).
     *   3. Else: standard solid-block check.
     */
    private boolean blocksMovement(Location loc, boolean inGrace) {
        // A* and the projectile share ONE notion of "wall": a cell blocks the bullet iff it is a
        // real solid block that is NOT part of a turret structure. The spawn grace NO LONGER
        // blanket-disables collision — that let freshly-fired bullets phase straight through
        // terrain (the "dive down through 8 stone blocks at a buried mob" bug). The bullet still
        // clears its own dome because every turret-structure cell is pass-through via
        // isInTurretZone, so the grace flag is intentionally ignored here.
        if (!isSolid(loc)) return false;
        if (plugin.isInTurretZone(loc)) return false;
        return true;
    }

    private static Vector perpendicularHorizontal(Vector v) {
        return new Vector(-v.getZ(), 0, v.getX()).normalize();
    }

    /** True iff the player's current gamemode makes them invisible to turret combat. */
    private static boolean isImmuneGameMode(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.CREATIVE || g == GameMode.SPECTATOR;
    }

    /**
     * Cached reachability gate. Tries three sample aim points (eyes, feet, head+1) so a target
     * standing right against a wall isn't falsely marked unreachable just because the eye-line
     * is occluded. On all-fail, records an expiry tick after which we'll try again — keeps the
     * per-tick raycast budget bounded when many candidates are around but all unreachable.
     */
    private boolean isReachable(Location from, LivingEntity target) {
        UUID id = target.getUniqueId();
        long now = Bukkit.getCurrentTick();
        Long expiry = unreachableUntilTick.get(id);
        if (expiry != null && now < expiry) return false;

        Location feet = target.getLocation();
        if (raycastOpen(from, target.getEyeLocation())) return true;
        if (raycastOpen(from, feet)) return true;
        if (raycastOpen(from, feet.clone().add(0, target.getHeight() + 1, 0))) return true;

        unreachableUntilTick.put(id, now + TurretCombatTuning.UNREACHABLE_CACHE_TICKS);
        return false;
    }

    /**
     * Cheap line-of-sight: step from {@code from} toward {@code to} at {@link
     * TurretCombatTuning#REACHABILITY_STEP}-block increments, count solid blocks encountered.
     * Returns true (= path possibly viable) until the count exceeds
     * {@link TurretCombatTuning#REACHABILITY_MAX_SOLID_BLOCKS}. Cells inside any turret zone
     * are skipped (bullet phases through). Not actual A* — but enough to filter out
     * underground targets while letting the bullet's own avoidance handle thin obstacles.
     */
    private boolean raycastOpen(Location from, Location to) {
        World world = from.getWorld();
        if (world == null) return false;
        Vector dir = to.toVector().subtract(from.toVector());
        double distance = dir.length();
        if (distance < 0.5) return true;
        dir.multiply(1.0 / distance);
        double step = TurretCombatTuning.REACHABILITY_STEP;
        int steps = (int) (distance / step);
        int solidCount = 0;
        for (int i = 1; i < steps; i++) {
            Location pt = from.clone().add(dir.clone().multiply(i * step));
            if (plugin.isInTurretZone(pt)) continue;
            // Skip cells in unloaded chunks rather than forcing a synchronous load/generation
            // on the main thread (the TPS-spike root cause). A gap in the raycast just means we
            // don't penalise the target for terrain we haven't loaded.
            if (!world.isChunkLoaded(pt.getBlockX() >> 4, pt.getBlockZ() >> 4)) continue;
            // isOccluding (vs isSolid): only counts opaque full-cube blocks. Leaves, glass,
            // vines, doors-when-open all return false here — bullets phase through them
            // visually anyway, no reason to mark a target unreachable for sitting behind cover.
            if (pt.getBlock().getType().isOccluding()) {
                solidCount++;
                if (solidCount > TurretCombatTuning.REACHABILITY_MAX_SOLID_BLOCKS) return false;
            }
        }
        return true;
    }

    /** GC the reachability cache. Cheap pass run once per combat tick. */
    private void pruneReachabilityCache() {
        long now = Bukkit.getCurrentTick();
        unreachableUntilTick.entrySet().removeIf(e -> e.getValue() <= now);
    }

    /**
     * Pick a unit vector pointing toward open air to bias the projectile's heading toward.
     * Prefers UP (cleanest for trees / walls — a single +Y bias produces a smooth arc), then
     * sweeps both horizontal perpendiculars at increasing distance. The probe distances are
     * generous so a tree with a 3-wide leaf canopy or a 4-block-tall trunk still resolves.
     * Returns null only when truly boxed in.
     */
    private Vector computeAvoidanceDirection(Location loc, Vector dirNormal, boolean inGrace) {
        Vector forwardHalf = dirNormal.clone().multiply(0.5);
        // Sweep upward over the obstacle. Probe immediately in front of the bullet so we
        // detect "over the canopy" rather than just "directly above the current cell".
        for (int h = 1; h <= TurretCombatTuning.AVOIDANCE_UP_PROBE_MAX; h++) {
            Location probe = loc.clone().add(forwardHalf).add(0, h, 0);
            if (!blocksMovement(probe, inGrace)) return new Vector(0, 1, 0);
        }
        // Sweep both perpendiculars at increasing offset. Alternate sides per distance so a
        // tight obstacle with one open flank gets routed correctly.
        Vector perp = perpendicularHorizontal(dirNormal);
        Vector negPerp = perp.clone().multiply(-1);
        for (int d = 1; d <= TurretCombatTuning.AVOIDANCE_SIDE_PROBE_MAX; d++) {
            if (!blocksMovement(loc.clone().add(perp.clone().multiply(d)), inGrace)) return perp;
            if (!blocksMovement(loc.clone().add(negPerp.clone().multiply(d)), inGrace)) return negPerp;
        }
        return null;
    }

    /**
     * Hit-detection. Two modes:
     * <ul>
     *   <li><b>Target-locked</b> ({@code targetId != null}): only the tracked target counts.
     *       Bullets phase through every other entity until they actually reach the one they
     *       were fired at. Stops zombies / spiders intercepting shots aimed at a skeleton.</li>
     *   <li><b>Free</b> ({@code targetId == null}, e.g. target died mid-flight): falls back to
     *       "first non-member living entity in radius" — bullet finishes wherever it lands.</li>
     * </ul>
     * <p>AOE damage on detonation is unchanged — anyone in {@link
     * TurretCombatTuning#EXPLOSION_RADIUS} still takes splash, so collateral around the impact
     * point still works.
     */
    private LivingEntity findProximityHit(Location bulletLoc, UUID claimOwner, UUID targetId) {
        World world = bulletLoc.getWorld();
        if (world == null) return null;
        Vector point = bulletLoc.toVector();
        Claim spawnClaim = plugin.getClaimManager().getClaimOf(claimOwner);
        double hitRadius = TurretCombatTuning.HIT_RADIUS;
        double hitRadiusSq = hitRadius * hitRadius;

        // Target-locked mode: only test the one entity. Cheaper too (no nearbyEntities scan).
        if (targetId != null) {
            Entity t = Bukkit.getEntity(targetId);
            if (!(t instanceof LivingEntity le) || !le.isValid() || le.isDead()) return null;
            if (le instanceof Player p) {
                if (spawnClaim != null && spawnClaim.isMember(p.getUniqueId())) return null;
                if (isImmuneGameMode(p)) return null;
            }
            return sphereDistSqToBox(point, le.getBoundingBox()) <= hitRadiusSq ? le : null;
        }

        // Free mode: bullet has lost its target, hit whatever's in range. Scan a generous
        // bounding cuboid then sphere-filter inside the loop. The cuboid extent matches the
        // sphere radius — `getNearbyEntities` is a coarse pre-filter, not the final check.
        double scan = hitRadius + 1.0;
        for (Entity e : world.getNearbyEntities(bulletLoc, scan, scan, scan)) {
            if (e instanceof ShulkerBullet) continue;
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (e instanceof Shulker) continue;
            if (le instanceof Player p) {
                if (spawnClaim != null && spawnClaim.isMember(p.getUniqueId())) continue;
                if (isImmuneGameMode(p)) continue;
            }
            if (sphereDistSqToBox(point, e.getBoundingBox()) <= hitRadiusSq) return le;
        }
        return null;
    }

    /**
     * Squared distance from a point to the nearest point on an axis-aligned bounding box.
     * Zero when the point is inside the box; positive otherwise. Compare against {@code r²}
     * to test "point within radius {@code r} of the box" — i.e. sphere-from-point semantics.
     *
     * <p>This is what the user asked for when they said "1.5 blocks from the projectile
     * centre in every direction": a true sphere around the impact point, not a Minkowski
     * cuboid that reaches further in the diagonals than along the axes.
     */
    private static double sphereDistSqToBox(Vector point, BoundingBox box) {
        double dx = Math.max(0.0, Math.max(box.getMinX() - point.getX(),
                point.getX() - box.getMaxX()));
        double dy = Math.max(0.0, Math.max(box.getMinY() - point.getY(),
                point.getY() - box.getMaxY()));
        double dz = Math.max(0.0, Math.max(box.getMinZ() - point.getZ(),
                point.getZ() - box.getMaxZ()));
        return dx * dx + dy * dy + dz * dz;
    }

    // =====================================================================
    // Hit resolution.
    // =====================================================================

    /**
     * AOE explosion at the bullet's location. Every eligible living entity whose bounding box
     * (expanded by {@link TurretCombatTuning#EXPLOSION_RADIUS}) contains the impact point takes
     * the level-tuned damage. Eligibility filters: skip claim members of the spawning claim,
     * skip CREATIVE / SPECTATOR players, skip Shulker entities (turret NPC bodies).
     *
     * <p>The {@code primaryTarget} parameter is informational only — it's already inside the
     * AOE in practice; we hand it in so the caller's intent is documented.
     */
    private void explodeOn(ShulkerBullet bullet, TurretProjectile proj, LivingEntity primaryTarget) {
        World world = bullet.getWorld();
        if (world == null) { proj.markedForCleanup = true; bullet.remove(); return; }

        // Pick the epicentre. With a primary target (the normal case — bullet detonated on the
        // entity it was tracking), use the TARGET's centre, not the bullet's stop position.
        // Vanilla ShulkerBullet stops a tick or so before fully overlapping the target, so the
        // bullet's location is typically ~0.5-1 b short of the target's body. A 1.5-block
        // splash sphere around the bullet position therefore misses other entities right next
        // to the target. Centring on the target itself makes the splash predictable — anyone
        // within 1.5 b of the target's centre gets caught.
        //
        // Free-mode hit (target died mid-flight, bullet hit something random) falls back to the
        // bullet's location since there's no target to centre on.
        Location at;
        if (primaryTarget != null && primaryTarget.isValid() && !primaryTarget.isDead()) {
            at = primaryTarget.getLocation().clone().add(0, primaryTarget.getHeight() * 0.5, 0);
        } else {
            at = bullet.getLocation();
        }

        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.3f);
        world.spawnParticle(Particle.EXPLOSION, at, 1);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 0);

        double dmg = TurretCombatTuning.damage(proj.level);
        double radius = TurretCombatTuning.EXPLOSION_RADIUS;
        Claim spawn = plugin.getClaimManager().getClaimOf(proj.spawningClaimOwner);
        Vector impact = at.toVector();

        // Resolve the firing turret's Shulker entity so we can attribute damage. This lets the
        // PlayerDeathListener generate "Eliminated by Turret #N (owner)" messages instead of
        // generic "X died" / "X was killed by Shulker" boilerplate.
        Entity damager = null;
        if (spawn != null && proj.firingSlot >= 0 && proj.firingSlot < Claim.MAX_TURRETS) {
            Turret firingTurret = spawn.getSlotTurret(proj.firingSlot);
            if (firingTurret != null && firingTurret.getNpcId() >= 0) {
                NPC npc = CitizensAPI.getNPCRegistry().getById(firingTurret.getNpcId());
                if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                    damager = npc.getEntity();
                }
            }
        }

        int damaged = 0;
        int healed = 0;
        double radiusSq = radius * radius;
        // Scan a little wider than the radius so big entities whose bounding box pokes into the
        // splash zone are still considered — the actual hit test uses sphere-from-impact
        // semantics (see sphereDistSqToBox). With the impact now centred on the target (above),
        // 1.5-block splash reliably catches anyone within 1.5 b of the target's centre.
        for (Entity e : world.getNearbyEntities(at, radius + 1.0, radius + 1.0, radius + 1.0)) {
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (e instanceof Shulker) continue;
            if (sphereDistSqToBox(impact, e.getBoundingBox()) > radiusSq) continue;

            if (le instanceof Player p) {
                if (isImmuneGameMode(p)) continue;
                // Friendly fire → heal instead of damage. Splash on owner/friends within the
                // 0.75 radius grants Regeneration scaled by turret level (3/4/5 s for L1/L2/L3).
                if (spawn != null && spawn.isMember(p.getUniqueId())) {
                    applyFriendlySplashHeal(p, proj.level);
                    if (plugin.getQuests() != null) {
                        plugin.getQuests().complete(p.getUniqueId(), Quest.TURRET_REGEN);
                    }
                    healed++;
                    continue;
                }
            }
            // A turret must never harm a villager — they're a base's traders, and a shot clipping a
            // wall shouldn't let the splash cull them. Give a villager the SAME friendly heal a base
            // member gets, whoever's claim it's in. AbstractVillager = Villager + WanderingTrader only;
            // a ZombieVillager is a Zombie (a hostile raid mob), so it is NOT matched here and still
            // takes damage as before.
            if (le instanceof org.bukkit.entity.AbstractVillager) {
                applyFriendlySplashHeal(le, proj.level);
                healed++;
                continue;
            }
            // Attribute the damage to the turret's shulker NPC if we found one — that's what
            // lets PlayerDeathListener rewrite the death message to credit the turret.
            //
            // Pre-scale for vanilla Player.actuallyHurt difficulty math, which silently rewrites
            // damage to players: Easy → min(amount/2+1, amount), Hard → amount*1.5, Normal → no-op.
            // We want our tuned values (L1=6, L2=8, L3=10) to LAND exactly on the player, so for
            // non-Normal difficulties we feed the inverse — `le.damage()` is then post-scaled by
            // vanilla back to our target. Mobs aren't affected by Player.actuallyHurt scaling, so
            // we only pre-scale when the target is a Player.
            double applied = (le instanceof Player) ? preScaleForDifficulty(dmg, le.getWorld()) : dmg;
            // Bypass vanilla invulnerableTime / lastHurt absorption. By default MC sets
            // noDamageTicks ≈ 20 after any hit, and any follow-up damage during that window only
            // counts the OVERFLOW above lastDamage — which means a salvo of 4 turret bullets
            // landing within ~1s collapses to a single hit. Turrets are coordinated point-defence
            // here, so we want every projectile to count. Reset both fields immediately before
            // the damage call so this specific shot lands at full value; the next hit (from a
            // turret or anything else) starts a fresh i-frame from here.
            le.setNoDamageTicks(0);
            le.setLastDamage(0.0);
            // Metadata-based death-message attribution. The normal path lets
            // TurretKillListener resolve the turret from the damager NPC; that works for a live
            // turret. For panic-volley bullets the firing NPC has already despawned by the time
            // the bullet hits — without this stamp, the kill falls through to the vanilla
            // "you died" message. Stamping (slot|owner) on the victim immediately before the
            // damage call lets the listener resolve the turret via metadata as a fallback. The
            // tag is cleared 2 ticks later regardless so a non-fatal hit doesn't leave stale
            // attribution on a player who later dies to something unrelated.
            if (le instanceof Player victimPlayer && proj.firingSlot >= 0
                    && proj.spawningClaimOwner != null) {
                String tag = (proj.firingSlot + 1) + "|" + proj.spawningClaimOwner.toString();
                victimPlayer.setMetadata(TURRET_KILL_META,
                        new FixedMetadataValue(plugin, tag));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (victimPlayer.isValid()
                            && victimPlayer.hasMetadata(TURRET_KILL_META)) {
                        victimPlayer.removeMetadata(TURRET_KILL_META, plugin);
                    }
                }, 2L);
            }
            if (damager instanceof LivingEntity dmgr) {
                le.damage(applied, dmgr);
            } else {
                le.damage(applied);
            }
            damaged++;
            Vector push = le.getLocation().toVector().subtract(impact);
            if (push.lengthSquared() > 1e-6) {
                push.normalize().multiply(0.15).setY(0.05);
                le.setVelocity(le.getVelocity().add(push));
            }
        }
        if (DEBUG_LOG) plugin.getLogger().info(String.format(
                "[bullet %s] EXPLODE at %s (L%d, dmg=%.1f [pre-scaled %.2f for %s] from slot #%d), AOE damaged %d, healed %d",
                shortId(bullet.getUniqueId()), fmtLoc(at),
                proj.level, dmg, preScaleForDifficulty(dmg, world), world.getDifficulty(),
                proj.firingSlot + 1, damaged, healed));
        proj.markedForCleanup = true;
        bullet.remove();
    }

    /** Grant the friendly turret splash-heal: Regeneration scaled by turret level (3/4/5 s for
     *  L1/L2/L3) — the buff base members get on a friendly splash, also used to make turrets heal
     *  rather than harm villagers. */
    private void applyFriendlySplashHeal(LivingEntity le, int level) {
        int secs = TurretCombatTuning.friendlyRegenSeconds(level);
        le.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.REGENERATION,
                secs * 20, 0, true, true, true));
    }

    /**
     * Invert vanilla's {@code Player.actuallyHurt} difficulty scaling so {@code target} damage
     * lands on the player exactly. Vanilla rewrites incoming player damage as:
     * <pre>
     *   Easy   → min(amount/2 + 1, amount)
     *   Normal → amount
     *   Hard   → amount * 1.5
     * </pre>
     * To deliver {@code target} after this transform we feed:
     * <pre>
     *   Easy   → 2 * (target - 1)     // since 2*(t-1)/2 + 1 = t and 2*(t-1) ≥ t for t ≥ 2
     *   Normal → target
     *   Hard   → target * 2/3
     * </pre>
     * Peaceful is irrelevant here (turrets don't fire at peaceful-difficulty players in practice;
     * we still return the raw target so behaviour is sane if the world is switched mid-fire).
     * Only call this for {@link Player} targets — mobs don't go through Player.actuallyHurt.
     */
    private static double preScaleForDifficulty(double target, World w) {
        Difficulty d = w == null ? Difficulty.NORMAL : w.getDifficulty();
        return switch (d) {
            case EASY -> Math.max(target, 2.0 * (target - 1.0));
            case HARD -> target * 2.0 / 3.0;
            default -> target;     // NORMAL + PEACEFUL
        };
    }

    // =====================================================================
    // Target selection — gated by active-enemy tracker.
    // =====================================================================

    /** Per-shot probability of overriding wither priority to instead target a hostile player. */
    private static final double WITHER_OVERRIDE_PLAYER_CHANCE = 0.25;

    /**
     * Elevation band relative to the muzzle, used as the primary sort key for candidate ranking.
     * Smaller value = higher priority. Within a band, we sort by horizontal distance.
     *
     * <p>The split: anything within ±{@link #SAME_LEVEL_DY} blocks of the muzzle is "same level"
     * (free of vertical detours, basically a horizontal LOS shot). Things clearly above the
     * muzzle are next — bullets arc up easily and most mob spawn locations above are open sky.
     * Things below the muzzle are last — these are very likely buried in caves with no air route,
     * and the previous behavior of always sorting by Euclidean distance kept feeding those buried
     * mobs to A* first, wasting the per-tick budget on an unreachable enderman 33 blocks below
     * while a zombie 32 blocks away on the same level got skipped.
     */
    private static final int SAME_LEVEL_DY = 4;
    private static int elevationBand(Location origin, LivingEntity e) {
        double dy = e.getLocation().getY() - origin.getY();
        if (Math.abs(dy) <= SAME_LEVEL_DY) return 0;     // same height
        if (dy > 0)                         return 1;    // above muzzle
        return 2;                                        // below muzzle (likely underground)
    }

    /**
     * Return every eligible target in range, ranked by elevation tier first (same-level → above →
     * below) and then by horizontal distance within each tier. The combat tick walks this list
     * A*-ing each one and fires at the first reachable.
     *
     * <p>Wither/player/mob priority is preserved as the outermost bucket — a wither always
     * outranks anything else regardless of elevation. The elevation tier governs the order WITHIN
     * each category. This is what the user asked for: stop wasting A* budget on buried mobs when
     * an open-air target exists on the same plane.
     *
     * <p>{@code preferPlayer} (rolled per shot by the caller at {@value #WITHER_OVERRIDE_PLAYER_CHANCE})
     * promotes the hostile-player bucket ahead of siege when any eligible player exists, so a turret
     * occasionally punishes the player even while a wither/raid cohort soaks the rest of the salvo.
     * When false (or no eligible player is present) the order is the default siege → players → mobs.
     */
    private List<LivingEntity> findEligibleTargets(Claim claim, Location origin, double range,
                                                   boolean preferPlayer) {
        World world = origin.getWorld();
        List<LivingEntity> out = new java.util.ArrayList<>();
        if (world == null) return out;
        // HORIZONTAL range, not 3D. A turret tower with a muzzle 14+ blocks above the ground was
        // only reaching ~32 blocks horizontally inside a 35-block 3D sphere — players experience
        // range in XZ, not as a sphere. dy is handled separately by the elevation-tier ranking
        // and by A* (path can travel any amount of vertical inside MAX_SEARCH_RADIUS).
        double rangeSq = range * range;
        java.util.function.ToDoubleFunction<Location> horizDistSq = loc -> {
            double dx = loc.getX() - origin.getX();
            double dz = loc.getZ() - origin.getZ();
            return dx * dx + dz * dz;
        };

        // Cheap pre-filter shared by all three buckets below. raycastOpen counts opaque blocks
        // (isOccluding — leaves/glass/etc are see-through) up to REACHABILITY_MAX_SOLID_BLOCKS;
        // anything past that is considered "buried" and dropped here. This is the key TPS win:
        // a buried enderman 28b below the turret was previously being fed to A* every 3-5s,
        // each call exhausting 4×4000 nodes before returning null. With the raycast cull it
        // never gets that far — we just skip it as a candidate. The unreachable-tick cache is
        // also queried so repeat-failed targets aren't re-raycast every poll.
        java.util.function.Predicate<LivingEntity> preReachable = le -> isReachable(origin, le);

        // Comparator: elevation tier asc, then HORIZONTAL distance asc (Y excluded from distance
        // here because we already weighted it via the tier). Falls back on full 3D distance for
        // tie-break inside the same XZ point (extremely rare).
        Comparator<LivingEntity> ranking = Comparator
                .<LivingEntity>comparingInt(e -> elevationBand(origin, e))
                .thenComparingDouble(e -> {
                    double dx = e.getLocation().getX() - origin.getX();
                    double dz = e.getLocation().getZ() - origin.getZ();
                    return dx * dx + dz * dz;
                })
                .thenComparingDouble(e -> e.getLocation().distanceSquared(origin));

        // SIEGE bucket — withers + raid-attack NPCs (raiders + ravagers). Always included,
        // bypasses the active-enemy gate (these are inherently hostile by construction).
        // Skipped ONLY when we can positively confirm the raid's attacker is friendly to this
        // base's owner. If the raid can't be resolved (registry key drift, raid record missing,
        // …) we still treat the raid NPC as hostile and shoot it — the old "getRaid==null →
        // continue" behaviour silently dropped EVERY raider whenever recognition hiccuped, which
        // is exactly the "turret never shoots raiders" bug.
        List<LivingEntity> siege = new java.util.ArrayList<>();
        for (Entity e : world.getNearbyEntities(origin, range, range, range)) {
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (horizDistSq.applyAsDouble(le.getLocation()) > rangeSq) continue;
            if (e instanceof Wither) { siege.add(le); continue; }
            if (!isRaidNpc(le)) continue;
            if (claim != null) {
                UUID raidId = raidNpcIdFor(le);
                ActiveRaid raid = (raidId == null || plugin.getRaidManager() == null)
                        ? null : plugin.getRaidManager().getRaid(raidId);
                // The friendly-fire exemption protects a BYSTANDER ally's turrets from shooting
                // their friend's raiders. It must NOT apply to the base actually being raided — a
                // defender's own turrets always fire on the raiders attacking them, even if the
                // defender happens to be allied/friended with the attacker (e.g. the dev base).
                if (raid != null
                        && !claim.getOwner().equals(raid.getZoneOwnerId())
                        && plugin.getRaidManager().isFriendlyToAttacker(
                                raid.getAttackerId(), claim.getOwner())) continue;
            }
            siege.add(le);
        }
        siege.sort(ranking);

        // Players (only if we have a claim to check enemy status against). Pre-filtered by
        // raycast so a player who portaled into a sealed bunker doesn't burn A* budget every poll.
        List<LivingEntity> players = new java.util.ArrayList<>();
        if (claim != null) {
            for (Player p : world.getPlayers()) {
                if (p.isDead() || !p.isValid()) continue;
                if (isImmuneGameMode(p)) continue;
                if (claim.isMember(p.getUniqueId())) continue;
                if (!plugin.getEnemyTracker().isActiveEnemy(claim.getOwner(), p)) continue;
                if (horizDistSq.applyAsDouble(p.getLocation()) > rangeSq) continue;
                if (!preReachable.test(p)) continue;
                players.add(p);
            }
            players.sort(ranking);
        }

        // Hostile mobs (last priority, gated by setting). Pre-filtered — this is the big TPS
        // win, since buried/caved mobs are the common 0-reachability case. Phantom is added
        // explicitly: it implements Flying but not Monster in Bukkit's hierarchy, so the
        // instanceof-Monster check would otherwise skip it.
        List<LivingEntity> mobs = new java.util.ArrayList<>();
        if (claim == null || claim.attacksHostileMobs()) {
            for (Entity e : world.getNearbyEntities(origin, range, range, range)) {
                if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
                if (!(e instanceof Monster) && !(e instanceof Phantom)) continue;
                if (e instanceof Wither) continue;     // already in higher-priority bucket
                if (isRaidNpc(le)) continue;            // raid NPCs handled in siege bucket
                if (horizDistSq.applyAsDouble(le.getLocation()) > rangeSq) continue;
                if (!preReachable.test(le)) continue;
                mobs.add(le);
            }
            mobs.sort(ranking);
        }

        // Assemble final order. The per-shot coin flip (preferPlayer) decides whether the player
        // bucket jumps ahead of siege: on a successful 25% roll the turret leads with the player
        // (if any are eligible), otherwise the canonical siege → players → mobs ordering holds.
        // Mobs are always last either way.
        if (preferPlayer && !players.isEmpty()) {
            out.addAll(players);
            out.addAll(siege);
        } else {
            out.addAll(siege);
            out.addAll(players);
        }
        out.addAll(mobs);
        return out;
    }

    /**
     * Return the raid-id stamped on this entity's Citizens NPC, or null if the entity isn't
     * a raid-spawned raider/ravager. HashMap lookup in {@link RaidEntityManager} — cheap,
     * runs once per candidate during target gathering.
     */
    private UUID raidNpcIdFor(LivingEntity e) {
        RaidEntityManager rem = plugin.getRaidEntities();
        if (rem != null) {
            CustomRaider r = rem.getRaider(e.getUniqueId());
            if (r != null && r.getRaidId() != null) return r.getRaidId();
            CustomRavager rv = rem.getRavager(e.getUniqueId());
            if (rv != null && rv.getRaidId() != null) return rv.getRaidId();
        }
        // Fallback: the raid-id is stamped on the Citizens NPC data at spawn. This survives any
        // entity-UUID / registry-key drift that would make the map lookup above miss.
        if (Bukkit.getPluginManager().getPlugin("Citizens") != null) {
            try {
                NPC npc = CitizensAPI.getNPCRegistry().getNPC(e);
                if (npc != null) {
                    Object rid = npc.data().get("raid-id");
                    if (rid != null) return UUID.fromString(rid.toString());
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Robust "is this a raid raider/ravager?" test. Tries the wrapper registry first, then falls
     * back to the {@code raid-role} data tag on the Citizens NPC. The fallback is what makes a
     * raider eligible even if its entity UUID drifted out of the registry map — the old code
     * relied solely on the map and silently treated every raider as a non-target when it missed.
     */
    private boolean isRaidNpc(LivingEntity e) {
        RaidEntityManager rem = plugin.getRaidEntities();
        if (rem != null && (rem.getRaider(e.getUniqueId()) != null
                || rem.getRavager(e.getUniqueId()) != null)) return true;
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return false;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(e);
            if (npc == null) return false;
            Object role = npc.data().get("raid-role");
            return role != null && ("raider".equals(role.toString()) || "ravager".equals(role.toString()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Closest eligible target around {@code origin} within {@code range}. Priority order:
     * <ol>
     *   <li><b>Wither</b> — always highest priority (siege boss). Withers are also {@link Monster}s
     *       so the legacy mob branch could pick them up if the claim's attack-mobs toggle is on, but we
     *       want the priority to be unconditional — even with mob-attacking disabled.</li>
     *   <li><b>Hostile player</b> — must be an active enemy of the claim (see
     *       {@link TurretEnemyTracker}).</li>
     *   <li><b>Hostile mob</b> — only if the owning claim's {@link Claim#attacksHostileMobs()} is on.</li>
     * </ol>
     *
     * <p>When a wither AND a hostile player are both in range we don't just lock onto the wither.
     * That would leave the player free to potshot from a corner while the wither soaks every
     * bullet. Each shot independently rolls {@value #WITHER_OVERRIDE_PLAYER_CHANCE} to swap the
     * pick to the player, so on average ~25% of the salvo still keeps the player honest while
     * the wither remains the primary threat.
     *
     * <p>When {@code claim == null} the player path is skipped (no claim to check enemy-status
     * against) and we fall straight through to mobs.
     */
    private LivingEntity findEligibleTarget(Claim claim, Location origin, double range) {
        World world = origin.getWorld();
        if (world == null) return null;
        double rangeSq = range * range;

        // Closest SIEGE-tier threat: wither OR raid NPC (raider / ravager). Friendly-fire
        // respect — skip raid NPCs whose attacker is friendly to this claim's owner, WITH the
        // defender-base exception (matching the primary siege scan): a base's own turrets always
        // fire on the raiders attacking it, even if the owner is friended/allied to the attacker
        // (e.g. the dev base). Only a bystander ally's turrets skip their friend's raiders.
        LivingEntity closestSiege = null;
        double closestSiegeSq = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(origin, range, range, range)) {
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            double dSq = le.getLocation().distanceSquared(origin);
            if (dSq > rangeSq) continue;
            if (e instanceof Wither) {
                if (dSq < closestSiegeSq) { closestSiege = le; closestSiegeSq = dSq; }
                continue;
            }
            UUID raidId = raidNpcIdFor(le);
            if (raidId == null) continue;
            ActiveRaid raid = plugin.getRaidManager() == null
                    ? null : plugin.getRaidManager().getRaid(raidId);
            if (raid == null) continue;
            if (claim != null
                    && !claim.getOwner().equals(raid.getZoneOwnerId())
                    && plugin.getRaidManager().isFriendlyToAttacker(
                            raid.getAttackerId(), claim.getOwner())) continue;
            if (dSq < closestSiegeSq) { closestSiege = le; closestSiegeSq = dSq; }
        }

        Player closestPlayer = null;
        if (claim != null) {
            double closestPlayerSq = Double.MAX_VALUE;
            for (Player p : world.getPlayers()) {
                if (p.isDead() || !p.isValid()) continue;
                if (isImmuneGameMode(p)) continue;
                if (claim.isMember(p.getUniqueId())) continue;
                if (!plugin.getEnemyTracker().isActiveEnemy(claim.getOwner(), p)) continue;
                double dSq = p.getLocation().distanceSquared(origin);
                if (dSq > rangeSq) continue;
                if (dSq < closestPlayerSq) {
                    closestPlayer = p;
                    closestPlayerSq = dSq;
                }
            }
        }

        // Priority resolution. Siege + player both present → 25% chance to swap to the player
        // so they don't get a free ride while the siege threat soaks the salvo.
        if (closestSiege != null && closestPlayer != null) {
            return java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                    < WITHER_OVERRIDE_PLAYER_CHANCE ? closestPlayer : closestSiege;
        }
        if (closestSiege != null) return closestSiege;
        if (closestPlayer != null) return closestPlayer;
        if (claim != null && !claim.attacksHostileMobs()) return null;

        LivingEntity closestMob = null;
        double closestMobSq = Double.MAX_VALUE;
        for (Entity e : world.getNearbyEntities(origin, range, range, range)) {
            if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead()) continue;
            if (!(e instanceof Monster) && !(e instanceof Phantom)) continue;
            // Withers + raid NPCs were considered above at higher priority; skip here so the
            // mob branch can't re-pick something we already rejected via the override roll.
            if (e instanceof Wither) continue;
            if (raidNpcIdFor(le) != null) continue;
            double dSq = le.getLocation().distanceSquared(origin);
            if (dSq > rangeSq) continue;
            if (dSq < closestMobSq) {
                closestMob = le;
                closestMobSq = dSq;
            }
        }
        return closestMob;
    }
}
