package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.quest.Quest;
import com.raeyd.raidattack.quest.QuestManager;
import com.raeyd.raidattack.raid.ActiveRaid;
import com.raeyd.raidattack.raid.CustomRaider;
import com.raeyd.raidattack.raid.CustomRavager;
import com.raeyd.raidattack.raid.RaidEntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Death-message attribution + uniform styling for the whole server.
 *
 * <p><b>Turret kills.</b> When a player dies from damage attributed to a turret's shulker NPC
 * (passed as the damager in {@link TurretCombatManager#explodeOn}), this listener rewrites the
 * death message to {@code "<attacker>'s Raider was eliminated by Turret #N (<owner>)"} instead of
 * vanilla's generic "killed by Shulker". The non-player ravager path is broadcast manually.
 *
 * <p><b>Uniform styling for every other death.</b> Per the user's spec, ALL deaths follow one
 * colour scheme — <span style="color:red">victim</span> in RED, the description in GRAY, and the
 * <span style="color:green">attacker</span> in GREEN. We keep vanilla's exact phrasing (so a
 * zombie kill still reads "was slain by", a fall still reads "fell from a high place") by
 * recolouring the death message's {@link TranslatableComponent} arguments in place. Name
 * arguments are also re-resolved through {@link #resolveEntityLabel} so a Citizens raider never
 * leaks its {@code "CIT-<id>"} registry name into a kill line.
 */
public final class TurretKillListener implements Listener {

    private final HomeSystemPlugin plugin;

    public TurretKillListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Player-type victims — this is the path our <b>raiders</b> take (they're Citizens
     * player-type NPCs, so a kill fires {@link PlayerDeathEvent}). We rewrite the death message
     * to {@code "<attacker>'s Raider was eliminated by Turret #N (owner)"}. The victim label is
     * resolved from raid state rather than {@code victim.getName()} — Citizens periodically
     * reverts an NPC's registry name to its default {@code "CIT-<id>"} form, which is exactly the
     * leak the user reported ({@code "CIT-18501aff9995 was eliminated by Turret #1"}).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();

        // Turret kill → our custom phrasing (RED victim / GRAY description / GREEN attacker).
        TurretKill kill = resolveTurretKill(victim);
        if (kill != null) {
            // Quest credit for the turret owner: our player-type raiders feed the 500-kill counter;
            // a genuine (non-NPC) player feeds the "kill a hostile player" quest.
            QuestManager q = plugin.getQuests();
            if (q != null && kill.ownerId != null) {
                RaidEntityManager rem = plugin.getRaidEntities();
                boolean isRaiderNpc = rem != null && rem.getRaider(victim.getUniqueId()) != null;
                if (isRaiderNpc) {
                    q.add(kill.ownerId, Quest.RAIDER_KILLS, 1);
                } else if (!CitizensAPI.getNPCRegistry().isNPC(victim)) {
                    q.complete(kill.ownerId, Quest.TURRET_KILL_PLAYER);
                }
            }
            Component msg = Component.text(resolveEntityLabel(victim), NamedTextColor.RED)
                    .append(Component.text(" was eliminated by ", NamedTextColor.GRAY))
                    .append(Component.text("Turret #" + kill.slot + " (" + kill.ownerName + ")",
                            NamedTextColor.GREEN));
            e.deathMessage(msg);
            return;
        }

        // Any other death (PvP, mob, fall, lava, …) → keep vanilla's phrasing but re-style it
        // into the uniform RED/GRAY/GREEN scheme and resolve NPC names.
        Component styled = styleVanillaDeath(e.deathMessage(), victim);
        if (styled != null) e.deathMessage(styled);
    }

    /**
     * Non-player victims — this is the path the <b>ravager</b> (the ~5% boss-type raid mob) takes:
     * a vanilla {@link org.bukkit.entity.Ravager} doesn't fire {@link PlayerDeathEvent}, so without
     * this handler a turret-killed ravager produced no kill message at all. We only act on ravagers
     * the raid engine tracks, and only when the kill is attributable to a turret; everything else
     * is left to vanilla. {@link EntityDeathEvent} has no death-message field, so we broadcast the
     * line ourselves (the player path piggybacks on the vanilla server-wide death broadcast).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity victim = e.getEntity();
        if (victim instanceof Player) return;   // player-type raiders go through onDeath()

        // Our tracked raid ravagers get a custom message; withers feed a quest. Wild mobs → vanilla.
        RaidEntityManager rem = plugin.getRaidEntities();
        boolean isRavager = rem != null && rem.getRavager(victim.getUniqueId()) != null;
        boolean isWither = victim instanceof org.bukkit.entity.Wither;
        if (!isRavager && !isWither) return;

        TurretKill kill = resolveTurretKill(victim);
        if (kill == null) return;   // not a turret kill — vanilla handles it

        // Quest credit for the turret owner.
        QuestManager q = plugin.getQuests();
        if (q != null && kill.ownerId != null) {
            if (isWither)  q.complete(kill.ownerId, Quest.TURRET_KILL_WITHER);
            if (isRavager) q.add(kill.ownerId, Quest.RAIDER_KILLS, 1);
        }

        // Custom elimination broadcast for ravagers (player-type raiders piggyback the vanilla
        // server-wide death broadcast in onDeath; a wither isn't announced here).
        if (isRavager) {
            Component msg = Component.text(resolveEntityLabel(victim), NamedTextColor.RED)
                    .append(Component.text(" was eliminated by ", NamedTextColor.GRAY))
                    .append(Component.text("Turret #" + kill.slot + " (" + kill.ownerName + ")",
                            NamedTextColor.GREEN));
            Bukkit.broadcast(msg);
        }
    }

    /**
     * Re-style a vanilla death message into the uniform RED/GRAY/GREEN scheme while preserving
     * its phrasing. Vanilla death messages are {@link TranslatableComponent}s where argument 0 is
     * always the victim and argument 1 (when present) is the killer/source. We swap those name
     * arguments for cleanly-resolved, coloured names (RED victim, GREEN killer) and paint the
     * connecting description text GRAY. Resolving the name arguments also keeps a Citizens
     * raider's {@code "CIT-<id>"} out of PvP/PvE death lines. Best-effort: anything we can't parse
     * falls back to a plain GRAY recolour, and any failure leaves the message untouched-but-gray.
     */
    private Component styleVanillaDeath(Component original, Player victim) {
        if (original == null) return null;

        Component victimName = Component.text(resolveEntityLabel(victim), NamedTextColor.RED);
        Entity killer = killerOf(victim);
        Component killerName = killer != null
                ? Component.text(resolveEntityLabel(killer), NamedTextColor.GREEN) : null;

        try {
            if (original instanceof TranslatableComponent tc) {
                List<Component> args = new ArrayList<>(tc.args());
                if (!args.isEmpty()) {
                    args.set(0, victimName);                       // arg 0 = victim
                    if (args.size() >= 2) {
                        args.set(1, killerName != null
                                ? killerName                       // resolved killer entity
                                : args.get(1).color(NamedTextColor.GREEN));  // unknown source
                    }
                    return tc.args(args).colorIfAbsent(NamedTextColor.GRAY);
                }
                return tc.colorIfAbsent(NamedTextColor.GRAY);
            }
        } catch (Throwable ignored) {
            // Unexpected component shape — fall through to the plain gray fallback below.
        }
        return original.colorIfAbsent(NamedTextColor.GRAY);
    }

    /**
     * The entity that killed {@code victim}, or {@code null} for an environmental death. Prefers
     * {@link Player#getKiller()}, then the last damage cause's damager; a projectile is credited
     * to its shooter so "shot by &lt;archer&gt;" names the player, not the arrow.
     */
    private Entity killerOf(Player victim) {
        Player byPlayer = victim.getKiller();
        if (byPlayer != null) return byPlayer;
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent ev) {
            Entity damager = ev.getDamager();
            if (damager instanceof Projectile proj) {
                ProjectileSource src = proj.getShooter();
                if (src instanceof Entity shooter) return shooter;
            }
            return damager;
        }
        return null;
    }

    /**
     * Human-readable label for any entity. If it's one of our tracked raiders/ravagers,
     * reconstruct {@code "<attacker>'s Raider"} from raid state (survives a Citizens name-revert to
     * {@code "CIT-<id>"}). Otherwise fall back to the entity's own name, or a neutral
     * {@code "a raider"} if even that has reverted to the Citizens default.
     */
    private String resolveEntityLabel(Entity entity) {
        RaidEntityManager rem = plugin.getRaidEntities();
        if (rem != null && plugin.getRaidManager() != null) {
            UUID raidId = null;
            CustomRaider r = rem.getRaider(entity.getUniqueId());
            if (r != null) raidId = r.getRaidId();
            else {
                CustomRavager rv = rem.getRavager(entity.getUniqueId());
                if (rv != null) raidId = rv.getRaidId();
            }
            if (raidId != null) {
                ActiveRaid raid = plugin.getRaidManager().getRaid(raidId);
                if (raid != null) return raid.getAttackerName() + "'s Raider";
            }
        }
        String n = entity.getName();
        if (n != null && !n.isEmpty() && !n.startsWith("CIT-")) return n;
        return "a raider";
    }

    /**
     * Identify the turret that killed {@code victim}, or {@code null} if it wasn't a turret kill.
     *
     * <p>Path A — standard: the most recent damager is a live turret NPC, matched by its Citizens
     * id against the tracked turret slots. We deliberately do NOT gate on the NPC's display name
     * ("Turret #..."): Citizens occasionally reverts the registry name to "CIT-&lt;id&gt;", and
     * gating on the prefix used to make this bail and let the vanilla "killed by CIT-&lt;id&gt;"
     * message leak. The id is authoritative regardless of name.
     *
     * <p>Path B — fallback: a panic-volley bullet killed the victim after the source turret NPC had
     * already despawned. {@link TurretCombatManager} stamps the victim with a "slot|ownerUuid"
     * metadata tag immediately before the damage call (slot already 1-based for display). We
     * consume (clear) that metadata here — death always invalidates it.
     */
    private TurretKill resolveTurretKill(LivingEntity victim) {
        int slotNumber = -1;
        String ownerName = null;
        UUID ownerUuid = null;

        Entity damager = lastTurretDamager(victim);
        if (damager != null) {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(damager);
            if (npc != null) {
                outer: for (Claim claim : plugin.getClaimManager().all().values()) {
                    for (Turret t : claim.getTurrets()) {
                        if (t.getNpcId() == npc.getId()) {
                            ownerUuid = claim.getOwner();
                            ownerName = plugin.getClaimManager().resolveName(ownerUuid);
                            slotNumber = t.getSlot() + 1;
                            break outer;
                        }
                    }
                }
            }
        }

        if (slotNumber < 0 && victim.hasMetadata(TurretCombatManager.TURRET_KILL_META)) {
            for (var meta : victim.getMetadata(TurretCombatManager.TURRET_KILL_META)) {
                String raw = meta.asString();
                if (raw == null || raw.isEmpty()) continue;
                int sep = raw.indexOf('|');
                if (sep <= 0) continue;
                try {
                    int parsedSlot = Integer.parseInt(raw.substring(0, sep));
                    ownerUuid = UUID.fromString(raw.substring(sep + 1));
                    slotNumber = parsedSlot;
                    ownerName = plugin.getClaimManager().resolveName(ownerUuid);
                    break;
                } catch (IllegalArgumentException ignored) {
                    // Malformed metadata (bad number or bad UUID) — fall through to next entry.
                    // NumberFormatException is a subclass of IllegalArgumentException so one
                    // catch covers both Integer.parseInt and UUID.fromString failures.
                }
            }
        }
        // Clear the metadata once we've consumed it (or even if we couldn't — death always
        // invalidates it). The scheduled cleanup in TurretCombatManager handles non-fatal hits.
        if (victim.hasMetadata(TurretCombatManager.TURRET_KILL_META)) {
            victim.removeMetadata(TurretCombatManager.TURRET_KILL_META, plugin);
        }

        if (slotNumber < 0 || ownerName == null) return null;
        return new TurretKill(slotNumber, ownerName, ownerUuid);
    }

    /** Returns the most recent EntityDamageByEntity damager if it's a Citizens NPC turret. */
    private Entity lastTurretDamager(LivingEntity victim) {
        if (victim.getLastDamageCause() instanceof EntityDamageByEntityEvent ev) {
            return ev.getDamager();
        }
        return null;
    }

    /** Resolved turret identity for a kill: 1-based display slot + owner display name + owner UUID. */
    private static final class TurretKill {
        final int slot;
        final String ownerName;
        final UUID ownerId;
        TurretKill(int slot, String ownerName, UUID ownerId) {
            this.slot = slot;
            this.ownerName = ownerName;
            this.ownerId = ownerId;
        }
    }
}
