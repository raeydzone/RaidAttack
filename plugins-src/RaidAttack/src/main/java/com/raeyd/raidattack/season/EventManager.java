package com.raeyd.raidattack.season;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.EnumMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drives the season timeline: answers "is X unlocked yet?" and broadcasts the pre-unlock countdown.
 *
 * <p>Unlock state itself is purely time-based (see {@link SeasonEvent#isEnabled()}) — this manager
 * owns only the announcement side. A 1 Hz task watches each coded event and broadcasts as it crosses
 * the milestones <b>4h / 1h / 30m / 15m / 5m / 1m</b>, then <b>every second for the final 15 s</b>,
 * then <b>"now"</b>. Each milestone fires exactly once (detected by the second-boundary crossing),
 * and events already past at boot fire nothing (silent catch-up) — the lock is simply already open.
 */
public final class EventManager {

    /** Permission that lets a player bypass every season lock (dimensions + raiding). Dev/admin. */
    public static final String BYPASS_PERM = "homesystem.events.bypass";

    /** Countdown milestones, in SECONDS before the event, descending. */
    private static final long[] THRESHOLDS = {
            4 * 3600, 3600, 1800, 900, 300, 60,
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1
    };

    private static final Component TAG = Component.text("[Event] ", NamedTextColor.AQUA)
            .decorate(TextDecoration.BOLD);

    private final HomeSystemPlugin plugin;
    private BukkitTask task;
    /** Last observed whole-second remaining per event, for one-shot milestone-crossing detection. */
    private final Map<SeasonEvent, Long> lastRemainingSec = new EnumMap<>(SeasonEvent.class);
    /** Dev override fire-times (epoch ms). Present => use instead of the hardcoded season date.
     *  In-memory only, so a restart wipes it back to the real schedule. */
    private final Map<SeasonEvent, Long> overrideMillis = new EnumMap<>(SeasonEvent.class);
    /** Per-event unlock times loaded from config.yml's {@code events:} section (epoch ms). Takes
     *  precedence over the hardcoded {@link SeasonEvent} default, but yields to a dev override. */
    private final Map<SeasonEvent, Long> configuredMillis = new EnumMap<>(SeasonEvent.class);

    public EventManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadConfig();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);   // 1 Hz
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        lastRemainingSec.clear();
    }

    // -- effective time (respects dev override) -------------------------------

    /** Effective fire time for an event. Precedence: dev override &gt; config.yml &gt; hardcoded default. */
    public long whenMillis(SeasonEvent ev) {
        Long o = overrideMillis.get(ev);
        if (o != null) return o;
        Long c = configuredMillis.get(ev);
        if (c != null) return c;
        return ev.whenMillis();
    }

    /**
     * Load per-event unlock times from config.yml's {@code events:} section, keyed by
     * {@link SeasonEvent#configKey()}. Values are UTC date/times (see {@link SeasonEvent#parseUtcMillis}).
     * A missing or unparseable value falls back to the hardcoded {@link SeasonEvent} default, with a
     * warning — so a typo in the file can never brick the season. Called once on {@link #start()}.
     */
    public void loadConfig() {
        configuredMillis.clear();
        for (SeasonEvent ev : SeasonEvent.values()) {
            Object raw = plugin.getConfig().get("events." + ev.configKey());
            if (raw == null) continue;                                   // no entry -> keep default
            Long ms = (raw instanceof java.util.Date d)
                    ? d.getTime()                                        // YAML parsed a full timestamp (UTC)
                    : SeasonEvent.parseUtcMillis(String.valueOf(raw));
            if (ms == null) {
                plugin.getLogger().warning("config.yml events." + ev.configKey() + " = \"" + raw
                        + "\" is not a valid UTC date (expected e.g. 2026-07-01 10:00); using default "
                        + SeasonEvent.formatUtc(ev.whenMillis()) + ".");
                continue;
            }
            configuredMillis.put(ev, ms);
            plugin.getLogger().info("Event '" + ev.displayName() + "' scheduled for "
                    + SeasonEvent.formatUtc(ms) + " UTC (from config.yml).");
        }
    }

    public boolean isEnabled(SeasonEvent ev)     { return System.currentTimeMillis() >= whenMillis(ev); }
    public long    remainingMillis(SeasonEvent ev) { return whenMillis(ev) - System.currentTimeMillis(); }
    public String  formattedWhen(SeasonEvent ev)  { return SeasonEvent.formatUtc(whenMillis(ev)); }

    public boolean raidingEnabled() { return isEnabled(SeasonEvent.RAIDING); }
    public boolean netherEnabled()  { return isEnabled(SeasonEvent.NETHER); }
    public boolean endEnabled()     { return isEnabled(SeasonEvent.END); }

    public boolean isDevOverrideActive() { return !overrideMillis.isEmpty(); }

    /**
     * Dev/testing: compress the whole timeline to fire from NOW — Raiding +5 min, Nether +10 min,
     * The End +15 min — until the next server restart (overrides live in memory only). Re-baselines
     * the countdown so the freshly-compressed milestones announce going forward instead of all at once.
     */
    public void applyDevOverride() {
        long now = System.currentTimeMillis();
        overrideMillis.put(SeasonEvent.SERVER_START, now);
        overrideMillis.put(SeasonEvent.RAIDING, now + 5L  * 60_000L);
        overrideMillis.put(SeasonEvent.NETHER,  now + 10L * 60_000L);
        overrideMillis.put(SeasonEvent.END,     now + 15L * 60_000L);
        lastRemainingSec.clear();
    }

    /** True if the player may ignore season locks (dev/admin). */
    public boolean canBypass(Player p) {
        return p != null && p.hasPermission(BYPASS_PERM);
    }

    // -- countdown ------------------------------------------------------------

    private void tick() {
        for (SeasonEvent ev : SeasonEvent.values()) {
            if (ev.effect() == SeasonEvent.Effect.NONE) continue;   // server-start anchor: no countdown
            long curSec = (long) Math.ceil(remainingMillis(ev) / 1000.0);
            Long prev = lastRemainingSec.put(ev, curSec);
            if (prev == null) continue;            // first observation — don't retro-announce

            if (prev > 0 && curSec <= 0) {         // just crossed zero → it's live
                broadcastLive(ev);
                continue;
            }
            for (long t : THRESHOLDS) {            // announce every milestone we crossed this tick
                if (prev > t && curSec <= t) broadcastCountdown(ev, t);
            }
        }
    }

    private void broadcastCountdown(SeasonEvent ev, long thresholdSec) {
        Component msg = TAG
                .append(Component.text(ev.displayName(), ev.color()).decorate(TextDecoration.BOLD))
                .append(Component.text(" in " + humanThreshold(thresholdSec) + ".", NamedTextColor.GRAY));
        Bukkit.broadcast(msg);
        float pitch = thresholdSec <= 15 ? 1.6f : 1.0f;   // sharper ticks in the final seconds
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
        }
    }

    private void broadcastLive(SeasonEvent ev) {
        Component name = Component.text(ev.displayName(), ev.color()).decorate(TextDecoration.BOLD);
        Bukkit.broadcast(TAG.append(name).append(Component.text(" is now LIVE!", NamedTextColor.GREEN)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            p.showTitle(Title.title(name, Component.text("is now enabled!", NamedTextColor.GRAY)));
        }
    }

    /** "4 hours" / "30 minutes" / "1 minute" / "15 seconds" / "1 second". */
    static String humanThreshold(long sec) {
        if (sec >= 3600 && sec % 3600 == 0) {
            long h = sec / 3600; return h + (h == 1 ? " hour" : " hours");
        }
        if (sec >= 60 && sec % 60 == 0) {
            long m = sec / 60; return m + (m == 1 ? " minute" : " minutes");
        }
        return sec + (sec == 1 ? " second" : " seconds");
    }

    /** Compact relative duration for the board / deny messages: "4d 6h", "6h 12m", "12m 30s", "45s". */
    public static String relative(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
