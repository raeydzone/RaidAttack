package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.quest.Quest;
import com.raeyd.raidattack.quest.QuestManager;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Inventory-based upgrade UI for one claim's four turret slots.
 *
 * <p>Layout: a 9-slot dispenser inventory. Slots 0..3 are clickable upgrade buttons (one per
 * turret #1..#4); slots 4..8 are grey-glass decoration. Clicking a button pulls the cost
 * material from the player's main inventory and bumps that slot's level. All raw inventory
 * interactions are cancelled — items never enter the GUI itself, so nothing can be stuck or
 * stolen from it. The level is stored on the {@link Claim}, not on the {@link Turret}, so it
 * persists across remove → redeploy of that slot.
 *
 * <p>Cost schedule:
 * <ul>
 *   <li>L1 → L2: 64 Diamonds</li>
 *   <li>L2 → L3: 1 Nether Star (the Wither's drop — ties the upgrade to beating the boss)</li>
 * </ul>
 */
public final class TurretUpgradeGUI implements InventoryHolder {

    private static final int SIZE = 9;
    private final NamespacedKey buttonKey;

    private final HomeSystemPlugin plugin;
    private final Claim claim;
    private final UUID viewerId;
    private final boolean asDev;
    private final Inventory inventory;

    public TurretUpgradeGUI(HomeSystemPlugin plugin, Claim claim, Player viewer, boolean asDev) {
        this.plugin = plugin;
        this.claim = claim;
        this.viewerId = viewer.getUniqueId();
        this.asDev = asDev;
        this.buttonKey = new NamespacedKey(plugin, "turret_upgrade_slot");
        String title = ChatColor.DARK_PURPLE + (asDev ? "[dev] " : "") + "Turret Upgrades";
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        rebuild();
    }

    @Override
    public Inventory getInventory() { return inventory; }

    public void open(Player viewer) {
        viewer.openInventory(inventory);
    }

    /** Rebuild all button items from current claim state. Called after each successful upgrade. */
    private void rebuild() {
        for (int slot = 0; slot < Claim.MAX_TURRETS; slot++) {
            inventory.setItem(slot, buildSlotButton(slot));
        }
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName(" ");
            pane.setItemMeta(paneMeta);
        }
        for (int i = Claim.MAX_TURRETS; i < SIZE; i++) inventory.setItem(i, pane);
    }

    private ItemStack buildSlotButton(int slot) {
        int level = claim.getSlotLevel(slot);
        boolean deployed = claim.getSlotTurret(slot) != null;
        Material icon;
        String costText;
        if (level >= Claim.MAX_TURRET_LEVEL) {
            icon = Material.BARRIER;
            costText = ChatColor.RED + "Max level";
        } else if (level == 1) {
            icon = Material.DIAMOND;
            costText = ChatColor.AQUA + "Cost: 64 Diamonds → Level 2";
        } else {
            icon = Material.NETHER_STAR;
            costText = ChatColor.LIGHT_PURPLE + "Cost: 1 Nether Star → Level 3";
        }
        ItemStack item = new ItemStack(icon);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "Turret #" + (slot + 1) + ChatColor.GRAY
                    + " — " + ChatColor.YELLOW + "Level " + level);
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + (deployed ? "Deployed" : "Empty slot"),
                    "",
                    costText,
                    (level >= Claim.MAX_TURRET_LEVEL
                            ? ChatColor.DARK_GRAY + "Nothing to do."
                            : ChatColor.GRAY + "Click to consume materials and upgrade.")
            ));
            meta.getPersistentDataContainer().set(buttonKey, PersistentDataType.INTEGER, slot);
            item.setItemMeta(meta);
        }
        return item;
    }

    // -- click handling (invoked by TurretUpgradeListener) --------------------

    public void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);  // no item movement, ever
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.getUniqueId().equals(viewerId)) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= Claim.MAX_TURRETS) return;

        int slot = e.getRawSlot();
        int level = claim.getSlotLevel(slot);
        if (level >= Claim.MAX_TURRET_LEVEL) {
            p.sendMessage(ChatColor.RED + "Turret #" + (slot + 1) + " is already max level.");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return;
        }
        Material cost = (level == 1) ? Material.DIAMOND : Material.NETHER_STAR;
        int amount = (level == 1) ? 64 : 1;
        if (!hasItems(p, cost, amount)) {
            p.sendMessage(ChatColor.RED + "You need " + amount + " "
                    + prettyName(cost) + " to upgrade turret #" + (slot + 1) + ".");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.6f);
            return;
        }
        consumeItems(p, cost, amount);
        claim.setSlotLevel(slot, level + 1);
        plugin.getClaimManager().save();

        // Quest progress: count how many of the 4 slots now sit at level >= 2 / >= 3.
        QuestManager quests = plugin.getQuests();
        if (quests != null) {
            int atLeast2 = 0, atLeast3 = 0;
            for (int s = 0; s < Claim.MAX_TURRETS; s++) {
                int lvl = claim.getSlotLevel(s);
                if (lvl >= 2) atLeast2++;
                if (lvl >= 3) atLeast3++;
            }
            quests.set(claim.getOwner(), Quest.TURRETS_LVL2, atLeast2);
            quests.set(claim.getOwner(), Quest.TURRETS_LVL3, atLeast3);
        }

        // Refresh NPC nametag if the turret is currently deployed.
        Turret t = claim.getSlotTurret(slot);
        if (t != null) plugin.getTurretEntities().refreshName(t, claim.getSlotLevel(slot));

        rebuild();
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.sendMessage(ChatColor.GREEN + "Turret #" + (slot + 1) + " upgraded to Level "
                + claim.getSlotLevel(slot) + "!");
    }

    public void handleDrag(InventoryDragEvent e) {
        e.setCancelled(true);
    }

    // -- helpers --------------------------------------------------------------

    private static boolean hasItems(Player p, Material mat, int amount) {
        int found = 0;
        for (ItemStack stack : p.getInventory().getContents()) {
            if (stack != null && stack.getType() == mat) {
                found += stack.getAmount();
                if (found >= amount) return true;
            }
        }
        return false;
    }

    private static void consumeItems(Player p, Material mat, int amount) {
        int remaining = amount;
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != mat) continue;
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            if (stack.getAmount() <= 0) contents[i] = null;
            remaining -= take;
        }
        p.getInventory().setContents(contents);
    }

    private static String prettyName(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
