package com.raeyd.raidattack.turret;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Schematic + place/clear logic for the placeholder turret build. The actual turret entity
 * doesn't exist yet — this is just the cosmetic structure marking the slot.
 *
 * <p>Anchor convention: the {@link Turret}'s stored (x, y, z) is the <b>ground block</b> the
 * player was standing on at deploy time. Layer 1 of the structure replaces that block, so the
 * player ends up standing on top of layer 1 after deployment (they had to leave the footprint
 * first — see {@link TurretPlacementQueue}).
 *
 * <p>Layout (dy above anchor; center XZ = anchor XZ):
 * <pre>
 * dy=0  layer 1: 5×5 Blackstone (each block: 20% chance Gilded Blackstone)
 * dy=1  layer 2: 5×5 border + center  →  17 Polished Blackstone Walls
 * dy=2  layer 3: center only          →  1 wall
 * dy=3  layer 4: plus sign            →  5 walls   (.#. / ### / .#.)
 * dy=4  layer 5: inner corners + center shulker → 4 walls + 1 purple shulker
 * dy=5  layer 6: inner corners        →  4 walls   (#.# / ... / #.#)
 * dy=6  layer 7: inner 3×3, center glowstone → 8 walls + 1 glowstone
 * </pre>
 */
public final class TurretStructure {

    /** Probability that a given Layer-1 block becomes Gilded Blackstone instead of Blackstone. */
    private static final double GILDED_CHANCE = 0.20;

    /** Horizontal radius (from anchor center) of the structure's footprint, in blocks. */
    public static final int FOOTPRINT_RADIUS = 2;   // 5×5 footprint
    /**
     * Horizontal radius of the <b>protected zone</b> — one block wider than the structure on every
     * side (7×7). The extra ring is locked down (indestructible / no-build) and cleared to air on
     * deploy, guaranteeing a stand-on-able touch-cell immediately in front of the turret on all
     * four sides. That ring is what the anti-wall fallback teleports besieging raiders onto.
     */
    public static final int PROTECTION_RADIUS = FOOTPRINT_RADIUS + 1;   // 7×7 protected footprint
    /** Vertical height (above the layer-1 ground) the structure occupies, in blocks. */
    public static final int HEIGHT = 7;             // layers 1..7
    /** Additional no-build airspace above the structure top (so nobody caps the turret). */
    public static final int AIRSPACE_ABOVE = 15;
    /** Inclusive top dy of the full protected zone relative to anchor (structure + airspace). */
    public static final int ZONE_MAX_DY = HEIGHT - 1 + AIRSPACE_ABOVE;   // 21

    private TurretStructure() {}

    /**
     * Place every block of the structure at the turret's anchor in the given world. Idempotent —
     * re-running re-randomises the Gilded-Blackstone scattering on layer 1 but otherwise produces
     * the same shape. Block updates fire so walls auto-connect.
     */
    public static void place(World world, Turret t) {
        for (Pos p : positionsForPlace()) {
            Material mat = p.material;
            if (mat == Material.BLACKSTONE && ThreadLocalRandom.current().nextDouble() < GILDED_CHANCE) {
                mat = Material.GILDED_BLACKSTONE;
            }
            Block b = world.getBlockAt(t.getX() + p.dx, t.getY() + p.dy, t.getZ() + p.dz);
            b.setType(mat, true);
        }
    }

    /**
     * Clear every position the schematic would occupy back to air. We iterate the same
     * deterministic position list — we do NOT clear the entire 5×5×7 bounding box, so any block
     * the owner added inside their own footprint (unlikely but possible during the deferred
     * placement window) survives.
     */
    public static void clear(World world, Turret t) {
        for (Pos p : positionsForPlace()) {
            Block b = world.getBlockAt(t.getX() + p.dx, t.getY() + p.dy, t.getZ() + p.dz);
            b.setType(Material.AIR, true);
        }
    }

    /**
     * Clear a turret-shaped block area at the given anchor (same coordinates as a {@link Turret})
     * regardless of whether a {@link Turret} object exists for it. Used by the orphan scanner
     * to raze leftover structures from dead claims.
     */
    public static void clearAt(World world, int anchorX, int anchorY, int anchorZ) {
        for (Pos p : positionsForPlace()) {
            Block b = world.getBlockAt(anchorX + p.dx, anchorY + p.dy, anchorZ + p.dz);
            b.setType(Material.AIR, true);
        }
    }

    /**
     * Clear the entire protected volume to air just before the structure is placed, so nothing
     * (terrain on a hillside, a defender's pre-built wall, leaves, etc.) blocks the turret's line
     * of fire or the approach ring around it. Footprint is the full {@link #PROTECTION_RADIUS}
     * (7×7); height spans the whole protected zone (anchor.Y up to anchor.Y + {@link #ZONE_MAX_DY}).
     * Ground <i>below</i> the anchor is untouched, so the turret keeps a floor and raiders keep
     * solid ground to stand on around the base. Physics updates are suppressed — the protection
     * listener already cancels fluid/falling-block intrusion into the zone, so the shaft stays open.
     */
    public static void clearProtectionVolume(World world, Turret t) {
        clearProtectionVolume(world, t.getX(), t.getY(), t.getZ());
    }

    public static void clearProtectionVolume(World world, int anchorX, int anchorY, int anchorZ) {
        for (int dx = -PROTECTION_RADIUS; dx <= PROTECTION_RADIUS; dx++) {
            for (int dz = -PROTECTION_RADIUS; dz <= PROTECTION_RADIUS; dz++) {
                for (int dy = 0; dy <= ZONE_MAX_DY; dy++) {
                    Block b = world.getBlockAt(anchorX + dx, anchorY + dy, anchorZ + dz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
            }
        }
    }

    /**
     * Heuristic: does the cell at ({@code x, y, z}) look like the anchor (layer-1 centre) of a
     * deployed turret? Checks layer-1 centre is blackstone-ish AND layer-7 centre is glowstone.
     * Two distinct signature blocks — false-positive rate is essentially zero in normal terrain.
     */
    public static boolean looksLikeTurretAnchor(World world, int x, int y, int z) {
        Material layer1 = world.getBlockAt(x, y, z).getType();
        if (layer1 != Material.BLACKSTONE && layer1 != Material.GILDED_BLACKSTONE) return false;
        Material layer7Centre = world.getBlockAt(x, y + 6, z).getType();
        return layer7Centre == Material.GLOWSTONE;
    }

    /** A single (relative offset, target material) tuple. */
    private record Pos(int dx, int dy, int dz, Material material) {}

    /** Build the schematic as a flat list of positions. Order is layer 1 → layer 7. */
    private static List<Pos> positionsForPlace() {
        List<Pos> out = new ArrayList<>();

        // Layer 1 (dy=0): 5×5 Blackstone (Gilded is randomised at place-time, see place()).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                out.add(new Pos(dx, 0, dz, Material.BLACKSTONE));
            }
        }

        // Layer 2 (dy=1): outer 5×5 ring + center.
        Material wall = Material.POLISHED_BLACKSTONE_WALL;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean border = (Math.abs(dx) == 2 || Math.abs(dz) == 2);
                boolean center = (dx == 0 && dz == 0);
                if (border || center) out.add(new Pos(dx, 1, dz, wall));
            }
        }

        // Layer 3 (dy=2): single center wall.
        out.add(new Pos(0, 2, 0, wall));

        // Layer 4 (dy=3): plus sign — center + 4 cardinals.
        out.add(new Pos(0, 3, 0, wall));
        out.add(new Pos(1, 3, 0, wall));
        out.add(new Pos(-1, 3, 0, wall));
        out.add(new Pos(0, 3, 1, wall));
        out.add(new Pos(0, 3, -1, wall));

        // Layer 5 (dy=4): 4 inner corners + center (kept as AIR — a live Shulker NPC sits here,
        // managed by TurretEntityManager; the AIR entry ensures any stray block is cleared).
        out.add(new Pos(1, 4, 1, wall));
        out.add(new Pos(-1, 4, 1, wall));
        out.add(new Pos(1, 4, -1, wall));
        out.add(new Pos(-1, 4, -1, wall));
        out.add(new Pos(0, 4, 0, Material.AIR));

        // Layer 6 (dy=5): 4 inner corners.
        out.add(new Pos(1, 5, 1, wall));
        out.add(new Pos(-1, 5, 1, wall));
        out.add(new Pos(1, 5, -1, wall));
        out.add(new Pos(-1, 5, -1, wall));

        // Layer 7 (dy=6): inner 3×3, center glowstone, 8 surrounding walls.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Material m = (dx == 0 && dz == 0) ? Material.GLOWSTONE : wall;
                out.add(new Pos(dx, 6, dz, m));
            }
        }

        return out;
    }
}
