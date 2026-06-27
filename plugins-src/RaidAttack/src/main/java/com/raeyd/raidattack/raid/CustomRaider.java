package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * One spawned raider. Wraps a Citizens player-type NPC with:
 * <ul>
 *   <li>The custom MineSkin texture from {@link RaiderSkin} (real signed skin, not a
 *       packet-faked one — works across server restarts and resource-pack-free clients).</li>
 *   <li>A red nametag of the form {@code <attacker>'s Raider}.</li>
 *   <li>Tuned attributes: HP 25, attack damage 6, movement between walk &amp; sprint (≈0.115 on
 *       the attribute scale).</li>
 *   <li>Vanilla AI disabled — every per-tick decision (target, path, swing) is driven by
 *       {@code RaiderAI} in later phases. The NPC's brain is empty.</li>
 *   <li>An iron sword in the main hand so the natural player swing-arm animation renders when
 *       the AI calls {@link Player#swingMainHand()} on attack.</li>
 * </ul>
 *
 * <p>This class only owns the entity lifecycle (spawn/despawn) and the configured stats; the
 * combat loop and pathing live elsewhere. That keeps Citizens-API concerns out of the AI tick
 * and lets the same wrapper be reused by both the test command and the real raid spawn engine.
 */
public final class CustomRaider {

    /** Attribute scale: 0.10 = vanilla walk, 0.13 = sprint. 0.115 sits between. */
    public static final double MOVEMENT_SPEED = 0.115;
    public static final double MAX_HEALTH      = 25.0;
    /** Damage per swing — applied manually via target.damage() in the AI tick so we don't
     *  fight vanilla's "weapon adds modifier on top of attribute" math. */
    public static final double ATTACK_DAMAGE   = 6.0;
    /** Block-distance the AI considers an in-range hit (matches the vanilla sword reach of 3). */
    public static final double ATTACK_RANGE_BLOCKS = 3.0;

    private final HomeSystemPlugin plugin;
    private final String attackerName;
    /** Raid this raider belongs to, or {@code null} for a free-standing test spawn. */
    private final UUID raidId;
    private NPC npc;

    public CustomRaider(HomeSystemPlugin plugin, String attackerName, UUID raidId) {
        this.plugin = plugin;
        this.attackerName = attackerName;
        this.raidId = raidId;
    }

    /**
     * Spawn the raider at {@code at}. Returns true on success. False if Citizens is missing,
     * the world is null, or the underlying entity didn't come up (Citizens reload mid-tick,
     * etc.). A failed spawn does not allocate anything — safe to retry.
     */
    public boolean spawn(Location at) {
        if (at == null || at.getWorld() == null) return false;
        if (npc != null) return false;            // already spawned
        try {
            // Plain ASCII name — no legacy colour code. Paper 26.1 rejects '§' in chat
            // components, and the vanilla death-message encoder pulls in the entity's name
            // verbatim when the NPC dies → a name containing '§' throws inside die() before
            // EntityDeathEvent fires, which means our death listener never runs and the
            // kill counter stays stuck at 100%. Red rendering is handled by a Citizens
            // hologram label instead of scoreboard teams.
            String displayName = attackerName + "'s Raider";
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, displayName);
            // Apply the signed MineSkin texture BEFORE spawning so the entity goes out to
            // clients with the right textures property in its profile from frame one. Setting
            // it post-spawn works but produces a one-frame Steve flicker for nearby viewers.
            SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
            skin.setSkinPersistent(RaiderSkin.NAME, RaiderSkin.SIGNATURE, RaiderSkin.VALUE);

            // Citizens 2.0.42 setProtected(true) blocks ALL damage on player-type NPCs,
            // including player-attack damage and knockback. That made raiders unkillable
            // (the user's "literally a deathskin standing unkillable" symptom). Keep them
            // vulnerable to normal damage — they'll take fall/lava in edge cases but the
            // spawn picker uses highestBlockYAt+1 so the perimeter-band spawn point is
            // always on solid ground.
            npc.setProtected(false);
            npc.setUseMinecraftAI(false);     // no vanilla goals; the AI tick drives everything
            npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false);
            // Keep the raider's chunks loaded so the AI tick continues even when no real
            // player is in render range. Without this, a raider that wanders into an
            // unloaded chunk (no one nearby) effectively freezes — Citizens stops ticking it
            // and our AI loop has nothing to update.
            npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, true);
            // Setting this stamps the raid id onto the NPC for later cleanup queries. Survives
            // the Citizens persistence layer because we never call npc.data().setPersistent —
            // we want the data to die with the entity, not be re-loaded post-restart.
            if (raidId != null) {
                npc.data().set("raid-id", raidId.toString());
            }
            // Tag the role so the wider system can distinguish raiders from ravagers from
            // turret NPCs by data-key only (no name-substring sniffing).
            npc.data().set("raid-role", "raider");

            npc.spawn(at);
            if (!(npc.getEntity() instanceof Player p) || !p.isValid()) {
                // Spawn went up but the entity is wrong — destroy so we don't leak a registry entry.
                try { npc.destroy(); } catch (Throwable ignored) {}
                npc = null;
                return false;
            }
            applyRedNameLabel(displayName);
            // Schedule attribute application 5 ticks after spawn. Citizens initializes the
            // player entity's attribute map on a delayed tick, and any base-value we set
            // synchronously here gets overwritten by Citizens' defaults right after. Holding
            // off 5 ticks lets Citizens finish, then our values stick.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isValid() || p.isDead()) return;
                applyAttributes(p);
                // Disable natural regen — at full food (default 20 saturation) a player
                // entity heals ~1 HP per 0.5s, which is what made raiders "tank" so many
                // sword hits before dying. Food = 0 puts them below the regen threshold.
                p.setFoodLevel(0);
                p.setSaturation(0f);
                // Set health to attribute max (so they don't sit at vanilla-default 20).
                double max = p.getAttribute(Attribute.MAX_HEALTH).getBaseValue();
                p.setHealth(Math.min(max, max));
            }, 5L);
            p.getInventory().setItem(EquipmentSlot.HAND, new ItemStack(Material.IRON_SWORD));
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to spawn raider for '" + attackerName
                    + "' at " + fmt(at) + ": " + t.getMessage());
            if (npc != null) {
                try { npc.destroy(); } catch (Throwable ignored) {}
                npc = null;
            }
            return false;
        }
    }

    /** Tear down the underlying NPC. Idempotent. */
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

    /**
     * Stamp the tuned stats onto the freshly-spawned player entity. We zero out vanilla's
     * default 20 HP attribute and replace it with our 25, then set health to full. Attack
     * damage is set on the attribute too even though player-type entities don't auto-use it —
     * the AI tick reads {@link #ATTACK_DAMAGE} directly, but mirroring it onto the attribute
     * makes the value visible to anything that inspects the entity (e.g. /attribute commands).
     */
    private static void applyAttributes(Player p) {
        setBase(p, Attribute.MAX_HEALTH, MAX_HEALTH);
        setBase(p, Attribute.MOVEMENT_SPEED, MOVEMENT_SPEED);
        setBase(p, Attribute.ATTACK_DAMAGE, ATTACK_DAMAGE);
        // Normal player knockback resistance = 0 (no extra). Leave KNOCKBACK_RESISTANCE
        // untouched; vanilla defaults to 0 which is what the spec asks for.
        p.setHealth(Math.min(MAX_HEALTH, p.getAttribute(Attribute.MAX_HEALTH).getBaseValue()));
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
            hologram.setLineHeight(0.28);
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to apply red raider name label: " + t.getMessage());
        }
    }

    private static String fmt(Location l) {
        return String.format("(%s %.1f,%.1f,%.1f)",
                l.getWorld() == null ? "?" : l.getWorld().getName(),
                l.getX(), l.getY(), l.getZ());
    }
}
