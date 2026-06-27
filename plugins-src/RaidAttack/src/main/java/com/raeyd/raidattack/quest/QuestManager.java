package com.raeyd.raidattack.quest;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-player progress store + display for the RaidAttack {@link Quest} achievements.
 *
 * <p>Progress is held in memory ({@code UUID → Quest → count}, clamped to each quest's target) and
 * persisted to the {@code world.quest_progress} / {@code world.quest_rewarded} tables. Writes are
 * batched: a change flags the store dirty and a ~1/min timer flushes a snapshot to the DB off the
 * main thread (plus a synchronous flush on {@link #stop()}), so the 500-raider-kill counter doesn't
 * hammer the database. Crossing a quest's target while the player is online pops a completion toast.
 *
 * <p>All access is from the main server thread (events + scheduler), so no synchronisation.
 */
public final class QuestManager {

    private static final int BAR_LEN = 20;
    private static final long SAVE_INTERVAL_TICKS = 1200L;   // flush dirty state ~once a minute

    /** One-time grand reward for completing every quest. */
    private static final int ALL_DONE_XP = 5000;
    private static final int ALL_DONE_DIAMONDS = 32;
    private static final int ALL_DONE_NETHERITE = 1;

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    private final Map<UUID, Map<Quest, Integer>> progress = new HashMap<>();
    /** Players who've already collected the "all quests done" reward (so it pays exactly once). */
    private final Set<UUID> rewarded = new HashSet<>();
    private boolean dirty = false;
    private BukkitTask saveTask;

    public QuestManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    // -- lifecycle ------------------------------------------------------------

    public void load() {
        progress.clear();
        rewarded.clear();
        rewarded.addAll(worldDb.loadQuestRewarded());
        Map<UUID, Map<String, Integer>> raw = worldDb.loadQuestProgress();
        for (Map.Entry<UUID, Map<String, Integer>> e : raw.entrySet()) {
            Map<Quest, Integer> m = new EnumMap<>(Quest.class);
            for (Quest q : Quest.values()) {
                Integer v = e.getValue().get(q.key());
                if (v != null && v > 0) m.put(q, Math.min(v, q.target()));
            }
            if (!m.isEmpty()) progress.put(e.getKey(), m);
        }
    }

    /** Snapshot the in-memory state with string quest keys, so it can be written off-thread. */
    private Map<UUID, Map<String, Integer>> snapshotProgress() {
        Map<UUID, Map<String, Integer>> snap = new HashMap<>();
        for (Map.Entry<UUID, Map<Quest, Integer>> e : progress.entrySet()) {
            Map<String, Integer> m = new HashMap<>();
            for (Map.Entry<Quest, Integer> qe : e.getValue().entrySet()) m.put(qe.getKey().key(), qe.getValue());
            snap.put(e.getKey(), m);
        }
        return snap;
    }

    /** Synchronous flush to the DB — used on {@link #stop()} (server shutdown). */
    public void save() {
        worldDb.saveQuests(snapshotProgress(), new HashSet<>(rewarded));
        dirty = false;
    }

    public void start() {
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!dirty) return;
            dirty = false;
            // Snapshot on the main thread, write off it.
            Map<UUID, Map<String, Integer>> snap = snapshotProgress();
            Set<UUID> rw = new HashSet<>(rewarded);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> worldDb.saveQuests(snap, rw));
        }, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    public void stop() {
        if (saveTask != null) { saveTask.cancel(); saveTask = null; }
        if (dirty) save();
    }

    // -- progress API ---------------------------------------------------------

    public int get(UUID id, Quest q) {
        if (id == null) return 0;
        Map<Quest, Integer> m = progress.get(id);
        return m == null ? 0 : m.getOrDefault(q, 0);
    }

    public boolean isComplete(UUID id, Quest q) {
        return get(id, q) >= q.target();
    }

    /** Set absolute progress (clamped to [0, target]); fires a completion toast on the crossing. */
    public void set(UUID id, Quest q, int value) {
        if (id == null) return;
        int clamped = Math.max(0, Math.min(value, q.target()));
        int old = get(id, q);
        if (clamped == old) return;
        progress.computeIfAbsent(id, k -> new EnumMap<>(Quest.class)).put(q, clamped);
        dirty = true;
        if (old < q.target() && clamped >= q.target()) {
            notifyComplete(id, q);
            maybeGrantAllReward(id);       // was this the final quest?
        }
    }

    public void add(UUID id, Quest q, int delta) {
        if (delta != 0) set(id, q, get(id, q) + delta);
    }

    /** Convenience for single-step quests (and a no-op once already complete). */
    public void complete(UUID id, Quest q) {
        set(id, q, q.target());
    }

    private void notifyComplete(UUID id, Quest q) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        p.sendMessage(ChatColor.GOLD + "✔ Quest complete: " + ChatColor.GREEN + q.title() + ChatColor.GOLD + "!");
        p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
    }

    /**
     * Grand reward for finishing every quest: paid exactly once per player (tracked in {@link #rewarded}).
     * Only granted while the player is online (so we can hand over items + XP); re-checked whenever they
     * open {@code /ra quests}, which covers a final quest that ticked over while they were offline.
     */
    private void maybeGrantAllReward(UUID id) {
        if (id == null || rewarded.contains(id)) return;
        for (Quest q : Quest.values()) {
            if (!isComplete(id, q)) return;        // something still outstanding
        }
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;                     // offline — try again when they next view their quests
        rewarded.add(id);
        dirty = true;
        p.giveExp(ALL_DONE_XP);
        giveOrDrop(p, new ItemStack(Material.DIAMOND, ALL_DONE_DIAMONDS));
        giveOrDrop(p, new ItemStack(Material.NETHERITE_INGOT, ALL_DONE_NETHERITE));
        p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✦ ALL RaidAttack quests complete! ✦");
        p.sendMessage(ChatColor.GREEN + "Grand reward: " + ChatColor.WHITE + "+" + ALL_DONE_XP + " XP, "
                + ALL_DONE_DIAMONDS + " diamonds, " + ALL_DONE_NETHERITE + " netherite ingot.");
    }

    /** Add a stack to the player's inventory, dropping any overflow at their feet. */
    private static void giveOrDrop(Player p, ItemStack stack) {
        for (ItemStack leftover : p.getInventory().addItem(stack).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), leftover);
        }
    }

    // -- rendering ------------------------------------------------------------

    /** Build the {@code /ra quests} screen: a header plus, per quest, a title line and a bar line. */
    public List<String> render(UUID id) {
        maybeGrantAllReward(id);    // catches a final quest that completed while the player was offline
        List<String> out = new ArrayList<>();
        int done = 0;
        for (Quest q : Quest.values()) {
            int prog = Math.min(get(id, q), q.target());
            boolean complete = prog >= q.target();
            if (complete) done++;
            String icon  = complete ? (ChatColor.GREEN + "✔ ") : (ChatColor.GRAY + "• ");
            String title = (complete ? ChatColor.GREEN : ChatColor.WHITE) + q.title();
            String count = q.target() > 1
                    ? " " + ChatColor.GRAY + "[" + ChatColor.WHITE + prog
                      + ChatColor.GRAY + "/" + ChatColor.WHITE + q.target() + ChatColor.GRAY + "]"
                    : "";
            out.add(icon + title + count);
            out.add("   " + bar(prog, q.target()));
        }
        out.add(0, ChatColor.GOLD + "" + ChatColor.BOLD + "RaidAttack Quests "
                + ChatColor.GRAY + "(" + ChatColor.WHITE + done
                + ChatColor.GRAY + "/" + Quest.values().length + " done)");
        return out;
    }

    /**
     * Bedrock-form rendering: clean one-line-per-quest list grouped into "in progress" and
     * "completed", with no wide bar glyphs (which wrap/misalign in a form's proportional font) and
     * no misleading {@code 0%} / {@code [0/1]} counters on single-step quests. Used by the {@code /ra}
     * Bedrock menu; the chat {@link #render} above stays for Java players.
     */
    public List<String> renderForm(UUID id) {
        maybeGrantAllReward(id);
        int done = 0;
        for (Quest q : Quest.values()) if (isComplete(id, q)) done++;
        List<String> out = new ArrayList<>();
        out.add(ChatColor.GOLD + "" + ChatColor.BOLD + "QUESTS " + ChatColor.RESET
                + ChatColor.DARK_GRAY + "(" + ChatColor.WHITE + done
                + ChatColor.GRAY + "/" + ChatColor.WHITE + Quest.values().length
                + ChatColor.DARK_GRAY + " done)");
        out.add("");
        // One line per quest: a status mark, the title, and (for multi-step quests) the progress
        // count inline at the end as [x/y]. Single-step quests show no counter.
        for (Quest q : Quest.values()) {
            int prog = Math.min(get(id, q), q.target());
            boolean complete = prog >= q.target();
            String mark  = complete ? (ChatColor.GREEN + "✔ ") : (ChatColor.GRAY + "• ");
            String title = (complete ? ChatColor.GRAY : ChatColor.WHITE) + q.title();
            String count = q.target() > 1
                    ? "  " + ChatColor.DARK_GRAY + "[" + ChatColor.GRAY + prog
                      + ChatColor.DARK_GRAY + "/" + ChatColor.GRAY + q.target() + ChatColor.DARK_GRAY + "]"
                    : "";
            out.add(mark + title + count);
        }
        return out;
    }

    /** A two-tone solid bar: green filled run, dark-gray remainder, trailing percentage. */
    private static String bar(int prog, int target) {
        double frac = target <= 0 ? 1.0 : (double) prog / target;
        frac = Math.max(0.0, Math.min(1.0, frac));
        int filled = (int) Math.round(frac * BAR_LEN);
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GREEN);
        for (int i = 0; i < filled; i++) sb.append('█');
        sb.append(ChatColor.DARK_GRAY);
        for (int i = filled; i < BAR_LEN; i++) sb.append('█');
        sb.append(' ').append(ChatColor.GRAY).append((int) Math.round(frac * 100)).append('%');
        return sb.toString();
    }
}
