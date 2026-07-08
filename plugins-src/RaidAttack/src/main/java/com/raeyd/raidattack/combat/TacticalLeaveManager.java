package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;

/**
 * Combat-log ("tactical leaving") punishment.
 *
 * <p>A player who quits while <b>under 3 hearts</b>, having just been <b>hit by a real player</b>
 * who is still within a <b>128-block cube</b> (all axes) of them, is marked as a tactical leaver
 * (persisted to the world DB so restarts don't grant amnesty). The verdict lands at their next
 * <b>successful /login</b> — never in limbo, because limbo hides the inventory in the
 * {@code LimboVault} and a kill there would drop nothing:
 *
 * <ul>
 *   <li>Back (authenticated) within 10 minutes of the quit → pardoned, the mark is dropped.</li>
 *   <li>Away 10 minutes or longer → after login restores them, they are teleported to the exact
 *       position they logged out at and self-killed there — everything drops on that spot — with a
 *       global death message naming the chaser (last hit) and the death coordinates.</li>
 * </ul>
 *
 * <p>Raider NPCs are Citizens player-entities, so both sides of the hit are NPC-filtered: only a
 * hit from a genuine player arms the mark, and NPCs can never be marked.
 */
public final class TacticalLeaveManager implements Listener {

    /** Under 3 hearts (health is half-hearts: 3 hearts = 6.0). */
    private static final double LOW_HEALTH_THRESHOLD = 6.0;
    /** Chaser must be within this many blocks on EVERY axis (cube, not sphere) at quit time. */
    private static final double CHASER_RANGE_BLOCKS = 128.0;
    /** Coming back (authenticated) within this window pardons the leaver. */
    private static final long RETURN_GRACE_MILLIS = 10L * 60L * 1000L;
    /** The last player hit must be this fresh at quit time to count as "being chased". */
    private static final long LAST_HIT_FRESH_MILLIS = 30_000L;

    /** Metadata carrying the prepared death message between the kill and PlayerDeathEvent. */
    private static final String DEATH_MESSAGE_META = "ra_tactical_leave_death";

    private record LastPlayerHit(UUID attackerId, String attackerName, long atMillis) {}

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    /** Last genuine-player hit per victim, in-memory (fresh-window is seconds, restarts clear it). */
    private final Map<UUID, LastPlayerHit> lastPlayerHitByVictim = new ConcurrentHashMap<>();

    public TacticalLeaveManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    // ---- tracking the chaser (last real-player hit) ---------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || isNpc(victim)) return;

        Player attacker = resolvePlayerAttacker(event);
        if (attacker == null || isNpc(attacker) || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        lastPlayerHitByVictim.put(victim.getUniqueId(),
                new LastPlayerHit(attacker.getUniqueId(), attacker.getName(), System.currentTimeMillis()));
    }

    /** The damaging player: a direct melee hit or the shooter of a projectile. */
    private static Player resolvePlayerAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    /** A normal death wipes the chase state — the fight is over, nothing to punish. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathClearChase(PlayerDeathEvent event) {
        lastPlayerHitByVictim.remove(event.getPlayer().getUniqueId());
    }

    // ---- marking the leaver on quit ---------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player victim = event.getPlayer();
        LastPlayerHit hit = lastPlayerHitByVictim.remove(victim.getUniqueId());
        if (isNpc(victim) || hit == null) return;

        long now = System.currentTimeMillis();
        if (now - hit.atMillis() > LAST_HIT_FRESH_MILLIS) return;      // stale fight, not a chase
        if (victim.isDead() || victim.getHealth() >= LOW_HEALTH_THRESHOLD) return;

        Player attacker = Bukkit.getPlayer(hit.attackerId());
        if (attacker == null || !attacker.isOnline() || attacker.isDead()) return;

        Location quitAt = victim.getLocation();
        Location chaserAt = attacker.getLocation();
        if (!chaserAt.getWorld().equals(quitAt.getWorld())) return;
        if (Math.abs(chaserAt.getX() - quitAt.getX()) > CHASER_RANGE_BLOCKS
                || Math.abs(chaserAt.getY() - quitAt.getY()) > CHASER_RANGE_BLOCKS
                || Math.abs(chaserAt.getZ() - quitAt.getZ()) > CHASER_RANGE_BLOCKS) return;

        worldDb.upsertTacticalLeave(new WorldDatabase.TacticalLeaveRow(
                victim.getUniqueId(), hit.attackerName(), quitAt.getWorld().getUID(),
                quitAt.getX(), quitAt.getY(), quitAt.getZ(), now));
        plugin.getLogger().info("[TacticalLeave] " + victim.getName() + " quit at " + fmt(quitAt)
                + " under 3 hearts while chased by " + hit.attackerName() + " — marked (10 min grace).");
    }

    // ---- the verdict, after a successful /login ---------------------------------------------

    /**
     * Called by AuthManager right after a SUCCESSFUL /login (inventory restored, limbo left).
     * Judges a pending tactical-leave mark; runs the kill a tick later so the login teleport and
     * restore have fully settled before we move + kill the player.
     */
    public void onAuthenticatedLogin(Player player) {
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(player.getUniqueId());
        if (row == null) return;
        worldDb.deleteTacticalLeave(player.getUniqueId());   // consumed either way — one verdict per mark

        if (System.currentTimeMillis() - row.quitAtMillis() < RETURN_GRACE_MILLIS) return;   // pardoned

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;

            World world = Bukkit.getWorld(row.worldUuid());
            Location deathSpot = world != null
                    ? new Location(world, row.x(), row.y(), row.z())
                    : player.getLocation();   // world gone (regenerated dev world) — die where they stand
            player.teleport(deathSpot);

            Component message = Component.text("☠ " + player.getName()
                            + " got killed for tactical leaving — chased by " + row.attackerName()
                            + " (last hit) at " + fmt(deathSpot), NamedTextColor.RED);
            player.setMetadata(DEATH_MESSAGE_META, new FixedMetadataValue(plugin,
                    net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(message)));

            // Self-kill at the spot: setHealth(0) is a plain death (no damager, ignores totems),
            // so vanilla drops the full inventory right here.
            player.setHealth(0.0);
            plugin.getLogger().info("[TacticalLeave] executed on " + player.getName() + " at " + fmt(deathSpot)
                    + " (chaser " + row.attackerName() + ").");
        });
    }

    /** Swap the vanilla death message for the tactical-leaving broadcast (death messages are global). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathMessage(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!player.hasMetadata(DEATH_MESSAGE_META)) return;
        for (var meta : player.getMetadata(DEATH_MESSAGE_META)) {
            if (meta.getOwningPlugin() == plugin) {
                event.deathMessage(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                        .deserialize(meta.asString()));
                break;
            }
        }
        player.removeMetadata(DEATH_MESSAGE_META, plugin);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static boolean isNpc(Player player) {
        try {
            return Bukkit.getPluginManager().getPlugin("Citizens") != null
                    && CitizensAPI.getNPCRegistry().isNPC(player);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String fmt(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
