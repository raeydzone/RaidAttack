package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.citizensnpcs.api.CitizensAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Pairwise <b>PvP mode</b> tracking — the foundation the tactical-leaving punishment sits on.
 *
 * <p>Every real-player-vs-real-player hit is recorded as <b>raw damage</b> (pre-armor /
 * pre-enchant / pre-potion — {@code event.getDamage()}). A pair of players ENTERS PvP mode the
 * moment either one has dealt more than {@link #TRIGGER_RAW_DAMAGE} raw damage to the other
 * within the rolling {@link #WINDOW_MILLIS 5-minute} window — one direction suffices: the
 * attacked player is always pulled in instantly, so one broadcast covers both. A player can be
 * in PvP mode with any number of opponents at once; each pair is tracked independently.
 *
 * <p>A pair LEAVES PvP mode the instant any of these breaks (checked every second):
 * <ul>
 *   <li>the rolling window no longer holds &gt;25 raw damage in either direction (hits age out);</li>
 *   <li>the two players are no longer within {@link #RANGE_BLOCKS} blocks on every axis
 *       (same cube convention as the rest of the plugin), or land in different worlds;</li>
 *   <li>either player dies — any cause, including the tactical-leave 10-minute execution
 *       (the survivor is released immediately).</li>
 * </ul>
 * On exit the pair's damage history is cleared — re-entering PvP mode needs fresh &gt;25 damage,
 * so the pair can't flip-flop on stale hits.
 *
 * <p><b>Freezing.</b> While either member of a pair is offline (a tactical leaver mid-timer),
 * the pair is frozen: window entries don't age, range isn't checked, the pair can't end. When
 * the absentee returns, the pause is added onto every entry timestamp — the clock continues
 * exactly as if the absence never happened — and the pair is revalidated immediately, so an
 * opponent who wandered out of range meanwhile ends PvP mode on the spot.
 *
 * <p>Raider NPCs are Citizens player-entities; both sides of every hit are NPC-filtered.
 */
public final class PvpModeManager implements Listener {

    /** Strictly more than this much raw damage within the window triggers PvP mode. */
    public static final double TRIGGER_RAW_DAMAGE = 25.0;
    /** Rolling damage window. */
    public static final long WINDOW_MILLIS = 5L * 60L * 1000L;
    /** Pair must stay within this many blocks on EVERY axis (cube, not sphere). */
    public static final double RANGE_BLOCKS = 250.0;

    /** Chat tag on every PvP-mode message. */
    private static final Component TAG = Component.text("[RaidAttack] ", NamedTextColor.YELLOW)
            .decorate(TextDecoration.BOLD);

    private record DamageEntry(UUID damagerId, long atMillis, double rawDamage) {}

    /** Canonically ordered player pair, so (A,B) and (B,A) share one state. */
    record PairKey(UUID low, UUID high) {
        static PairKey of(UUID a, UUID b) {
            return a.compareTo(b) <= 0 ? new PairKey(a, b) : new PairKey(b, a);
        }
        boolean contains(UUID id) { return low.equals(id) || high.equals(id); }
        UUID other(UUID id) { return low.equals(id) ? high : low; }
    }

    private static final class PairState {
        final Deque<DamageEntry> entries = new ArrayDeque<>();
        boolean active = false;
        /** Epoch ms when this pair froze (a member went offline); 0 = live. */
        long pausedSinceMillis = 0L;
        /** Last known display names, so end-messages work while a member is offline. */
        String lowName = "?", highName = "?";
    }

    private final HomeSystemPlugin plugin;
    private final Map<PairKey, PairState> pairs = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public PvpModeManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /** onEnable: start the once-a-second pair revalidation tick. */
    public void start() {
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    /** onDisable. PvP state is runtime-only by design — a restart clears every pair. */
    public void stop() {
        if (tickTask != null) tickTask.cancel();
        pairs.clear();
    }

    // ---- damage intake ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || isNpc(victim)) return;
        Player attacker = resolvePlayerAttacker(event);
        if (attacker == null || isNpc(attacker) || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        long now = System.currentTimeMillis();
        PairKey key = PairKey.of(attacker.getUniqueId(), victim.getUniqueId());
        PairState state = pairs.computeIfAbsent(key, k -> new PairState());
        state.lowName = nameOf(key.low(), state.lowName);
        state.highName = nameOf(key.high(), state.highName);
        // Raw pre-mitigation damage, per spec — armor/protection/resistance must not dilute it.
        state.entries.addLast(new DamageEntry(attacker.getUniqueId(), now, event.getDamage()));

        if (!state.active && state.pausedSinceMillis == 0L) {
            pruneOldEntries(state, now);
            if (directionOverThreshold(key, state)) activatePair(key, state);
        }
    }

    /** The damaging player: a direct melee hit or the shooter of a projectile. */
    private static Player resolvePlayerAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) return p;
        if (event.getDamager() instanceof Projectile proj
                && proj.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private void activatePair(PairKey key, PairState state) {
        state.active = true;
        Bukkit.broadcast(TAG.append(Component.text("⚔ " + state.lowName + " and " + state.highName
                + " entered PvP mode.", NamedTextColor.WHITE)));
    }

    /** End one pair: quiet note to both members, wipe its damage history (fresh-start rule). */
    private void endPair(PairKey key, PairState state, String reason) {
        boolean wasActive = state.active;
        pairs.remove(key);
        if (!wasActive) return;
        plugin.getLogger().info("[PvpMode] " + state.lowName + " vs " + state.highName + " ended (" + reason + ").");
        notifyEnded(key.low(), key.high(), state.highName, state.lowName);
        // Tell the tactical-leave layer for BOTH members — whoever now has zero active pairs
        // and carries a pending flag is released ("no flag remains once PvP mode is over").
        if (plugin.getTacticalLeaves() != null) {
            plugin.getTacticalLeaves().onPvpPairEnded(key.low());
            plugin.getTacticalLeaves().onPvpPairEnded(key.high());
        }
    }

    /** Quiet per-player end message (only to online members). */
    private void notifyEnded(UUID low, UUID high, String highName, String lowName) {
        Player p1 = Bukkit.getPlayer(low);
        if (p1 != null && p1.isOnline()) p1.sendMessage(TAG.append(Component.text(
                "PvP mode with " + highName + " ended — you may log out safely.", NamedTextColor.GRAY)));
        Player p2 = Bukkit.getPlayer(high);
        if (p2 != null && p2.isOnline()) p2.sendMessage(TAG.append(Component.text(
                "PvP mode with " + lowName + " ended — you may log out safely.", NamedTextColor.GRAY)));
    }

    /** Once a second: prune windows and revalidate every live active pair. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<PairKey, PairState> e : pairs.entrySet()) {
            PairKey key = e.getKey();
            PairState state = e.getValue();
            if (state.pausedSinceMillis != 0L) continue;          // frozen — untouchable
            pruneOldEntries(state, now);
            if (!state.active) {
                if (state.entries.isEmpty()) pairs.remove(key);   // idle bookkeeping, nothing brewing
                continue;
            }
            if (!directionOverThreshold(key, state)) { endPair(key, state, "damage aged out"); continue; }
            Player a = Bukkit.getPlayer(key.low());
            Player b = Bukkit.getPlayer(key.high());
            if (a == null || b == null || !a.isOnline() || !b.isOnline()) continue; // quit handled via freeze
            if (a.isDead() || b.isDead()) continue;                                 // death event handles it
            if (!withinRange(a, b)) endPair(key, state, "out of range");
        }
    }

    private static boolean withinRange(Player a, Player b) {
        if (!a.getWorld().equals(b.getWorld())) return false;
        var la = a.getLocation();
        var lb = b.getLocation();
        return Math.abs(la.getX() - lb.getX()) <= RANGE_BLOCKS
                && Math.abs(la.getY() - lb.getY()) <= RANGE_BLOCKS
                && Math.abs(la.getZ() - lb.getZ()) <= RANGE_BLOCKS;
    }

    private static void pruneOldEntries(PairState state, long now) {
        Iterator<DamageEntry> it = state.entries.iterator();
        while (it.hasNext()) {
            if (now - it.next().atMillis() > WINDOW_MILLIS) it.remove();
            else break;   // entries are appended in time order — first fresh one ends the sweep
        }
    }

    /** True iff either direction's window sum is strictly over the trigger (one direction suffices). */
    private static boolean directionOverThreshold(PairKey key, PairState state) {
        double fromLow = 0, fromHigh = 0;
        for (DamageEntry entry : state.entries) {
            if (entry.damagerId().equals(key.low())) fromLow += entry.rawDamage();
            else fromHigh += entry.rawDamage();
        }
        return fromLow > TRIGGER_RAW_DAMAGE || fromHigh > TRIGGER_RAW_DAMAGE;
    }

    // ---- death / quit / return ------------------------------------------------------------------

    /** Any death ends every pair the player is part of — the survivor is released instantly. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        endAllFor(event.getPlayer().getUniqueId(), "death of " + event.getPlayer().getName());
    }

    /** End every pair containing this player (death, or tactical-leave execution = death). */
    public void endAllFor(UUID playerId, String reason) {
        for (Map.Entry<PairKey, PairState> e : List.copyOf(pairs.entrySet())) {
            if (e.getKey().contains(playerId)) endPair(e.getKey(), e.getValue(), reason);
        }
    }

    /** A member quit (tactical leaver): freeze every pair they're part of. */
    public void onMemberQuit(UUID playerId) {
        long now = System.currentTimeMillis();
        for (Map.Entry<PairKey, PairState> e : pairs.entrySet()) {
            if (!e.getKey().contains(playerId)) continue;
            PairState state = e.getValue();
            if (state.pausedSinceMillis == 0L) state.pausedSinceMillis = now;
        }
    }

    /**
     * A member returned (authenticated): unfreeze their pairs — shift every entry forward by the
     * pause, so the window continues as if the absence never happened — then revalidate at once
     * (range included), so a vanished opponent releases the pair immediately.
     */
    public void onMemberReturned(UUID playerId) {
        long now = System.currentTimeMillis();
        List<PairKey> toRevalidate = new ArrayList<>();
        for (Map.Entry<PairKey, PairState> e : pairs.entrySet()) {
            PairKey key = e.getKey();
            if (!key.contains(playerId)) continue;
            PairState state = e.getValue();
            if (state.pausedSinceMillis != 0L) {
                UUID other = key.other(playerId);
                Player otherPlayer = Bukkit.getPlayer(other);
                boolean otherStillGone = otherPlayer == null || !otherPlayer.isOnline();
                if (otherStillGone) continue;   // both were flagged; stay frozen until the other returns
                long paused = now - state.pausedSinceMillis;
                Deque<DamageEntry> shifted = new ArrayDeque<>();
                for (DamageEntry entry : state.entries) {
                    shifted.addLast(new DamageEntry(entry.damagerId(), entry.atMillis() + paused, entry.rawDamage()));
                }
                state.entries.clear();
                state.entries.addAll(shifted);
                state.pausedSinceMillis = 0L;
            }
            toRevalidate.add(key);
        }
        if (!toRevalidate.isEmpty()) tick();   // immediate revalidation (range/damage) of unfrozen pairs
    }

    // ---- queries --------------------------------------------------------------------------------

    /** Is this player in PvP mode with anyone right now (frozen pairs count — they're still live)? */
    public boolean isInPvpMode(UUID playerId) {
        for (Map.Entry<PairKey, PairState> e : pairs.entrySet()) {
            if (e.getValue().active && e.getKey().contains(playerId)) return true;
        }
        return false;
    }

    /** Opponent name from this player's most recently-hit active pair — for broadcasts and DMs. */
    public String primaryOpponentName(UUID playerId) {
        String best = null;
        long bestAt = Long.MIN_VALUE;
        for (Map.Entry<PairKey, PairState> e : pairs.entrySet()) {
            PairState state = e.getValue();
            if (!state.active || !e.getKey().contains(playerId)) continue;
            DamageEntry last = state.entries.peekLast();
            long at = last == null ? 0L : last.atMillis();
            if (at > bestAt) {
                bestAt = at;
                best = e.getKey().low().equals(playerId) ? state.highName : state.lowName;
            }
        }
        return best;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static String nameOf(UUID id, String fallback) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : fallback;
    }

    private static boolean isNpc(Player player) {
        try {
            return Bukkit.getPluginManager().getPlugin("Citizens") != null
                    && CitizensAPI.getNPCRegistry().isNPC(player);
        } catch (Throwable t) {
            return false;
        }
    }
}
