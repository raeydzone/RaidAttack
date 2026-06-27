package com.raeyd.raidattack.moderation;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Executes the routed moderation commands ({@code /ban}, {@code /kick}, {@code /unban}) and
 * appends a tamper-friendly audit line to {@code moderation.log} for every action — capturing
 * <em>when</em>, <em>who acted</em>, <em>who was affected</em> and <em>why</em>.
 *
 * <p>Permission gating lives in {@link ModerationListener}; by the time a method here runs the
 * caller has already been authorised. This class only validates the <em>inputs</em> (duration
 * syntax, reason length, target existence).
 */
public final class ModerationService {

    /** Ban reason length window, inclusive. */
    public static final int REASON_MIN = 10;
    public static final int REASON_MAX = 40;

    /** number + single unit letter; unit is CASE-SENSITIVE (m=month vs M=minute). */
    private static final Pattern DURATION_TOKEN = Pattern.compile("(\\d+)([a-zA-Z])");
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HomeSystemPlugin plugin;
    private final File logFile;

    public ModerationService(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "moderation.log");
    }

    // -- command result -------------------------------------------------------

    /** Simple ok/message holder so the listener can colour the feedback uniformly. */
    public record Result(boolean ok, String message) {}

    // -- duration parsing -----------------------------------------------------

    /** Parsed duration with its millisecond span and a compact human summary (e.g. "45d"). */
    public static final class ParsedDuration {
        public final long millis;
        public final String human;
        ParsedDuration(long millis, String human) { this.millis = millis; this.human = human; }
    }

    /**
     * Parse a duration like {@code 2w1m} into a span. Units are case-sensitive:
     * <pre> m = month (31d)   w = week (7d)   d = day   h = hour   M = minute   s = second </pre>
     * Tokens may appear in any order and are simply summed (so {@code 2w1m} = 14d + 31d = 45d).
     * Unrecognised unit letters are ignored. Returns {@code null} if no valid token is found.
     */
    public static ParsedDuration parseDuration(String input) {
        if (input == null || input.isBlank()) return null;
        Matcher mt = DURATION_TOKEN.matcher(input);
        long totalSeconds = 0;
        boolean any = false;
        while (mt.find()) {
            long n;
            try { n = Long.parseLong(mt.group(1)); } catch (NumberFormatException ex) { continue; }
            switch (mt.group(2)) {               // NOTE: do not lowercase — m vs M differ
                case "m" -> { totalSeconds += n * 31L * 24 * 3600; any = true; }   // month
                case "w" -> { totalSeconds += n * 7L * 24 * 3600;  any = true; }   // week
                case "d" -> { totalSeconds += n * 24L * 3600;      any = true; }   // day
                case "h" -> { totalSeconds += n * 3600L;           any = true; }   // hour
                case "M" -> { totalSeconds += n * 60L;             any = true; }   // minute
                case "s" -> { totalSeconds += n;                   any = true; }   // second
                default  -> { /* unknown unit — ignore per spec */ }
            }
        }
        if (!any || totalSeconds <= 0) return null;
        return new ParsedDuration(totalSeconds * 1000L, humanize(totalSeconds));
    }

    private static String humanize(long seconds) {
        long d = seconds / 86400; seconds %= 86400;
        long h = seconds / 3600;  seconds %= 3600;
        long m = seconds / 60;    long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (s > 0) sb.append(s).append("s");
        String out = sb.toString().trim();
        return out.isEmpty() ? "0s" : out;
    }

    // -- target resolution ----------------------------------------------------

    public static final class ResolvedTarget {
        public final UUID uuid;
        public final String name;
        public final Player online;   // null if offline
        ResolvedTarget(UUID uuid, String name, Player online) {
            this.uuid = uuid; this.name = name; this.online = online;
        }
    }

    /** Resolve a name → (uuid,name): online exact first, then claim name-cache, then offline. */
    public ResolvedTarget resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new ResolvedTarget(online.getUniqueId(), online.getName(), online);
        UUID cached = plugin.getClaimManager().resolveUUID(name);
        if (cached != null) {
            return new ResolvedTarget(cached, plugin.getClaimManager().resolveName(cached),
                    Bukkit.getPlayer(cached));
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore() || off.isOnline()) {
            String resolved = off.getName() != null ? off.getName() : name;
            return new ResolvedTarget(off.getUniqueId(), resolved, off.getPlayer());
        }
        return null;
    }

    // -- actions --------------------------------------------------------------

    /** {@code /ban <player> <duration> <reason>}. */
    public Result ban(CommandSender actor, String targetName, String durationStr, String reason) {
        ResolvedTarget t = resolveTarget(targetName);
        if (t == null) return new Result(false, "Unknown player '" + targetName + "'.");

        ParsedDuration dur = parseDuration(durationStr);
        if (dur == null) {
            return new Result(false, "Invalid duration '" + durationStr
                    + "'. Units: m=month w=week d=day h=hour M=minute s=second (e.g. 2w1d).");
        }
        if (reason == null) reason = "";
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            return new Result(false, "Reason must be " + REASON_MIN + "-" + REASON_MAX
                    + " characters (yours is " + reason.length() + ").");
        }

        Date expires = new Date(System.currentTimeMillis() + dur.millis);
        // Unified ban: write the eligibility flag the login gate already enforces (not the vanilla
        // ban list). For a registered player this denies their next join with the reason; if they
        // have no account (never registered) it can't persist, but they can't join anyway.
        plugin.getWorldDatabase().banAccount(t.uuid, reason, expires.toInstant());

        if (t.online != null) {
            t.online.kick(Component.text("You have been banned.", NamedTextColor.RED)
                    .append(Component.newline())
                    .append(Component.text("Reason: ", NamedTextColor.GRAY))
                    .append(Component.text(reason, NamedTextColor.WHITE))
                    .append(Component.newline())
                    .append(Component.text("Expires: ", NamedTextColor.GRAY))
                    .append(Component.text(STAMP.format(expires.toInstant()), NamedTextColor.WHITE)));
        }
        log("BAN", actorName(actor), t.name, t.uuid,
                "duration=" + dur.human + " until=" + STAMP.format(expires.toInstant())
                        + " reason=\"" + reason + "\"");
        return new Result(true, "Banned " + t.name + " for " + dur.human + " - " + reason);
    }

    /** {@code /unban <player>}. */
    public Result unban(CommandSender actor, String targetName) {
        ResolvedTarget t = resolveTarget(targetName);
        if (t == null) return new Result(false, "Unknown player '" + targetName + "'.");
        int removed = plugin.getWorldDatabase().unbanAccount(t.uuid);
        if (removed == 0) return new Result(false, "'" + t.name + "' is not banned.");
        log("UNBAN", actorName(actor), t.name, t.uuid, "");
        return new Result(true, "Unbanned " + t.name + ".");
    }

    /** {@code /kick <player> <reason>}. Target must be online; reason is length-validated like a ban. */
    public Result kick(CommandSender actor, String targetName, String reason) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return new Result(false, "Player '" + targetName + "' is not online.");
        if (reason == null) reason = "";
        if (reason.length() < REASON_MIN || reason.length() > REASON_MAX) {
            return new Result(false, "Reason must be " + REASON_MIN + "-" + REASON_MAX
                    + " characters (yours is " + reason.length() + ").");
        }
        target.kick(Component.text("You have been kicked.", NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.text("Reason: ", NamedTextColor.GRAY))
                .append(Component.text(reason, NamedTextColor.WHITE)));
        log("KICK", actorName(actor), target.getName(), target.getUniqueId(), "reason=\"" + reason + "\"");
        return new Result(true, "Kicked " + target.getName() + " - " + reason);
    }

    /** Names with an active in-game ban (for {@code /unban} tab-completion). */
    public List<String> bannedNames() {
        List<String> out = new ArrayList<>();
        for (UUID id : plugin.getWorldDatabase().activelyBannedUuids()) {
            out.add(plugin.getClaimManager().resolveName(id));
        }
        return out;
    }

    // -- helpers --------------------------------------------------------------

    private static String actorName(CommandSender actor) {
        if (actor instanceof Player p) return p.getName() + "(" + p.getUniqueId() + ")";
        return "CONSOLE";
    }

    private synchronized void log(String action, String actor, String targetName,
                                  UUID targetUuid, String extra) {
        String line = "[" + STAMP.format(Instant.now()) + "] "
                + padRight(action, 5)
                + " actor=" + actor
                + " target=" + targetName + "(" + targetUuid + ")"
                + (extra == null || extra.isEmpty() ? "" : " " + extra);
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(logFile, true))) {
                pw.println(line);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write moderation.log: " + e.getMessage());
        }
        plugin.getLogger().info("[MOD] " + line);
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        char[] pad = new char[width - s.length()];
        Arrays.fill(pad, ' ');
        return s + new String(pad);
    }
}
