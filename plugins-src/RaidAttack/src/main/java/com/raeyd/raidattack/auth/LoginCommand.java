package com.raeyd.raidattack.auth;

import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /login <password>} — the only command a frozen (un-authenticated) player may run. */
public final class LoginCommand implements CommandExecutor, TabCompleter {

    private final AuthManager manager;

    public LoginCommand(AuthManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can log in.");
            return true;
        }
        if (!manager.isInLimbo(player.getUniqueId())) {
            player.sendMessage(Component.text("You are already logged in.", NamedTextColor.GREEN));
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /login <password>", NamedTextColor.RED));
            return true;
        }
        manager.attemptLogin(player, String.join(" ", args));
        return true;
    }

    /**
     * Suppress Bukkit's default completion. With no completer, the first argument suggests online
     * player names (a password field should never do that, and it leaks who's online to a frozen,
     * un-authenticated player). Returning an empty list shows nothing.
     */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
