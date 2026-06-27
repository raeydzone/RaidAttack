package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.HologramTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Ravager;

/**
 * One spawned ravager. Citizens-managed ravager entity with vanilla AI fully disabled — the
 * raid AI tick drives every decision (target, path, attack). The native ravager model and
 * sounds are kept (head-swing animation, ravager hurt/roar audio) because that's what gives
 * the boss-tier raider its visual identity; we just override the brain.
 *
 * <p>Parallel API to {@link CustomRaider} so the spawn engine and AI loop can treat both
 * NPC types through the same lifecycle calls. The two classes differ only in entity type,
 * skin (ravager has none), and attribute values.
 */
public final class CustomRavager {

    public static final double MAX_HEALTH        = 100.0;
    public static final double MOVEMENT_SPEED    = 0.4;
    /** 0.75 = 75% incoming-knockback resistance per the attribute scale (1.0 = immune). */
    public static final double KNOCKBACK_RESIST  = 0.75;
    public static final double ATTACK_DAMAGE     = 18.0;
    public static final double ATTACK_RANGE_BLOCKS = 3.0;
    /** Vanilla iron sword swings every 12.5 ticks; "3× slower" = ~38 ticks between hits. */
    public static final int    ATTACK_COOLDOWN_TICKS = 76;

    private final HomeSystemPlugin plugin;
    private final String attackerName;
    private final UUID raidId;
    private NPC npc;

    public CustomRavager(HomeSystemPlugin plugin, String attackerName, UUID raidId) {
        this.plugin = plugin;
        this.attackerName = attackerName;
        this.raidId = raidId;
    }

    public boolean spawn(Location at) {
        if (at == null || at.getWorld() == null) return false;
        if (npc != null) return false;
        try {
            // Plain ASCII name — Paper 26.1 rejects '§' in chat components and the vanilla
            // death-message encoder consumes the entity name verbatim. Red rendering is handled
            // by a Citizens hologram label instead of scoreboard teams.
            String displayName = attackerName + "'s Ravager";
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.RAVAGER, displayName);
            // Same rationale as CustomRaider — setProtected(true) blocks player damage.
            // Ravager needs to be vulnerable to sword hits + turret bullets to be killable.
            npc.setProtected(false);
            npc.setUseMinecraftAI(false);
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            // Same chunk-keepalive rationale as CustomRaider — without this the AI tick goes
            // dormant for any ravager that wanders outside a player's render range.
            npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, true);
            if (raidId != null) {
                npc.data().set("raid-id", raidId.toString());
            }
            npc.data().set("raid-role", "ravager");

            npc.spawn(at);
            if (!(npc.getEntity() instanceof Ravager r) || !r.isValid()) {
                try { npc.destroy(); } catch (Throwable ignored) {}
                npc = null;
                return false;
            }
            applyRedNameLabel(displayName);
            // Delay attribute apply 5 ticks — Citizens initialises the entity's attribute map
            // on a delayed tick, so any base-value we set synchronously gets clobbered. Holding
            // off lets Citizens settle, then our values stick.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!r.isValid() || r.isDead()) return;
                applyAttributes(r);
            }, 5L);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to spawn ravager for '" + attackerName
                    + "' at " + fmt(at) + ": " + t.getMessage());
            if (npc != null) {
                try { npc.destroy(); } catch (Throwable ignored) {}
                npc = null;
            }
            return false;
        }
    }

    public void despawn() {
        if (npc == null) return;
        try {
            if (npc.hasTrait(HologramTrait.class)) npc.getOrAddTrait(HologramTrait.class).clear();
        } catch (Throwable ignored) {}
        try { npc.destroy(); } catch (Throwable ignored) {}
        npc = null;
    }

    public NPC getNpc() { return npc; }

    public LivingEntity getEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        return npc.getEntity() instanceof LivingEntity le ? le : null;
    }

    public boolean isAlive() {
        LivingEntity le = getEntity();
        return le != null && le.isValid() && !le.isDead();
    }

    public UUID getRaidId() { return raidId; }
    public String getAttackerName() { return attackerName; }

    // -- internals ------------------------------------------------------------

    private static void applyAttributes(Ravager r) {
        setBase(r, Attribute.MAX_HEALTH, MAX_HEALTH);
        setBase(r, Attribute.MOVEMENT_SPEED, MOVEMENT_SPEED);
        setBase(r, Attribute.ATTACK_DAMAGE, ATTACK_DAMAGE);
        setBase(r, Attribute.KNOCKBACK_RESISTANCE, KNOCKBACK_RESIST);
        r.setHealth(Math.min(MAX_HEALTH, r.getAttribute(Attribute.MAX_HEALTH).getBaseValue()));
    }

    private static void setBase(LivingEntity e, Attribute attr, double v) {
        var inst = e.getAttribute(attr);
        if (inst != null) inst.setBaseValue(v);
    }

    private void applyRedNameLabel(String displayName) {
        try {
            HologramTrait hologram = npc.getOrAddTrait(HologramTrait.class);
            hologram.clear();
            hologram.addLine(ChatColor.RED + displayName);
            hologram.setLineHeight(0.34);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to apply red ravager name label: " + t.getMessage());
        }
    }

    private static String fmt(Location l) {
        return String.format("(%s %.1f,%.1f,%.1f)",
                l.getWorld() == null ? "?" : l.getWorld().getName(),
                l.getX(), l.getY(), l.getZ());
    }
}
