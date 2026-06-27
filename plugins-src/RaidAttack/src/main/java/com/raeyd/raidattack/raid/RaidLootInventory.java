package com.raeyd.raidattack.raid;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * A player's persistent "stolen goods" inventory — a single 3×9 = 27-slot chest the raid loot
 * ticker deposits stolen items into. Exactly one per player UUID (including {@link
 * HomeSystemPlugin#DEV_UUID} for dev raids).
 *
 * <p>Per spec it can be emptied by its owner at any time and is <b>never</b> auto-reset — the
 * only things that mutate it are (a) the owner manually taking items <b>out</b> (deposits IN are
 * blocked by {@link RaidLootManager}'s withdraw-only gate, so the stash can't be abused as free
 * extra storage), and (b) the loot ticker depositing stolen stacks. Implements {@link
 * InventoryHolder} so click/close listeners recognise it via
 * {@code getHolder() instanceof RaidLootInventory}.
 */
public final class RaidLootInventory implements InventoryHolder {

    public static final int SIZE = 27;   // 3 rows × 9 columns

    private final UUID ownerId;
    private final Inventory inventory;

    public RaidLootInventory(UUID ownerId, String title) {
        this.ownerId = ownerId;
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(title));
    }

    public UUID getOwnerId() { return ownerId; }

    @Override
    public Inventory getInventory() { return inventory; }

    /**
     * Atomically deposit a whole stack. Per spec, when the inventory is full the item is NOT
     * stolen — it stays in the chest — so we deposit only if the ENTIRE stack fits. Returns
     * {@code true} if the whole stack was stored, {@code false} if it didn't all fit (in which
     * case any partial add is rolled back, leaving the inventory exactly as it was).
     */
    public boolean tryDepositFull(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return true;   // nothing to deposit
        var leftover = inventory.addItem(stack.clone());
        if (leftover.isEmpty()) return true;
        // Didn't all fit — undo the partial add. We added (original − leftover) of this item;
        // removeItem matches by isSimilar, so removing that many identical items restores the
        // pre-deposit state regardless of which physical stacks Bukkit touched.
        int leftAmt = 0;
        for (ItemStack l : leftover.values()) leftAmt += l.getAmount();
        int added = stack.getAmount() - leftAmt;
        if (added > 0) {
            ItemStack undo = stack.clone();
            undo.setAmount(added);
            inventory.removeItem(undo);
        }
        return false;
    }
}
