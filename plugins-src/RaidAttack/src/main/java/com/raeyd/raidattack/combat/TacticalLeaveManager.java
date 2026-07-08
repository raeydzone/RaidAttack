package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Combat-log ("tactical leaving") punishment.
 *
 * <p>A player who quits at <b>3 hearts or less</b>, having just been <b>hit by a real player</b>
 * who is still within a <b>150-block cube</b> (all axes) of them, is marked as a tactical leaver.
 * The mark carries a snapshot of their full inventory and persists in the world DB, so restarts
 * grant no amnesty (expiry timers are re-armed on boot).
 *
 * <p>The verdict lands <b>when the 10-minute timer expires — whether or not they ever rejoin</b>:
 * <ul>
 *   <li>Back (authenticated via /login) within 10 minutes → pardoned, mark dropped.</li>
 *   <li>Timer expires → the snapshot inventory drops on the exact quit position (the chaser can
 *       collect it right then) and the global "tactical leaving" message broadcasts, naming the
 *       chaser (last hit) and the coordinates. The row flips to <em>executed</em>; at the
 *       victim's next successful /login their inventory is wiped — strictly AFTER limbo, so the
 *       wipe hits the vault-restored items and nothing duplicates. Logically a death at expiry.</li>
 * </ul>
 *
 * <p>Raider NPCs are Citizens player-entities, so both sides of the hit are NPC-filtered.
 */
public final class TacticalLeaveManager implements Listener {

    /** 3 hearts or less (health is half-hearts: 3 hearts = 6.0). */
    private static final double LOW_HEALTH_THRESHOLD = 6.0;
    /** Chaser must be within this many blocks on EVERY axis (cube, not sphere) at quit time. */
    private static final double CHASER_RANGE_BLOCKS = 150.0;
    /** Coming back (authenticated) within this window pardons the leaver. */
    private static final long RETURN_GRACE_MILLIS = 10L * 60L * 1000L;
    /** The last player hit must be this fresh at quit time to count as "being chased". */
    private static final long LAST_HIT_FRESH_MILLIS = 30_000L;

    /** Metadata carrying the prepared death message between a live kill and PlayerDeathEvent. */
    private static final String DEATH_MESSAGE_META = "ra_tactical_leave_death";

    private record LastPlayerHit(UUID attackerId, String attackerName, long atMillis) {}

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    /** Last genuine-player hit per victim, in-memory (fresh-window is seconds, restarts clear it). */
    private final Map<UUID, LastPlayerHit> lastPlayerHitByVictim = new ConcurrentHashMap<>();
    /** Armed expiry timers by victim RID, cancelled on pardon. */
    private final Map<UUID, BukkitTask> expiryTaskByVictim = new ConcurrentHashMap<>();

    public TacticalLeaveManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    /** onEnable: re-arm the expiry timer of every pending mark (past-due fires next tick).
     *  Executed rows need no timer — they wait for the victim's next /login (inventory wipe). */
    public void start() {
        int armed = 0;
        for (WorldDatabase.TacticalLeaveRow row : worldDb.loadAllTacticalLeaves()) {
            if (row.executed()) continue;
            scheduleExpiry(row.victimRid(), row.quitAtMillis());
            armed++;
        }
        if (armed > 0) plugin.getLogger().info("[TacticalLeave] re-armed " + armed + " pending expiry timer(s).");
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
        if (victim.isDead() || victim.getHealth() > LOW_HEALTH_THRESHOLD) return;   // ≤ 3 hearts triggers

        Player attacker = Bukkit.getPlayer(hit.attackerId());
        if (attacker == null || !attacker.isOnline() || attacker.isDead()) return;

        Location quitAt = victim.getLocation();
        Location chaserAt = attacker.getLocation();
        if (!chaserAt.getWorld().equals(quitAt.getWorld())) return;
        if (Math.abs(chaserAt.getX() - quitAt.getX()) > CHASER_RANGE_BLOCKS
                || Math.abs(chaserAt.getY() - quitAt.getY()) > CHASER_RANGE_BLOCKS
                || Math.abs(chaserAt.getZ() - quitAt.getZ()) > CHASER_RANGE_BLOCKS) return;

        byte[] snapshot;
        try {
            snapshot = serializeInventory(victim.getInventory());
        } catch (IOException e) {
            plugin.getLogger().warning("[TacticalLeave] inventory snapshot failed for "
                    + victim.getName() + " — not marking: " + e.getMessage());
            return;
        }

        worldDb.upsertTacticalLeave(new WorldDatabase.TacticalLeaveRow(
                victim.getUniqueId(), hit.attackerName(), quitAt.getWorld().getUID(),
                quitAt.getX(), quitAt.getY(), quitAt.getZ(), now, snapshot, false));
        scheduleExpiry(victim.getUniqueId(), now);
        plugin.getLogger().info("[TacticalLeave] " + victim.getName() + " quit at " + fmt(quitAt)
                + " on ≤3 hearts while chased by " + hit.attackerName() + " — marked (10 min grace).");
    }

