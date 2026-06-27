package com.raeyd.raidattack.season;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * The season timeline. Each entry unlocks a piece of content at an exact <b>UTC</b> instant.
 *
 * <p>The dates here are <b>defaults</b>. They can be overridden per-event from {@code config.yml}
 * (the {@code events:} section, keyed by {@link #configKey()}): {@link EventManager} reads the
 * config on enable and falls back to these constants when a value is missing or unreadable. "Enabled"
 * is a pure function of the wall clock ({@code now >= when}), so there is no persisted on/off flag to
 * drift across restarts. Times are always interpreted and formatted in {@link ZoneOffset#UTC} — never
 * the host's local zone — so they read identically on the Windows dev box and the Linux production
 * server. The board shows the date unlabelled; the relative "(in …)" countdown is the timezone-
 * agnostic source of truth for at-a-glance timing.
 */
public enum SeasonEvent {

    /** Display-only anchor — no gameplay effect, rendered white on the board. */
    SERVER_START("Server Start",    "server_start", utc(2026, 7,  1, 10, 0), NamedTextColor.WHITE,      Effect.NONE),
    RAIDING     ("Raiding Enabled", "raiding",      utc(2026, 7,  7, 10, 0), NamedTextColor.GOLD,       Effect.RAIDING),
    NETHER      ("Nether Enabled",  "nether",       utc(2026, 7, 13, 10, 0), NamedTextColor.RED,        Effect.NETHER),
    END         ("The End Enabled", "the_end",      utc(2026, 7, 26, 10, 0), TextColor.color(0xFF7BAC), Effect.END);

    /** What flipping this event on actually gates. {@code NONE} = display-only anchor. */
    public enum Effect { NONE, RAIDING, NETHER, END }

    /** Board/display formatter, e.g. {@code "01 July 2026 10:00"} (UTC, unlabelled). */
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm", Locale.ENGLISH);

    /** Accepted date-TIME input formats for the config (all interpreted as UTC). */
    private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",      Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",   Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm",    Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy HH:mm",    Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm",     Locale.ENGLISH),
    };
    /** Accepted date-ONLY input formats (treated as 00:00 UTC). */
    private static final DateTimeFormatter[] DATE_ONLY_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd",  Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy",  Locale.ENGLISH),
    };

    private final String displayName;
    private final String configKey;
    private final Instant when;
    private final TextColor color;
    private final Effect effect;

    SeasonEvent(String displayName, String configKey, Instant when, TextColor color, Effect effect) {
        this.displayName = displayName;
        this.configKey = configKey;
        this.when = when;
        this.color = color;
        this.effect = effect;
    }

    public String displayName() { return displayName; }
    /** Key under {@code events:} in config.yml that overrides this event's date. */
    public String configKey()   { return configKey; }
    public Instant when()       { return when; }
    /** Hardcoded default fire time (epoch ms). Effective time is owned by {@link EventManager}. */
    public long whenMillis()    { return when.toEpochMilli(); }
    public TextColor color()    { return color; }
    public Effect effect()      { return effect; }

    /**
     * Format an absolute epoch-milli timestamp in UTC (unlabelled), e.g. {@code "01 July 2026 10:00"}.
     */
    static String formatUtc(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC).format(FMT);
    }

    /**
     * Parse a user-entered date/time (from config.yml) into epoch millis, interpreting it as UTC.
     * Accepted (all UTC): {@code 2026-07-01 10:00}, {@code 2026-07-01 10:00:00}, {@code 2026-07-01T10:00},
     * {@code 01 July 2026 10:00}, or a bare date {@code 2026-07-01} (treated as 00:00). A trailing
     * {@code Z} / {@code UTC} / {@code +00:00} is tolerated and ignored. Returns {@code null} if nothing
     * matches (the caller then keeps the hardcoded default).
     */
    static Long parseUtcMillis(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceFirst("(?i)\\s*(z|utc|\\+00:?00)\\s*$", "").trim();
        if (s.isEmpty()) return null;
        for (DateTimeFormatter f : DATE_TIME_FORMATS) {
            try { return LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli(); }
            catch (DateTimeParseException ignored) { /* try next */ }
        }
        for (DateTimeFormatter f : DATE_ONLY_FORMATS) {
            try { return LocalDate.parse(s, f).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(); }
            catch (DateTimeParseException ignored) { /* try next */ }
        }
        return null;
    }

    private static Instant utc(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC);
    }
}
