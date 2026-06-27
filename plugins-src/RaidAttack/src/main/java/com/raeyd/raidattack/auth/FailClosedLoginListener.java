package com.raeyd.raidattack.auth;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Registered only when the real gate failed to initialize (e.g. DB misconfigured). The gate was
 * requested ({@code auth.enabled: true}), so we must NOT leave the server open — deny every login
 * instead. The rest of the plugin (raids, turrets, claims) still loads normally.
 */
public final class FailClosedLoginListener implements Listener {

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Raid Attack login is temporarily unavailable. Please try again later.", NamedTextColor.RED));
    }
}
