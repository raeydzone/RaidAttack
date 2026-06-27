package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.data.WorldDatabase;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Two armour behaviours, both driven off {@link PlayerItemDamageEvent}:
 *
 * <ol>
 *   <li><b>+150% durability buff (always on, for everyone).</b> Only ~40% (2-in-5) of each worn
 *       armour piece's durability damage is actually applied, so armour lasts 2.5x as long. We do
 *       this by reducing the event's damage rather than rewriting the item's max-durability
 *       component, so it works on enchanted/renamed gear and leaves the vanilla durability bar
 *       intact (and reversible).</li>
 *   <li><b>Low-durability indicator (per-player, default OFF).</b> When a player has it enabled,
 *       the first time a worn piece crosses below 20% remaining durability they get a one-shot
 *       warning naming the item plus a bell sound.</li>
 * </ol>
 *
 * <p>The per-player toggle persists to the {@code world.armor_indicator} table (presence of a row
 * = ON; absence = off).
 */
public final class ArmorDurabilityManager implements Listener {

    /** Fraction of durability damage actually applied, on average. Armour lifetime = 1/this:
     *  0.40 -> armour lasts 2.5x as long (+150%). (0.667 was the old +50%.) Tune freely. */
    private static final double KEEP_FRACTION = 0.40;
    /** Warn when remaining durability drops below this fraction. */
    private static final double WARN_THRESHOLD = 0.20;

    private final HomeSystemPlugin plugin;
    private final WorldDatabase worldDb;
    private final Set<UUID> indicatorOn = new HashSet<>();

    public ArmorDurabilityManager(HomeSystemPlugin plugin, WorldDatabase worldDb) {
        this.plugin = plugin;
        this.worldDb = worldDb;
    }

    // -- per-player toggle ----------------------------------------------------

    public boolean isIndicatorOn(UUID id) {
        return indicatorOn.contains(id);
    }

    /** Set the indicator state for a player; returns the new state. Persists the single change
     *  to the DB off the main thread on change. */
    public boolean setIndicator(UUID id, boolean on) {
        boolean changed = on ? indicatorOn.add(id) : indicatorOn.remove(id);
        if (changed) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> worldDb.setArmorIndicator(id, on));
        }
        return on;
    }

    // -- buff + warning -------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent e) {
        ItemStack item = e.getItem();
        if (item == null || !isArmor(item.getType())) return;

        int original = e.getDamage();
        if (original <= 0) return;

        // +150% durability buff: apply only ~40% of the damage points (armour lasts 2.5x as long).
        int applied = 0;
        for (int i = 0; i < original; i++) {
            if (ThreadLocalRandom.current().nextDouble() < KEEP_FRACTION) applied++;
        }
        if (applied <= 0) {
            e.setCancelled(true);        // no durability lost this hit -> can't cross a threshold
            return;
        }
        if (applied != original) e.setDamage(applied);

        // Low-durability indicator (opt-in). Fire once, on the downward crossing of 20%.
        Player p = e.getPlayer();
        if (!indicatorOn.contains(p.getUniqueId())) return;

        short max = item.getType().getMaxDurability();
        if (max <= 0) return;
        if (!(item.getItemMeta() instanceof Damageable dmg)) return;

        int before = dmg.getDamage();
        int after = before + applied;
        double remBefore = (max - before) / (double) max;
        double remAfter = (max - after) / (double) max;
        if (remBefore >= WARN_THRESHOLD && remAfter < WARN_THRESHOLD) {
            warn(p, item, remAfter);
        }
    }

    private void warn(Player p, ItemStack item, double remaining) {
        String name = prettyName(item);
        int pct = Math.max(0, (int) Math.floor(remaining * 100));
        p.sendMessage(Component.text("[!] ", NamedTextColor.RED)
                .append(Component.text("Your ", NamedTextColor.GOLD))
                .append(Component.text(name, NamedTextColor.YELLOW))
                .append(Component.text(" has reached low durability (" + pct
                        + "%) and is about to break!", NamedTextColor.GOLD)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.4f);
    }

    // -- helpers --------------------------------------------------------------

    private static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.ELYTRA;
    }

    private static String prettyName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component dn = meta.displayName();
            if (dn != null) {
                String plain = PlainTextComponentSerializer.plainText().serialize(dn);
                if (!plain.isBlank()) return plain;
            }
        }
        return prettyMaterial(item.getType());
    }

    private static String prettyMaterial(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // -- persistence (world.armor_indicator) ----------------------------------

    /** Load the opt-in set from the DB once at startup. */
    public void load() {
        indicatorOn.clear();
        indicatorOn.addAll(worldDb.loadArmorIndicatorOn());
    }
}
