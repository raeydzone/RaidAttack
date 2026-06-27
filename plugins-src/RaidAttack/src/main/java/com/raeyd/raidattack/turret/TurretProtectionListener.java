package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Locks down the volume around every deployed turret. Two rules, applied universally — owner,
 * friends, outsiders, and the owner's own creative-mode test characters:
 *
 * <ol>
 *   <li><b>Indestructible.</b> No block break, explosion, fire, or piston-pull can damage any
 *       block in the turret's protected volume. The structure uses rare materials (gilded
 *       blackstone, glowstone) and must not be siphoned.</li>
 *   <li><b>No build.</b> No block placement, bucket emptying, fluid flow, ignition, or piston
 *       push into the protected volume. This prevents anyone (including the owner) from boxing
 *       the turret in or otherwise sabotaging its line of fire.</li>
 * </ol>
 *
 * <p>Protected volume per turret: 7×7 XZ footprint centred on the anchor (one block wider than the
 * structure on every side — see {@link TurretStructure#PROTECTION_RADIUS}), spanning the
 * <b>entire vertical column</b> from world bottom to world top. The full-height column is what
 * closes the lava-moat exploit: a defender can no longer place lava (or remove the floor) under or
 * beside the turret to drown raiders that path/teleport in — the whole 7×7 shaft, at every Y, is
 * sealed against block break, placement, bucket, fluid flow, explosion, and piston intrusion.
 */
public final class TurretProtectionListener implements Listener {

    // Protect one block wider than the structure on every side (7×7) — see TurretStructure
    // .PROTECTION_RADIUS. The extra ring can't be walled or mined, and is cleared to air on deploy,
    // so there's always a stand-on-able touch-cell in front of the turret on all four sides.
    private static final int HALF_FOOTPRINT = TurretStructure.PROTECTION_RADIUS;
    private static final long DENY_MESSAGE_COOLDOWN_MS = 2000L;

    private final HomeSystemPlugin plugin;
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();

    public TurretProtectionListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    // -- block break / place (player-driven) ----------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (inTurretZone(e.getBlock().getLocation())) {
            e.setCancelled(true);
            denyMessage(e.getPlayer(), "break turret blocks");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (inTurretZone(e.getBlockPlaced().getLocation())) {
            e.setCancelled(true);
            denyMessage(e.getPlayer(), "place blocks inside a turret zone");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        if (inTurretZone(target.getLocation())) {
            e.setCancelled(true);
            denyMessage(e.getPlayer(), "pour fluid into a turret zone");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (inTurretZone(e.getBlockClicked().getLocation())) {
            e.setCancelled(true);
            denyMessage(e.getPlayer(), "scoop fluid from a turret zone");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (inTurretZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    // -- explosions (TNT, creepers, end-crystals, etc.) -----------------------

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> inTurretZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> inTurretZone(b.getLocation()));
    }

    // -- fluid flow into the zone --------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        if (inTurretZone(e.getToBlock().getLocation())) e.setCancelled(true);
    }

    // -- pistons can't cross into / out of the zone --------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (inTurretZone(b.getLocation())
                    || inTurretZone(b.getRelative(e.getDirection()).getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (inTurretZone(b.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // -- entities that mutate blocks (Endermen, falling sand, etc.) ----------

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (inTurretZone(e.getBlock().getLocation())) e.setCancelled(true);
    }

    // -- zone test ------------------------------------------------------------

    /**
     * True if {@code loc} falls inside any deployed turret's protected volume in the same world.
     * O(claims × turrets-per-claim) per call — at current scale (≤ a few dozen turrets total)
     * a linear scan is cheaper than maintaining a spatial index.
     */
    private boolean inTurretZone(Location loc) {
        if (loc.getWorld() == null) return false;
        UUID worldId = loc.getWorld().getUID();
        int x = loc.getBlockX(), z = loc.getBlockZ();
        for (Claim claim : plugin.getClaimManager().all().values()) {
            if (!claim.getWorldId().equals(worldId)) continue;
            for (Turret t : claim.getTurrets()) {
                int dx = x - t.getX();
                int dz = z - t.getZ();
                // Full vertical column: any Y in the 7×7 footprint is protected (anti lava-moat).
                if (dx >= -HALF_FOOTPRINT && dx <= HALF_FOOTPRINT
                        && dz >= -HALF_FOOTPRINT && dz <= HALF_FOOTPRINT) {
                    return true;
                }
            }
        }
        return false;
    }

    private void denyMessage(Player p, String actionVerb) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(p.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        lastDenyMessage.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.RED + "You can't " + actionVerb + " — turrets are sealed.");
    }
}
