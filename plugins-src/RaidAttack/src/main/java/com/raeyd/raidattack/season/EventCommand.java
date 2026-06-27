package com.raeyd.raidattack.season;

import com.raeyd.raidattack.HomeSystemPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /event} — renders the season timeline: each unlock's absolute UTC date plus a live relative
 * countdown {@code "(in 4d 6h)"}, or a green ENABLED marker once it has passed. Read-only; anyone
 * may run it.
 *
 * <p>{@code /event dev} (op / {@code homesystem.dev} only) compresses the whole timeline to fire
 * from now — Raiding +5 min, Nether +10 min, The End +15 min — until the next restart, so the
 * countdown + unlock flow can be tested without editing the hardcoded dates.
 */
public final class EventCommand implements CommandExecutor {

    private final HomeSystemPlugin plugin;

    public EventCommand(HomeSystemPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("dev")) {
            if (sender instanceof Player p && !p.hasPermission("homesystem.dev") && !p.isOp()) {
                p.sendMessage(Component.text("You don't have permission for /event dev.", NamedTextColor.RED));
                return true;
            }
            plugin.getEventManager().applyDevOverride();
            sender.sendMessage(Component.text(
                    "Dev override active (until restart): Raiding in 5m, Nether in 10m, The End in 15m.",
                    NamedTextColor.LIGHT_PURPLE).decorate(TextDecoration.BOLD));
            renderBoard(sender);
            return true;
        }
        renderBoard(sender);
        return true;
    }

    private void renderBoard(CommandSender sender) {
        EventManager em = plugin.getEventManager();
        sender.sendMessage(Component.text("═══════ Season Events ═══════", NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD));
        if (em.isDevOverrideActive()) {
            sender.sendMessage(Component.text("(dev override active — compressed test timeline)",
                    NamedTextColor.DARK_GRAY));
        }
        for (SeasonEvent ev : SeasonEvent.values()) {
            Component name = Component.text(ev.displayName(), ev.color()).decorate(TextDecoration.BOLD);
            Component line = Component.text(" • ", NamedTextColor.DARK_GRAY)
                    .append(name)
                    .append(Component.text(" — ", NamedTextColor.GRAY))
                    .append(Component.text(em.formattedWhen(ev), NamedTextColor.WHITE));
            if (em.isEnabled(ev)) {
                // Uniform "active" marker for every event that has fired (anchor + unlocks alike).
                line = line.append(Component.text("  ✔ active", NamedTextColor.GREEN));
            } else {
                line = line.append(Component.text(
                        "  (in " + EventManager.relative(em.remainingMillis(ev)) + ")",
                        NamedTextColor.YELLOW));
            }
            sender.sendMessage(line);
        }
    }
}
