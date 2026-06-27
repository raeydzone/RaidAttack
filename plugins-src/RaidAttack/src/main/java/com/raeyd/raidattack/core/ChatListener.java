package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Rewrites Paper's {@link AsyncChatEvent} to the format the user spec'd:
 *
 * <pre>
 *  Username: message                          -- player not in an alliance
 *  [Tag] Username: message                    -- player in an alliance ('[Tag]' coloured)
 *  [Alliance-Only Chat] [Tag] Username: message
 *                                             -- player in alliance + /a toggle ON, in gray.
 *                                                Recipients restricted to alliance members.
 * </pre>
 *
 * <p>Implementation detail: we install a custom {@link net.kyori.adventure.chat.ChatType.Bound}
 * renderer on the event when alliance-only mode is active, and we narrow the viewer set via
 * {@link AsyncChatEvent#viewers()} so non-alliance players don't see the message at all. For
 * the normal (global) path we just install our renderer and leave viewers untouched.
 *
 * <p>Console always sees every message — we add Bukkit's console sender to the viewer set on
 * the alliance-only branch so server logs stay complete.
 */
public final class ChatListener implements Listener {

    private final HomeSystemPlugin plugin;

    public ChatListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Alliance a = plugin.getAllianceManager().getOf(sender.getUniqueId());
        boolean allianceOnly = a != null
                && plugin.getAllianceManager().isAllianceOnlyChat(sender.getUniqueId());

        if (allianceOnly) {
            // Restrict viewers to alliance members + console. Non-members literally do not see
            // the message — closer to a whisper than a chat broadcast.
            narrowToAlliance(event, a);
        }

        final Alliance alliance = a;            // effectively-final for the lambda
        final boolean onlyMode = allianceOnly;
        event.renderer((source, sourceDisplayName, message, viewer) ->
                renderLine(sender, alliance, onlyMode, message));
    }

    private void narrowToAlliance(AsyncChatEvent event, Alliance a) {
        Set<UUID> ids = new HashSet<>(a.getMembers());
        Set<Audience> kept = new HashSet<>();
        Audience console = Bukkit.getConsoleSender();
        for (Audience v : event.viewers()) {
            if (v instanceof Player pv && ids.contains(pv.getUniqueId())) kept.add(v);
            else if (v == console) kept.add(v);
        }
        // Always keep the sender + console on the viewer list.
        kept.add(event.getPlayer());
        kept.add(console);
        event.viewers().clear();
        event.viewers().addAll(kept);
    }

    /**
     * Build the final rendered line. Public+static so other entrypoints (e.g. {@code /a <msg>}
     * one-shot) can produce the same look without going through the event renderer.
     */
    public static Component renderLine(Player sender, Alliance alliance, boolean allianceOnly,
                                       Component message) {
        Component prefix = Component.empty();
        if (allianceOnly) {
            prefix = prefix.append(Component.text("[Alliance-Only Chat] ", NamedTextColor.GRAY));
        }
        if (alliance != null) {
            prefix = prefix.append(Component.text("[" + alliance.getName() + "] ", alliance.getColor()));
        }
        Component nameAndMsg = Component.text(sender.getName(),
                        allianceOnly ? NamedTextColor.GRAY : NamedTextColor.WHITE)
                .append(Component.text(": ", allianceOnly ? NamedTextColor.GRAY : NamedTextColor.WHITE))
                .append(allianceOnly
                        ? message.colorIfAbsent(NamedTextColor.GRAY)
                        : message);
        return prefix.append(nameAndMsg);
    }

    /**
     * Emit a one-shot alliance-only message without toggling state. Used by {@code /a <message>}.
     * Mirrors the formatting of the event-driven path so the chat history looks uniform.
     */
    public static void deliverAllianceOnly(HomeSystemPlugin plugin, Player sender, Alliance a, String msg) {
        Component line = renderLine(sender, a, true, Component.text(msg));
        for (UUID id : a.getMembers()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(line);
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }
}
