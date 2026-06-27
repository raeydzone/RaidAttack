package com.raeyd.raidattack.raid;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Single source of truth for {@code [Raid] …} chat formatting. The user spec is:
 * <ul>
 *   <li>Every raid-system message starts with a fixed "[Raid]" tag (yellow).</li>
 *   <li>Bonus / reward messages render their body in <b>green</b> (positive feedback).</li>
 *   <li>Neutral / informational messages render their body in <b>white</b>.</li>
 *   <li>Negative messages (incoming attack, lost a turret, defender died) render in <b>red</b>.</li>
 * </ul>
 * Each variant has an optional sound parameter so callers can attach a positive ding to a bonus
 * or a negative thud to a setback; we play it entity-attached ({@code p.playSound(p, …)}) so the
 * sound source follows the player as they move (mono "in head" feel, no drift).
 */
public final class RaidMessages {

    private RaidMessages() {}

    /** Fixed prefix used for every [Raid] message. Yellow + bold so it stands apart from chat. */
    private static final Component TAG = Component.text("[Raid] ", NamedTextColor.YELLOW)
            .decorate(TextDecoration.BOLD);

    /** Positive / reward message — body in GREEN. Use for XP bonuses, raid-defeated, etc. */
    public static void bonus(Player to, String body) {
        bonus(to, body, null);
    }

    public static void bonus(Player to, String body, Sound sound) {
        send(to, body, NamedTextColor.GREEN, sound, 1.0f);
    }

    /**
     * Broadcast a positive {@code [Raid]} message (GREEN body) to the WHOLE server. Used for bonus
     * payouts — every player sees who earned what, prefixed with the raid tag.
     */
    public static void broadcastBonus(String body) {
        Bukkit.broadcast(TAG.append(Component.text(body, NamedTextColor.GREEN)));
    }

    /** Neutral message — body in WHITE. Use for status / progress / one-off info. */
    public static void info(Player to, String body) {
        info(to, body, null);
    }

    public static void info(Player to, String body, Sound sound) {
        send(to, body, NamedTextColor.WHITE, sound, 1.0f);
    }

    /** Negative message — body in RED. Use for incoming-attack warnings + bad-news pings. */
    public static void negative(Player to, String body) {
        negative(to, body, null);
    }

    public static void negative(Player to, String body, Sound sound) {
        send(to, body, NamedTextColor.RED, sound, 1.0f);
    }

    // -- internals ------------------------------------------------------------

    private static void send(Player to, String body, NamedTextColor colour, Sound sound, float pitch) {
        if (to == null || !to.isOnline()) return;
        Component msg = TAG.append(Component.text(body, colour));
        to.sendMessage(msg);
        if (sound != null) {
            // Entity-attached play so the sound follows the player. The location overload
            // snapshots position at play-time and "drifts behind" the player as they move.
            to.playSound(to, sound, 1.0f, pitch);
        }
    }
}
