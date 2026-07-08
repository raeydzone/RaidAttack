package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tactical-leaving punishment, built on {@link PvpModeManager}: quitting while in PvP mode
 * (with anyone) flags you. Your full inventory is snapshotted into {@code world.tactical_leaves},
 * so restarts grant no amnesty.
 *
 * <p><b>The 10-minute offline budget.</b> A flagged player has 10 minutes of CUMULATIVE offline
 * time to return — rejoining pauses the clock, quitting again (still flagged) resumes it where it
 * stopped, so hop-rejoining can't stall the verdict. The budget refreshes to a full 10 minutes
 * when the flag clears (PvP over / death), and also after 15 continuous minutes back on the
 * server while still flagged — so a crash during a long fight doesn't permanently eat the budget.
 *
 * <p><b>Discord warnings.</b> When cumulative offline time crosses 5 minutes and again at
 * 8 minutes, a row is queued in the bot-owned {@code public.tactical_leave_alerts} table; the
 * rAI bot polls it and DMs the player (green embed, server IP, exact quit time) — the 8-minute
 * one distinctly more urgent.
 *
 * <p><b>Rejoining does NOT pardon.</b> On authenticated /login the timers freeze and PvP mode
 * resumes exactly where it left off ({@link PvpModeManager#onMemberReturned}); the flag clears
 * only when the player's PvP mode fully ends — damage aged out, opponent out of the 250-block
 * range (re-checked the instant they return), or a death on either side. Any death of the
 * flagged player clears the flag too (they died for real; vanilla dropped their items).
 *
 * <p><b>The verdict at budget exhaustion</b> (never returned in time): the snapshot inventory
 * drops on the exact quit position, the death broadcasts naming the opponent, the row flips to
 * <em>executed</em> and the victim's next /login wipes their restored inventory (strictly AFTER
 * limbo, so nothing duplicates). The execution counts as a death: every PvP pair the leaver was
 * in ends, releasing the opponents.
 */
public final class TacticalLeaveManager implements Listener {

    /** Total offline budget while flagged; exhausting it executes the verdict. */
    private static final long RETURN_GRACE_MILLIS = 10L * 60L * 1000L;
    /** Cumulative-offline thresholds that queue a Discord warning via the rAI bot. */
    private static final long ALERT_FIRST_MILLIS = 5L * 60L * 1000L;
    private static final long ALERT_FINAL_MILLIS = 8L * 60L * 1000L;
    /** This long continuously back online (still flagged) refreshes the full offline budget. */
    private static final long ONLINE_REFRESH_MILLIS = 15L * 60L * 1000L;

    /** Metadata carrying the prepared death message between a live kill and PlayerDeathEvent. */
    private static final String DEATH_MESSAGE_META = "ra_tactical_leave_death";
    /** Metadata marking the artificial at-login death of an already-executed leaver: the death
     *  broadcast already went out at expiry, so this one must be chat-silent. */
    private static final String SILENT_DEATH_META = "ra_tactical_leave_silent_death";

    private static final Component TAG = Component.text("[RaidAttack] ", NamedTextColor.YELLOW)
            .decorate(TextDecoration.BOLD);

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    /** Armed offline timers by victim RID: [0] = execution, [1..] = alert tasks. */
    private final Map<UUID, List<BukkitTask>> offlineTasksByVictim = new ConcurrentHashMap<>();
    /** 15-min continuous-online budget-refresh task per flagged player. */
    private final Map<UUID, BukkitTask> onlineRefreshTaskByVictim = new ConcurrentHashMap<>();
    /** One-shot /ra pvp arm test flags: the next quit is a tactical leave regardless of PvP state. */
    private final Set<UUID> armedTestQuit = ConcurrentHashMap.newKeySet();
    /** RIDs with a pending (non-executed) mark — mirrors the DB so hot paths (every death, every
     *  pair end) can bail without a main-thread DB query. */
    private final Set<UUID> pendingFlagged = ConcurrentHashMap.newKeySet();

    public TacticalLeaveManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    /** onEnable: re-arm the offline timers of every pending mark (past-due fires next tick).
     *  Executed rows need no timer — they wait for the victim's next /login (inventory wipe). */
    public void start() {
        int armed = 0;
        for (WorldDatabase.TacticalLeaveRow row : worldDb.loadAllTacticalLeaves()) {
            if (row.executed()) continue;
            pendingFlagged.add(row.victimRid());
            scheduleOfflineTimers(row.victimRid(), row.quitAtMillis(), row.offlineUsedMillis(),
                    row.alerted5(), row.alerted8());
            armed++;
        }
        if (armed > 0) plugin.getLogger().info("[TacticalLeave] re-armed " + armed + " pending offline timer(s).");
    }

    // ---- the test arm (/ra pvp arm) -------------------------------------------------------------

    /** Toggle the one-shot test flag; returns the new armed state. */
    public boolean toggleArmedTestQuit(UUID playerId) {
        if (armedTestQuit.remove(playerId)) return false;
        armedTestQuit.add(playerId);
        return true;
    }

    // ---- marking the leaver on quit --------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player victim = event.getPlayer();
        if (isNpc(victim)) return;

        boolean armed = armedTestQuit.remove(victim.getUniqueId());
        PvpModeManager pvp = plugin.getPvpMode();
        boolean inPvp = pvp != null && pvp.isInPvpMode(victim.getUniqueId());
        if (!armed && !inPvp) return;
        if (victim.isDead()) return;   // died on the way out — the death already settled the fight

        // Freeze every PvP pair the leaver is in: window clocks stop while they're gone.
        if (pvp != null) pvp.onMemberQuit(victim.getUniqueId());

        String opponent = inPvp ? pvp.primaryOpponentName(victim.getUniqueId()) : null;
        if (opponent == null) opponent = "TEST";

        byte[] snapshot;
        try {
            snapshot = serializeInventory(victim.getInventory());
        } catch (IOException e) {
            plugin.getLogger().warning("[TacticalLeave] inventory snapshot failed for "
                    + victim.getName() + " — not marking: " + e.getMessage());
            return;
        }

        long now = System.currentTimeMillis();
        // Same engagement, second (third…) quit: carry the already-burned offline budget and
        // the already-sent alert thresholds forward. A fresh flag starts with a full budget.
        WorldDatabase.TacticalLeaveRow previous = worldDb.loadTacticalLeave(victim.getUniqueId());
        long offlineUsed = previous != null && !previous.executed() ? previous.offlineUsedMillis() : 0L;
        boolean alerted5 = previous != null && !previous.executed() && previous.alerted5();
        boolean alerted8 = previous != null && !previous.executed() && previous.alerted8();

        Location quitAt = victim.getLocation();
        pendingFlagged.add(victim.getUniqueId());
        worldDb.upsertTacticalLeave(new WorldDatabase.TacticalLeaveRow(
                victim.getUniqueId(), opponent, quitAt.getWorld().getUID(),
                quitAt.getX(), quitAt.getY(), quitAt.getZ(), now, snapshot, false,
                offlineUsed, alerted5, alerted8));

        cancelOnlineRefreshTask(victim.getUniqueId());
        scheduleOfflineTimers(victim.getUniqueId(), now, offlineUsed, alerted5, alerted8);

        long remainingMin = Math.max(1L, (RETURN_GRACE_MILLIS - offlineUsed + 59_999L) / 60_000L);
        Bukkit.broadcast(TAG.append(Component.text(victim.getName() + " tactically left during PvP mode against "
                + opponent + " — they have " + remainingMin + " minute" + (remainingMin == 1 ? "" : "s")
                + " to return.", NamedTextColor.RED)));
        plugin.getLogger().info("[TacticalLeave] " + victim.getName() + " quit in PvP mode (vs " + opponent
                + ") at " + fmt(quitAt) + " — offline budget used " + (offlineUsed / 1000L) + "s.");
    }

    // ---- offline timers (execution + Discord alerts) ---------------------------------------------

    /**
     * Arm the execution timer and the not-yet-sent 5/8-minute alert timers, all measured in
     * CUMULATIVE offline time: each delay is the threshold minus what was already burned before
     * this quit. Server downtime counts as offline time (past-due timers fire next tick).
     */
    private void scheduleOfflineTimers(UUID victimRid, long quitAtMillis, long offlineUsedMillis,
                                       boolean alerted5, boolean alerted8) {
        cancelOfflineTasks(victimRid);
        List<BukkitTask> tasks = new ArrayList<>();
        long alreadyElapsed = offlineUsedMillis + Math.max(0L, System.currentTimeMillis() - quitAtMillis);

        tasks.add(runLater(RETURN_GRACE_MILLIS - alreadyElapsed, () -> executeExpiry(victimRid)));
        if (!alerted5) tasks.add(runLater(ALERT_FIRST_MILLIS - alreadyElapsed,
                () -> queueDiscordAlert(victimRid, 5)));
        if (!alerted8) tasks.add(runLater(ALERT_FINAL_MILLIS - alreadyElapsed,
                () -> queueDiscordAlert(victimRid, 8)));
        offlineTasksByVictim.put(victimRid, tasks);
    }

    private BukkitTask runLater(long delayMillis, Runnable action) {
        return plugin.getServer().getScheduler().runTaskLater(plugin, action, Math.max(1L, delayMillis / 50L));
    }

    /** Queue one Discord warning row (async DB write) if the mark is still pending and they're still gone. */
    private void queueDiscordAlert(UUID victimRid, int thresholdMinutes) {
        Player online = Bukkit.getPlayer(victimRid);
        if (online != null && online.isOnline() && isAuthenticated(victimRid)) return;   // they're back
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(victimRid);
            if (row == null || row.executed()) return;
            if (thresholdMinutes >= 8 ? row.alerted8() : row.alerted5()) return;
            String javaName = resolveName(victimRid);
            if (worldDb.insertTacticalLeaveAlert(victimRid, javaName, row.attackerName(),
                    row.quitAtMillis(), thresholdMinutes)) {
                worldDb.markTacticalLeaveAlerted(victimRid, thresholdMinutes);
                plugin.getLogger().info("[TacticalLeave] queued " + thresholdMinutes + "-min Discord alert for " + javaName + ".");
            }
        });
    }

    // ---- the verdict, at budget exhaustion --------------------------------------------------------

    /** Main thread, once the 10-minute offline budget is gone. The mark becomes a death. */
    private void executeExpiry(UUID victimRid) {
        cancelOfflineTasks(victimRid);
        pendingFlagged.remove(victimRid);   // whatever happens below, the mark stops being "pending"
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(victimRid);
        if (row == null || row.executed()) return;   // cleared or already handled

        // Back and authenticated just before expiry (login would have paused the clock — this is
        // the tiny race where both land on the same tick): kill them live, the normal way.
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
            plugin.getLogger().info("[TacticalLeave] executed on " + resolveName(victimRid) + " at "
                    + fmt(dropAt) + " — dropped " + dropped + " stack(s) (opponent " + row.attackerName() + ").");
        } else {
            plugin.getLogger().warning("[TacticalLeave] world " + row.worldUuid() + " not loaded — "
                    + "items for " + victimRid + " could not drop; death still declared.");
        }

        plugin.getServer().broadcast(deathMessage(resolveName(victimRid), row));
        worldDb.markTacticalLeaveExecuted(victimRid);   // → next /login wipes the inventory

        // The execution counts as a death: release every opponent still paired with the leaver.
        if (plugin.getPvpMode() != null) {
            plugin.getPvpMode().endAllFor(victimRid, "tactical-leave execution of " + resolveName(victimRid));
        }
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
                + " at " + fmt(deathSpot) + " (opponent " + row.attackerName() + ").");
    }

    // ---- after a successful /login ----------------------------------------------------------------

    /**
     * Called by AuthManager right after a SUCCESSFUL /login (inventory restored, limbo left).
     * A pending flag PAUSES (offline budget banked, PvP pairs unfreeze + revalidate — which may
     * clear the flag on the spot); an executed one wipes the restored inventory (their items
     * already dropped at the quit spot when the budget ran out).
     */
    public void onAuthenticatedLogin(Player player) {
        UUID rid = player.getUniqueId();
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(rid);
        if (row == null) return;

        if (!row.executed()) {
            cancelOfflineTasks(rid);   // clock paused — they're back
            long offlineUsed = Math.min(RETURN_GRACE_MILLIS,
                    row.offlineUsedMillis() + Math.max(0L, System.currentTimeMillis() - row.quitAtMillis()));
            worldDb.updateTacticalLeaveOfflineUsed(rid, offlineUsed);

            // Unfreeze + instantly revalidate their pairs: an opponent who wandered out of the
            // 250-block range meanwhile ends PvP mode right here (which clears the flag via
            // onPvpPairEnded before we even check below).
            if (plugin.getPvpMode() != null) plugin.getPvpMode().onMemberReturned(rid);

            boolean stillInPvp = plugin.getPvpMode() != null && plugin.getPvpMode().isInPvpMode(rid);
            if (!stillInPvp) {
                clearFlag(rid);
                player.sendMessage(TAG.append(Component.text("Your PvP mode is over — the tactical-leave "
                        + "flag was lifted. You may log out safely.", NamedTextColor.GREEN)));
                return;
            }

            long remainingMin = Math.max(0L, (RETURN_GRACE_MILLIS - offlineUsed) / 60_000L);
            player.sendMessage(TAG.append(Component.text("You are flagged for tactical leaving — PvP mode is"
                    + " still active. Stay on the server until it ends before logging out (leave again and"
                    + " your remaining return time is ~" + remainingMin + " min).", NamedTextColor.RED)));

            // 15 continuous minutes back online (still flagged) refreshes the full offline budget —
            // so a crash mid-fight doesn't permanently starve the return timer.
            cancelOnlineRefreshTask(rid);
            onlineRefreshTaskByVictim.put(rid, plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                onlineRefreshTaskByVictim.remove(rid);
                Player still = Bukkit.getPlayer(rid);
                if (still == null || !still.isOnline()) return;
                WorldDatabase.TacticalLeaveRow current = worldDb.loadTacticalLeave(rid);
                if (current == null || current.executed()) return;
                worldDb.updateTacticalLeaveOfflineUsed(rid, 0L);
                plugin.getLogger().info("[TacticalLeave] offline budget refreshed for " + still.getName()
                        + " (15 min continuously online).");
            }, ONLINE_REFRESH_MILLIS / 50L));
            return;
        }

        // Executed while they were away: their items already dropped at the quit spot — wipe the
        // restored inventory so nothing duplicates, then run a REAL (artificial) death so they
        // go through the respawn flow and wake at their bed / respawn anchor, or the world spawn
        // if none — not standing alive on the death position. Wipe strictly BEFORE the kill so
        // the death drops nothing (the snapshot already dropped at expiry). Runs a tick later so
        // the login restore settles first.
        worldDb.deleteTacticalLeave(rid);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            PlayerInventory inv = player.getInventory();
            inv.setStorageContents(new ItemStack[inv.getStorageContents().length]);
            inv.setArmorContents(new ItemStack[inv.getArmorContents().length]);
            inv.setItemInOffHand(null);
            player.sendMessage(Component.text("☠ You were killed for tactical leaving while you were"
                    + " gone — your items dropped where you logged out.", NamedTextColor.RED));
            // Chat-silent kill: the server-wide broadcast already happened at expiry. setHealth(0)
            // is a plain death (no damager, ignores totems — the inventory is empty anyway).
            player.setMetadata(SILENT_DEATH_META, new FixedMetadataValue(plugin, true));
            player.setHealth(0.0);
            plugin.getLogger().info("[TacticalLeave] wiped inventory of " + player.getName()
                    + " and ran the artificial respawn death (items dropped earlier at "
                    + (int) row.x() + ", " + (int) row.y() + ", " + (int) row.z() + ").");
        });
    }

    // ---- flag lifecycle ---------------------------------------------------------------------------

    /**
     * Called by {@link PvpModeManager} for each member after one of their pairs ends. If that was
     * the player's LAST active pair, any pending flag lifts — whether they're online (fight over,
     * free to leave) or offline (their opponent died / left the area: nothing left to punish).
     */
    public void onPvpPairEnded(UUID playerId) {
        if (!pendingFlagged.contains(playerId)) return;
        if (plugin.getPvpMode() != null && plugin.getPvpMode().isInPvpMode(playerId)) return;  // other pairs remain
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(playerId);
        if (row == null || row.executed()) return;
        clearFlag(playerId);
        Player online = Bukkit.getPlayer(playerId);
        if (online != null && online.isOnline()) {
            online.sendMessage(TAG.append(Component.text("PvP mode is over — your tactical-leave flag"
                    + " was lifted. You may log out safely.", NamedTextColor.GREEN)));
        }
        plugin.getLogger().info("[TacticalLeave] flag lifted for " + resolveName(playerId) + " (PvP mode over).");
    }

    /** Any death of a flagged, online player settles the fight — flag and timers vanish. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID rid = event.getPlayer().getUniqueId();
        if (!pendingFlagged.contains(rid)) return;
        WorldDatabase.TacticalLeaveRow row = worldDb.loadTacticalLeave(rid);
        if (row != null && !row.executed()) clearFlag(rid);
    }

    /** Drop the pending mark + every timer attached to it. */
    private void clearFlag(UUID victimRid) {
        pendingFlagged.remove(victimRid);
        cancelOfflineTasks(victimRid);
        cancelOnlineRefreshTask(victimRid);
        worldDb.deleteTacticalLeave(victimRid);
    }

    /** Swap the vanilla death message for the tactical-leaving broadcast (live-kill path only),
     *  or silence it entirely for the artificial at-login respawn death (already broadcast). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeathMessage(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (player.hasMetadata(SILENT_DEATH_META)) {
            event.deathMessage(null);
            player.removeMetadata(SILENT_DEATH_META, plugin);
            return;
        }
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

    // ---- helpers ----------------------------------------------------------------------------------

    private Component deathMessage(String victimName, WorldDatabase.TacticalLeaveRow row) {
        return Component.text("☠ " + victimName + " got killed for tactical leaving — PvP mode against "
                + row.attackerName() + " at "
                + (int) row.x() + ", " + (int) row.y() + ", " + (int) row.z(), NamedTextColor.RED);
    }

    /** Display name for an (often offline) RID — cached name table, falling back to the id. */
    private String resolveName(UUID victimRid) {
        Player online = Bukkit.getPlayer(victimRid);
        if (online != null) return online.getName();
        String cached = worldDb.loadPlayerNames().get(victimRid);
        return cached != null ? cached : victimRid.toString().substring(0, 8);
    }

    private boolean isAuthenticated(UUID rid) {
        return plugin.getAuthManager() == null || !plugin.getAuthManager().isInLimbo(rid);
    }

    private void cancelOfflineTasks(UUID victimRid) {
        List<BukkitTask> tasks = offlineTasksByVictim.remove(victimRid);
        if (tasks != null) for (BukkitTask task : tasks) task.cancel();
    }

    private void cancelOnlineRefreshTask(UUID victimRid) {
        BukkitTask task = onlineRefreshTaskByVictim.remove(victimRid);
        if (task != null) task.cancel();
    }

    /** Storage + armor + offhand in one blob (same BukkitObjectStream format as the raid stash). */
    private static byte[] serializeInventory(PlayerInventory inv) throws IOException {
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack it : inv.getStorageContents()) all.add(it);
        for (ItemStack it : inv.getArmorContents()) all.add(it);
        all.add(inv.getItemInOffHand());
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (org.bukkit.util.io.BukkitObjectOutputStream out = new org.bukkit.util.io.BukkitObjectOutputStream(bos)) {
            out.writeInt(all.size());
            for (ItemStack it : all) out.writeObject(it);
        }
        return bos.toByteArray();
    }

    private static ItemStack[] deserializeInventory(byte[] data) throws IOException, ClassNotFoundException {
        try (org.bukkit.util.io.BukkitObjectInputStream in
                     = new org.bukkit.util.io.BukkitObjectInputStream(new ByteArrayInputStream(data))) {
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
