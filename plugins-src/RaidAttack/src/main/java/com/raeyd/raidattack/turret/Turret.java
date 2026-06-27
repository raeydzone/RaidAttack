package com.raeyd.raidattack.turret;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Location;

/**
 * A single deployed turret. Stores its slot (0-3, corresponds to {@code #1}–{@code #4} in
 * user-facing text), block coords, and the Citizens NPC id of its shulker entity.
 *
 * <p>The <b>level</b> lives on the parent {@link Claim} keyed by slot, NOT here — slot-level
 * persists across remove/redeploy. Look up via {@code claim.getSlotLevel(turret.getSlot())}.
 */
public final class Turret {

    /** Absolute upper bound for structure HP — used as the hard clamp in setters. */
    public static final int MAX_STRUCTURE_HP = 300;

    /**
     * Tier-dependent max HP for a freshly-spawned / respawned turret. L1=200, L2=250, L3=300.
     * Higher-tier turrets are worth more diamonds/netherite, so they deserve a stiffer wall.
     */
    public static int maxHpForLevel(int level) {
        return switch (level) {
            case 1 -> 200;
            case 2 -> 250;
            default -> 300;     // L3 and any unexpected higher value
        };
    }

    private final int slot;
    private final int x;
    private final int y;
    private final int z;
    /** Citizens NPC id of the shulker entity sitting at the layer-5 centre; -1 = unspawned. */
    private int npcId = -1;
    /**
     * Current structure HP (0..MAX). Hits 0 → turret enters its destroyed state.
     * <p>Initialised to the absolute max ({@value #MAX_STRUCTURE_HP}); the spawn / respawn
     * flow re-sets it to {@link #maxHpForLevel(int)} for the slot's current level so a fresh
     * L1 starts at 150, L2 at 175, L3 at 200.
     */
    private int structureHp = MAX_STRUCTURE_HP;
    /** Epoch ms when a destroyed turret should respawn. 0 = not currently destroyed. */
    private long respawnAtMillis = 0L;

    public Turret(int slot, int x, int y, int z) {
        this.slot = slot;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getSlot() { return slot; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getNpcId() { return npcId; }
    public void setNpcId(int npcId) { this.npcId = npcId; }

    public int getStructureHp() { return structureHp; }
    public void setStructureHp(int hp) {
        if (hp < 0) hp = 0;
        if (hp > MAX_STRUCTURE_HP) hp = MAX_STRUCTURE_HP;
        this.structureHp = hp;
    }
    public long getRespawnAtMillis() { return respawnAtMillis; }
    public void setRespawnAtMillis(long when) { this.respawnAtMillis = when; }
    public boolean isDestroyed() { return respawnAtMillis > 0L; }

    /** 3D Euclidean distance to a candidate placement location (block coords). */
    public double distanceTo(Location loc) {
        double dx = x - loc.getBlockX();
        double dy = y - loc.getBlockY();
        double dz = z - loc.getBlockZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", slot);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        if (npcId >= 0) m.put("npc", npcId);
        // Persist HP + respawn-deadline so a half-damaged turret survives a restart at its
        // current state. Stored only when non-default to keep the yml tidy.
        if (structureHp != MAX_STRUCTURE_HP) m.put("hp", structureHp);
        if (respawnAtMillis != 0L) m.put("respawn_at", respawnAtMillis);
        return m;
    }

    /**
     * @param defaultSlot used when the YAML entry predates slot-aware storage; pass the entry's
     *                    index in the list so legacy data lands at slots 0, 1, 2, … in order.
     */
    public static Turret fromMap(int defaultSlot, Map<?, ?> m) {
        int slot = m.get("slot") instanceof Number n ? n.intValue() : defaultSlot;
        int x = ((Number) m.get("x")).intValue();
        int y = ((Number) m.get("y")).intValue();
        int z = ((Number) m.get("z")).intValue();
        Turret t = new Turret(slot, x, y, z);
        if (m.get("npc") instanceof Number num) t.setNpcId(num.intValue());
        if (m.get("hp") instanceof Number hp) t.setStructureHp(hp.intValue());
        if (m.get("respawn_at") instanceof Number ra) t.setRespawnAtMillis(ra.longValue());
        return t;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
