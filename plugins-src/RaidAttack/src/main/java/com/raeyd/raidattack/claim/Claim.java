package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.turret.Turret;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A single claim. The claim is a rectangle on the XZ plane defined by two opposite corners
 * (min and max, both inclusive). Y is unbounded — the claim covers the full world height.
 *
 * <p>Turrets are stored slot-based: a fixed array of {@link #MAX_TURRETS} slots, plus a parallel
 * {@code int[]} of per-slot levels that persists even when the slot is empty. So upgrading
 * turret #1 to level 3, removing it, and re-deploying re-spawns the new turret at level 3.
 */
public final class Claim {

    public static final int MAX_TURRETS = 4;
    public static final int MAX_TURRET_LEVEL = 3;

    private final UUID owner;
    private final UUID worldId;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final Set<UUID> friends;
    /** Per-slot level (1..MAX_TURRET_LEVEL). Defaults to 1; persists across remove/redeploy. */
    private final int[] turretLevels = new int[MAX_TURRETS];
    /** Per-slot deployed turret (or {@code null} if the slot is empty). */
    private final Turret[] turretSlots = new Turret[MAX_TURRETS];
    /** Per-slot respawn-deadline (epoch ms). Non-zero means the slot is in destruction-downtime
     *  even if the slot has been emptied via {@code /HomeSystem turret remove}. Survives
     *  remove+redeploy so the player can't bypass the 5-minute downtime by /turret remove +
     *  /turret deploy. Cleared once a fresh turret successfully respawns. */
    private final long[] slotRespawnAt = new long[MAX_TURRETS];
    /** Per-base toggle: when true, this claim's turrets fall through to hostile mobs (zombies,
     *  skeletons, …) once no enemy player is in range; when false they only target enemy players.
     *  Owner sets it via {@code /HomeSystem turret attackmobs <on|off>}. Defaults to true. */
    private boolean attackHostileMobs = true;

    public Claim(UUID owner, UUID worldId, int minX, int minZ, int maxX, int maxZ, Set<UUID> friends) {
        this(owner, worldId, minX, minZ, maxX, maxZ, friends, new int[]{1, 1, 1, 1}, new ArrayList<>());
    }

    public Claim(UUID owner, UUID worldId, int minX, int minZ, int maxX, int maxZ,
                 Set<UUID> friends, int[] turretLevels, List<Turret> turrets) {
        this.owner = owner;
        this.worldId = worldId;
        this.minX = Math.min(minX, maxX);
        this.maxX = Math.max(minX, maxX);
        this.minZ = Math.min(minZ, maxZ);
        this.maxZ = Math.max(minZ, maxZ);
        this.friends = new HashSet<>(friends);
        for (int i = 0; i < MAX_TURRETS; i++) {
            int lvl = i < turretLevels.length ? turretLevels[i] : 1;
            this.turretLevels[i] = clampLevel(lvl);
        }
        for (Turret t : turrets) {
            if (t.getSlot() >= 0 && t.getSlot() < MAX_TURRETS) this.turretSlots[t.getSlot()] = t;
        }
    }

    private static int clampLevel(int lvl) {
        if (lvl < 1) return 1;
        if (lvl > MAX_TURRET_LEVEL) return MAX_TURRET_LEVEL;
        return lvl;
    }

    public UUID getOwner() { return owner; }
    public UUID getWorldId() { return worldId; }
    public int getMinX() { return minX; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxZ() { return maxZ; }
    public Set<UUID> getFriends() { return friends; }

    /** Per-base: do this claim's turrets target hostile mobs when no enemy player is in range? */
    public boolean attacksHostileMobs() { return attackHostileMobs; }
    public void setAttacksHostileMobs(boolean v) { this.attackHostileMobs = v; }

    public int sizeX() { return maxX - minX + 1; }
    public int sizeZ() { return maxZ - minZ + 1; }

    public boolean isMember(UUID playerId) {
        return owner.equals(playerId) || friends.contains(playerId);
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !loc.getWorld().getUID().equals(worldId)) return false;
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean overlaps(Claim other) {
        if (!worldId.equals(other.worldId)) return false;
        return !(maxX < other.minX || other.maxX < minX || maxZ < other.minZ || other.maxZ < minZ);
    }

    public boolean overlaps(UUID world, int oMinX, int oMinZ, int oMaxX, int oMaxZ) {
        if (!worldId.equals(world)) return false;
        return !(maxX < oMinX || oMaxX < minX || maxZ < oMinZ || oMaxZ < minZ);
    }

    // -- turret slots ---------------------------------------------------------

    public int getSlotLevel(int slot) { return turretLevels[slot]; }
    public void setSlotLevel(int slot, int level) { turretLevels[slot] = clampLevel(level); }

    /** Snapshot of the per-slot levels. Used to persist upgrades across unclaim/reclaim. */
    public int[] getSlotLevels() { return turretLevels.clone(); }
    /** Overwrite the per-slot levels (each clamped). Used when restoring a saved snapshot. */
    public void setSlotLevels(int[] levels) {
        if (levels == null) return;
        for (int i = 0; i < MAX_TURRETS && i < levels.length; i++) {
            turretLevels[i] = clampLevel(levels[i]);
        }
    }

    public Turret getSlotTurret(int slot) { return turretSlots[slot]; }

    /** Epoch-ms deadline before slot {@code slot} can host an active turret again. 0 = no
     *  downtime, slot is free. Used to gate /turret deploy on a slot whose previous turret
     *  was destroyed and is still in its respawn window. */
    public long getSlotRespawnAt(int slot) { return slotRespawnAt[slot]; }
    public void setSlotRespawnAt(int slot, long when) { slotRespawnAt[slot] = when; }
    /** Is slot {@code slot} currently in respawn-downtime? */
    public boolean isSlotInDowntime(int slot) {
        return slotRespawnAt[slot] > System.currentTimeMillis();
    }

    /** Find the lowest empty slot index, or -1 if all 4 are occupied. */
    public int firstEmptySlot() {
        for (int i = 0; i < MAX_TURRETS; i++) if (turretSlots[i] == null) return i;
        return -1;
    }

    public int countDeployed() {
        int c = 0;
        for (Turret t : turretSlots) if (t != null) c++;
        return c;
    }

    /** Deploy a turret into its declared slot. Caller is responsible for picking a free one. */
    public void deployTurret(Turret t) {
        turretSlots[t.getSlot()] = t;
    }

    /**
     * Clear the turret at the given 1-based slot number. Returns the removed turret or null.
     * The slot's level is preserved.
     */
    public Turret undeploySlot(int oneBasedSlot) {
        int i = oneBasedSlot - 1;
        if (i < 0 || i >= MAX_TURRETS) return null;
        Turret t = turretSlots[i];
        turretSlots[i] = null;
        return t;
    }

    /**
     * Empty every turret slot. Slot LEVELS are untouched — they survive (and are then snapshotted
     * by {@link ClaimManager#removeClaim(UUID)} into {@code savedSlotLevels} for re-application on
     * the owner's next {@code /HomeSystem claim}). Coordinates and NPC ids are dropped.
     *
     * <p>Called from {@code removeClaimWithCleanup} as a final defensive wipe so that if any
     * stale reference to this {@code Claim} object somehow ends up driving a {@code save()}
     * later, no zombie turret data leaks back into {@code claims.yml}.
     */
    public void clearTurretSlots() {
        for (int i = 0; i < MAX_TURRETS; i++) turretSlots[i] = null;
    }

    /** All currently-deployed turrets, in slot order. Empty slots are skipped. */
    public List<Turret> getTurrets() {
        List<Turret> out = new ArrayList<>();
        for (Turret t : turretSlots) if (t != null) out.add(t);
        return out;
    }

    // -- persistence ----------------------------------------------------------

    public void serialize(ConfigurationSection section) {
        section.set("world", worldId.toString());
        section.set("minX", minX);
        section.set("minZ", minZ);
        section.set("maxX", maxX);
        section.set("maxZ", maxZ);
        section.set("friends", friends.stream().map(UUID::toString).toList());
        section.set("attack_hostile_mobs", attackHostileMobs);
        section.set("turret_levels", Arrays.asList(
                turretLevels[0], turretLevels[1], turretLevels[2], turretLevels[3]));
        // Per-slot respawn deadlines. Only persisted when at least one slot is in downtime so
        // the yml stays clean for the common case.
        boolean anyDowntime = false;
        for (long v : slotRespawnAt) if (v != 0L) { anyDowntime = true; break; }
        if (anyDowntime) {
            section.set("slot_respawn_at", Arrays.asList(
                    slotRespawnAt[0], slotRespawnAt[1], slotRespawnAt[2], slotRespawnAt[3]));
        }
        List<Map<String, Object>> turrets = new ArrayList<>();
        for (Turret t : turretSlots) if (t != null) turrets.add(t.toMap());
        section.set("turrets", turrets);
    }

    public static Claim deserialize(UUID owner, ConfigurationSection section) {
        UUID world = UUID.fromString(section.getString("world"));
        int minX = section.getInt("minX");
        int minZ = section.getInt("minZ");
        int maxX = section.getInt("maxX");
        int maxZ = section.getInt("maxZ");
        Set<UUID> friends = new HashSet<>();
        for (String s : section.getStringList("friends")) {
            try { friends.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        // turret_levels: list of ints, default [1,1,1,1] if absent (covers pre-feature data).
        int[] levels = {1, 1, 1, 1};
        List<Integer> raw = section.getIntegerList("turret_levels");
        for (int i = 0; i < Math.min(raw.size(), MAX_TURRETS); i++) levels[i] = raw.get(i);
        // Turrets — assign sequential slots to legacy entries that lack a "slot:" field.
        List<Turret> turrets = new ArrayList<>();
        int autoSlot = 0;
        for (Object o : section.getMapList("turrets")) {
            if (!(o instanceof Map<?, ?> m)) continue;
            try {
                turrets.add(Turret.fromMap(autoSlot, m));
            } catch (RuntimeException ignored) {}
            autoSlot++;
        }
        Claim claim = new Claim(owner, world, minX, minZ, maxX, maxZ, friends, levels, turrets);
        // Per-base turret-vs-mobs toggle; defaults to true for pre-feature claims.
        claim.setAttacksHostileMobs(section.getBoolean("attack_hostile_mobs", true));
        // Restore per-slot respawn deadlines if present.
        if (section.contains("slot_respawn_at")) {
            List<Long> respawnRaw = section.getLongList("slot_respawn_at");
            for (int i = 0; i < Math.min(respawnRaw.size(), MAX_TURRETS); i++) {
                claim.setSlotRespawnAt(i, respawnRaw.get(i));
            }
        }
        return claim;
    }
}
