package com.raeyd.raidattack.combat;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.Iterator;
import java.util.concurrent.ThreadLocalRandom;
import net.citizensnpcs.api.CitizensAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * "Core item" death protection. On ANY player death, everything drops as usual EXCEPT core
 * items — armor pieces (helmet / chestplate / leggings / boots), any sword, the mace, any
 * spear, bow and crossbow. Each core item independently rolls an {@link #KEEP_CHANCE 80%}
 * chance to be KEPT (stay in the player's inventory, in its exact slot — worn armor stays
 * worn) and a 20% chance to drop like everything else. So dying usually keeps your kit but
 * never guarantees it, and every piece rolls on its own — you can wake up with all armor
 * minus the helmet that rolled the 20%.
 *
 * <p>Implementation: Paper's {@code PlayerDeathEvent#getItemsToKeep()} — adding the EXACT
 * inventory instance keeps it in place (slot preserved, crash-safe: the item simply never
 * leaves the inventory). Per Paper's contract we must then remove the kept stack from
 * {@code getDrops()} ourselves or it would duplicate; removal is identity-first with an
 * equality fallback (equality removes ONE occurrence, so two identical swords where one
 * keeps and one drops still resolve correctly).
 *
 * <p>Not applied to Citizens NPCs (raider loot is the raid system's business), to deaths with
 * keep-inventory already on, or to the tactical-leave OFFLINE execution (that snapshot drop is
 * a deliberate full punishment and never fires a PlayerDeathEvent) — though a leaver executed
 * LIVE (returned at the last second) dies through this event and does get the core-item rolls.
 */
public final class CoreItemKeepListener implements Listener {

    /** Per-item chance to survive the death (stay in inventory). Shared with the tactical-leave
     *  execution path, which applies the same rolls to the offline snapshot drop. */
    public static final double KEEP_CHANCE = 0.80;

    private final HomeSystemPlugin plugin;

    public CoreItemKeepListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) return;                 // nothing is dropping anyway
        Player player = event.getEntity();
        if (isNpc(player)) return;
        // The tactical-leave artificial at-login death: the core-item rolls already ran at
        // execution time (on the snapshot) — the inventory holds ONLY the kept winners, and the
        // silent-death handler keeps them through this death. Rolling again would double-dip.
        if (player.hasMetadata(TacticalLeaveManager.SILENT_DEATH_META)) return;

        // Iterate the LIVE inventory (storage + armor + offhand) so kept stacks are the exact
        // instances still sitting in their slots — that's what preserves slot + worn state.
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir() || !isCoreItem(item.getType())) continue;
            if (ThreadLocalRandom.current().nextDouble() >= KEEP_CHANCE) continue;   // the 20%: drops
            event.getItemsToKeep().add(item);
            removeOneFromDrops(event, item);
        }
    }

    /** Identity-first removal; equality fallback removes exactly one matching occurrence. */
    private static void removeOneFromDrops(PlayerDeathEvent event, ItemStack kept) {
        Iterator<ItemStack> it = event.getDrops().iterator();
        while (it.hasNext()) {
            if (it.next() == kept) { it.remove(); return; }
        }
        event.getDrops().remove(kept);
    }

    /** Armor pieces, swords, mace, spears, bow, crossbow. Name-matched so material additions
     *  (new tiers of sword/spear/armor) are covered without touching this list. */
    public static boolean isCoreItem(Material material) {
        if (material == Material.MACE || material == Material.BOW || material == Material.CROSSBOW) return true;
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.endsWith("_SWORD") || name.contains("SPEAR");
    }

    private static boolean isNpc(Player player) {
        try {
            return Bukkit.getPluginManager().getPlugin("Citizens") != null
                    && CitizensAPI.getNPCRegistry().isNPC(player);
        } catch (Throwable t) {
            return false;
        }
    }
}
