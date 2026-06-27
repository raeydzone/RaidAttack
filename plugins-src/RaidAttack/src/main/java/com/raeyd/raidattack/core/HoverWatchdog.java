package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.claim.ClaimManager;
import com.raeyd.raidattack.claim.ZoneListener;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Ghost-block / hover-exploit detector keyed on denied placements.
 *
 * <p>The exploit: a high-ping player tries to place blocks inside a foreign claim. The
 * placement is cancelled server-side by {@link ZoneListener}, but client-side prediction
 * renders the block long enough for the client to step or jump on it and translate to a
 * position inside the claim that has no server-side support.
 *
 * <p>This watchdog uses the cancellation itself as the signal, but ONLY when the denials
 * form a vertical pillar pattern. A single ground-level denied placement (or two
 * horizontally adjacent ones) is harmless — the player isn't gaining a position they
 * couldn't otherwise reach. The actual exploit requires the player to STACK denials
 * vertically (pillar-up across the boundary to reach a roof or high ledge). So each tick,
 * for every online player, we look at their recent denial buffer and check: do at least
 * {@link #MIN_STACK_DENIALS} of them cluster within a {@link #STACK_XZ_RADIUS}-block-wide
 * column AND span at least {@link #MIN_STACK_HEIGHT} blocks vertically? AND is the
 * player currently in a foreign claim within {@link #PLAYER_NEAR_STACK_RADIUS} blocks of
 * that column? If yes, snap back. Otherwise leave them alone.
 *
 * <p>We deliberately do NOT require the player to currently be airborne — the exploit
 * often deposits them onto a real server-side block (an existing roof or structure they
 * reached via ghost-block bridging). The vertical-stack-of-denials pattern is the proof
 * of intent; landing on a real surface afterwards doesn't change that.
 *
 * <p>Legit "I'm in the air on purpose" cases are whitelisted: gliding, vehicle, climbing,
 * water/lava, levitation / slow-falling, scaffolding, bubble column, powder snow, cobweb.
 * Creative/Spectator and bypass-permission holders are skipped entirely.
 */
public final class HoverWatchdog {

    /** How long a denied placement stays "fresh" for the pillar-pattern check. */
    private static final long DENIAL_TTL_MS = 3000L;

    /** Minimum denial count that must cluster in a vertical column to count as a pillar. */
    private static final int MIN_STACK_DENIALS = 3;

    /** XZ radius (blocks) within which denials are considered part of the same column. */
    private static final double STACK_XZ_RADIUS = 2.0;
    private static final double STACK_XZ_RADIUS_SQ = STACK_XZ_RADIUS * STACK_XZ_RADIUS;

    /** Minimum vertical span (blocks) the column must cover — rules out flat clusters. */
    private static final double MIN_STACK_HEIGHT = 2.0;

    /** How close the player must currently be (XZ) to the column to be snapped. */
    private static final double PLAYER_NEAR_STACK_RADIUS = 6.0;
    private static final double PLAYER_NEAR_STACK_RADIUS_SQ = PLAYER_NEAR_STACK_RADIUS * PLAYER_NEAR_STACK_RADIUS;

    /** Hard cap per player on stored denials, to bound memory under macro spam. */
    private static final int MAX_DENIALS_PER_PLAYER = 32;

    private final HomeSystemPlugin plugin;
    private final ClaimManager claims;

    /** Per-player recent denied placement coordinates. */
    private final Map<UUID, Deque<DeniedPlacement>> denials = new HashMap<>();
    /** Last position where the player was grounded AND outside every foreign claim. */
    private final Map<UUID, Location> lastSafe = new HashMap<>();

    private BukkitTask task;

    public HoverWatchdog(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.claims = plugin.getClaimManager();
    }

    public void start() {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        denials.clear();
        lastSafe.clear();
    }

    /**
     * Called by {@link ZoneListener} on every cancelled {@code BlockPlaceEvent} inside a
     * foreign claim. The recorded coordinate is the block the player tried to place — the
     * exact spot the client may briefly render as a usable scaffold.
     */
    public void recordDeniedPlacement(Player player, Location blockLoc) {
        if (player == null || blockLoc == null || blockLoc.getWorld() == null) return;
        UUID id = player.getUniqueId();
        Deque<DeniedPlacement> buf = denials.computeIfAbsent(id, k -> new ArrayDeque<>());
        buf.addLast(new DeniedPlacement(
                blockLoc.getWorld().getUID(),
                blockLoc.getX() + 0.5, blockLoc.getY() + 0.5, blockLoc.getZ() + 0.5,
                System.currentTimeMillis()));
        while (buf.size() > MAX_DENIALS_PER_PLAYER) buf.pollFirst();
    }

    private void tick() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            try {
                tickPlayer(p);
            } catch (Throwable t) {
                plugin.getLogger().warning("HoverWatchdog tick failed for " + p.getName() + ": " + t);
            }
        }
    }

    private void tickPlayer(Player p) {
        UUID id = p.getUniqueId();

        if (p.hasPermission("homesystem.bypass")
                || p.getGameMode() == GameMode.CREATIVE
                || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        boolean grounded = isStandingOnRealBlock(p);
        Claim claim = claims.getClaimAt(p.getLocation());
        boolean inForeign = claim != null && !claim.isMember(id);

        // Refresh the snap-back anchor every time the player is on real ground outside
        // any foreign claim.
        if (!inForeign && grounded) {
            lastSafe.put(id, p.getLocation());
        }

        if (!inForeign) return;
        if (isLegitFloat(p)) return;

        // Only fire when the player's recent denials form a vertical pillar pattern and
        // the player is currently near that pillar's XZ column. This rejects:
        //   - single denials (one accidental attempt at the wall)
        //   - flat horizontal denial clusters (probing a row of blocks at ground level)
        // and keeps the verdict to the actual exploit shape: stacked denials gaining height.
        if (matchesPillarPattern(id, p.getLocation())) {
            snapBack(p, claim);
        }
    }

    // ----- denial matching -------------------------------------------------

    /**
     * Look for a cluster of recent denials that:
     *   1. Number at least {@link #MIN_STACK_DENIALS}.
     *   2. Cluster within {@link #STACK_XZ_RADIUS} blocks of a common XZ center.
     *   3. Span at least {@link #MIN_STACK_HEIGHT} blocks vertically.
     *   4. Whose XZ center is within {@link #PLAYER_NEAR_STACK_RADIUS} of the player.
     *
     * <p>Expired entries are reaped on the same pass.
     */
    private boolean matchesPillarPattern(UUID id, Location playerLoc) {
        Deque<DeniedPlacement> buf = denials.get(id);
        if (buf == null || buf.size() < MIN_STACK_DENIALS) return false;

        long now = System.currentTimeMillis();
        UUID world = playerLoc.getWorld().getUID();

        // Collect fresh, same-world denials; reap expired ones in place.
        java.util.List<DeniedPlacement> fresh = new java.util.ArrayList<>(buf.size());
        Iterator<DeniedPlacement> it = buf.iterator();
        while (it.hasNext()) {
            DeniedPlacement d = it.next();
            if (now - d.timestamp > DENIAL_TTL_MS) { it.remove(); continue; }
            if (d.worldId.equals(world)) fresh.add(d);
        }
        if (fresh.size() < MIN_STACK_DENIALS) return false;

        double px = playerLoc.getX();
        double pz = playerLoc.getZ();

        // Try each fresh denial as a column center; gather others within XZ radius.
        for (DeniedPlacement center : fresh) {
            int count = 0;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (DeniedPlacement other : fresh) {
                double dx = other.x - center.x;
                double dz = other.z - center.z;
                if (dx * dx + dz * dz <= STACK_XZ_RADIUS_SQ) {
                    count++;
                    if (other.y < minY) minY = other.y;
                    if (other.y > maxY) maxY = other.y;
                }
            }
            if (count < MIN_STACK_DENIALS) continue;
            if (maxY - minY < MIN_STACK_HEIGHT) continue;
            double dpx = center.x - px;
            double dpz = center.z - pz;
            if (dpx * dpx + dpz * dpz <= PLAYER_NEAR_STACK_RADIUS_SQ) return true;
        }
        return false;
    }

    // ----- physics --------------------------------------------------------

    /**
     * Check every block in the footprint of the player's bounding box, just below the foot
     * line, for collision. If any has collision the player is server-truth supported. This
     * sidesteps client-reported {@code isOnGround()} which the client can lie about during
     * the ghost-block exploit.
     */
    private boolean isStandingOnRealBlock(Player p) {
        BoundingBox bb = p.getBoundingBox();
        World w = p.getWorld();
        int yCheck = (int) Math.floor(bb.getMinY() - 0.05);
        int x1 = (int) Math.floor(bb.getMinX() + 0.01);
        int x2 = (int) Math.floor(bb.getMaxX() - 0.01);
        int z1 = (int) Math.floor(bb.getMinZ() + 0.01);
        int z2 = (int) Math.floor(bb.getMaxZ() - 0.01);
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                Block b = w.getBlockAt(x, yCheck, z);
                if (!b.isPassable()) return true;
            }
        }
        return false;
    }

    private boolean isLegitFloat(Player p) {
        if (p.isInsideVehicle()) return true;
        if (p.isGliding()) return true;
        if (p.isFlying()) return true;
        if (p.isClimbing()) return true;
        if (p.isInWater()) return true;
        if (p.isInLava()) return true;
        if (p.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        // Block-based fallbacks for things not always exposed on Player in older API levels.
        Material at = p.getLocation().getBlock().getType();
        return switch (at) {
            case BUBBLE_COLUMN, POWDER_SNOW, SCAFFOLDING, COBWEB -> true;
            default -> false;
        };
    }

    // ----- snap back ------------------------------------------------------

    private void snapBack(Player p, Claim claim) {
        Location target = chooseSafeTarget(p);
        if (target == null) {
            plugin.getLogger().warning("HoverWatchdog: no safe target found for " + p.getName()
                    + " — skipping snap-back to avoid teleporting them into the void.");
            return;
        }
        p.setFallDistance(0);
        p.setVelocity(new Vector(0, 0, 0));
        p.teleport(target);
        p.sendMessage(ChatColor.RED + "Returned to safe ground — you can't enter "
                + claims.resolveName(claim.getOwner()) + "'s zone like that.");
        // After a successful snap, drop the player's denial history so we don't immediately
        // re-trigger if they happen to still be near the recorded coordinates.
        denials.remove(p.getUniqueId());
    }

    /**
     * Pick a destination, preferring (in order):
     *  1. {@code lastSafe} — the player's most recent grounded position outside any
     *     foreign claim (validated again before use, in case world changed).
     *  2. Their respawn (bed/anchor) point.
     *  3. World spawn.
     */
    private Location chooseSafeTarget(Player p) {
        UUID id = p.getUniqueId();
        Location candidate = lastSafe.get(id);
        if (candidate != null && isSafeSpot(candidate, id)) return candidate;
        Location bed = p.getRespawnLocation();
        if (bed != null && isSafeSpot(bed, id)) return bed;
        World w = p.getWorld();
        if (w == null) return null;
        Location spawn = w.getSpawnLocation();
        return spawn; // accept world spawn even if isSafeSpot is picky — better than nothing.
    }

    /**
     * Validate a candidate teleport position: not inside a foreign claim, feet+head clear,
     * solid block beneath, and no lava at feet or below.
     */
    private boolean isSafeSpot(Location loc, UUID playerId) {
        if (loc == null || loc.getWorld() == null) return false;
        Claim c = claims.getClaimAt(loc);
        if (c != null && !c.isMember(playerId)) return false;
        World w = loc.getWorld();
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        Block ground = w.getBlockAt(x, y - 1, z);
        if (!feet.isPassable() || !head.isPassable()) return false;
        if (ground.isPassable()) return false;
        if (feet.getType() == Material.LAVA || ground.getType() == Material.LAVA) return false;
        return true;
    }

    // ----- per-player state ------------------------------------------------

    private static final class DeniedPlacement {
        final UUID worldId;
        final double x, y, z;
        final long timestamp;

        DeniedPlacement(UUID worldId, double x, double y, double z, long timestamp) {
            this.worldId = worldId;
            this.x = x; this.y = y; this.z = z;
            this.timestamp = timestamp;
        }
    }
}