    // ---- the verdict, at expiry ---------------------------------------------------------------

    private void scheduleExpiry(UUID victimRid, long quitAtMillis) {
        cancelExpiryTask(victimRid);
        long remainingTicks = Math.max(1L,
                (quitAtMillis + RETURN_GRACE_MILLIS - System.currentTimeMillis()) / 50L);
        expiryTaskByVictim.put(victimRid, plugin.getServer().getScheduler()
                .runTaskLater(plugin, () -> executeExpiry(victimRid), remainingTicks));
    }

    /** Main thread, 10 minutes after the tagged quit. The mark becomes a death. */
    private void executeExpiry(UUID victimRid) {
        expiryTaskByVictim.remove(victimRid);
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(victimRid);
        if (row == null || row.executed()) return;   // pardoned or already handled

        // Rejoined and authenticated just before expiry (login would have pardoned — this is the
        // tiny race where both land on the same tick): kill them live, the normal way.
        Player online = Bukkit.getPlayer(victimRid);
        if (online != null && online.isOnline() && isAuthenticated(victimRid)) {
            worldDb.deleteTacticalLeave(victimRid);
            killLive(online, row);
            return;
        }

        // Normal case — victim offline (or unauthenticated in limbo, whose /login wipe dedupes):
        // drop the snapshot where they logged out and declare the death globally.
        World world = Bukkit.getWorld(row.worldUuid());
        if (world != null && row.inventory() != null) {
            Location dropAt = new Location(world, row.x(), row.y(), row.z());
            int dropped = 0;
            try {
                for (ItemStack item : deserializeInventory(row.inventory())) {
                    if (item == null || item.getType().isAir()) continue;
                    world.dropItemNaturally(dropAt, item);
                    dropped++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[TacticalLeave] snapshot drop failed for " + victimRid + ": " + e.getMessage());
            }
            plugin.getLogger().info("[TacticalLeave] executed on " + resolveName(victimRid, row) + " at "
                    + fmt(dropAt) + " — dropped " + dropped + " stack(s) (chaser " + row.attackerName() + ").");
        } else {
            plugin.getLogger().warning("[TacticalLeave] world " + row.worldUuid() + " not loaded — "
                    + "items for " + victimRid + " could not drop; death still declared.");
        }

        plugin.getServer().broadcast(deathMessage(resolveName(victimRid, row), row));
        worldDb.markTacticalLeaveExecuted(victimRid);   // → next /login wipes the inventory
    }

    /** Kill a live, authenticated victim on the spot (same message via the death event). */
    private void killLive(Player player, WorldDatabase.TacticalLeaveRow row) {
        World world = Bukkit.getWorld(row.worldUuid());
        Location deathSpot = world != null
                ? new Location(world, row.x(), row.y(), row.z())
                : player.getLocation();
        player.teleport(deathSpot);
        Component message = deathMessage(player.getName(), row);
        player.setMetadata(DEATH_MESSAGE_META, new FixedMetadataValue(plugin,
                net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(message)));
        // Self-kill at the spot: setHealth(0) is a plain death (no damager, ignores totems),
        // so vanilla drops the full inventory right here.
        player.setHealth(0.0);
        plugin.getLogger().info("[TacticalLeave] executed live on " + player.getName()
                + " at " + fmt(deathSpot) + " (chaser " + row.attackerName() + ").");
    }

    // ---- after a successful /login --------------------------------------------------------------

    /**
     * Called by AuthManager right after a SUCCESSFUL /login (inventory restored, limbo left).
     * Pardons a still-pending mark inside the grace window; wipes the inventory of an executed
     * one (their items already dropped at the quit spot when the timer expired).
     */
    public void onAuthenticatedLogin(Player player) {
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(player.getUniqueId());
        if (row == null) return;

        if (!row.executed()) {
            if (System.currentTimeMillis() - row.quitAtMillis() < RETURN_GRACE_MILLIS) {
                cancelExpiryTask(player.getUniqueId());               // back in time — pardoned
                worldDb.deleteTacticalLeave(player.getUniqueId());
            }
            return;   // past-due but unexecuted (boot race): the re-armed timer handles it live
        }

        // Executed while they were away: their items already dropped at the quit spot — wipe the
        // restored inventory so nothing duplicates. Runs a tick later so the login restore settles.
        worldDb.deleteTacticalLeave(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            PlayerInventory inv = player.getInventory();
            inv.setStorageContents(new ItemStack[inv.getStorageContents().length]);
            inv.setArmorContents(new ItemStack[inv.getArmorContents().length]);
            inv.setItemInOffHand(null);
            player.sendMessage(Component.text("☠ You were killed for tactical leaving while you were"
                    + " gone — your items dropped where you logged out.", NamedTextColor.RED));
            plugin.getLogger().info("[TacticalLeave] wiped inventory of " + player.getName()
                    + " (items dropped earlier at " + (int) row.x() + ", " + (int) row.y() + ", " + (int) row.z() + ").");
        });
    }

    /** Swap the vanilla death message for the tactical-leaving broadcast (live-kill path only). */
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

    private Component deathMessage(String victimName, WorldDatabase.TacticalLeaveRow row) {
        return Component.text("☠ " + victimName + " got killed for tactical leaving — chased by "
                + row.attackerName() + " (last hit) at "
                + (int) row.x() + ", " + (int) row.y() + ", " + (int) row.z(), NamedTextColor.RED);
    }

    /** Victim display name for an (often offline) RID — cached name table, falling back to the id. */
    private String resolveName(UUID victimRid, WorldDatabase.TacticalLeaveRow row) {
        Player online = Bukkit.getPlayer(victimRid);
        if (online != null) return online.getName();
        String cached = worldDb.loadPlayerNames().get(victimRid);
        return cached != null ? cached : victimRid.toString().substring(0, 8);
    }

    private boolean isAuthenticated(UUID rid) {
        return plugin.getAuthManager() == null || !plugin.getAuthManager().isInLimbo(rid);
    }

    private void cancelExpiryTask(UUID victimRid) {
        BukkitTask task = expiryTaskByVictim.remove(victimRid);
        if (task != null) task.cancel();
    }

    /** Storage + armor + offhand in one blob (same BukkitObjectStream format as the raid stash). */
    private static byte[] serializeInventory(PlayerInventory inv) throws IOException {
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack it : inv.getStorageContents()) all.add(it);
        for (ItemStack it : inv.getArmorContents()) all.add(it);
        all.add(inv.getItemInOffHand());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(all.size());
            for (ItemStack it : all) out.writeObject(it);
        }
        return bos.toByteArray();
    }

    private static ItemStack[] deserializeInventory(byte[] data) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            ItemStack[] arr = new ItemStack[n];
            for (int i = 0; i < n; i++) arr[i] = (ItemStack) in.readObject();
            return arr;
        }
    }

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
