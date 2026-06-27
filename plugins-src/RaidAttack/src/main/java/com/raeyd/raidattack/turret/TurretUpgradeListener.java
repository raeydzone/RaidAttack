package com.raeyd.raidattack.turret;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Single registered listener that delegates to whichever {@link TurretUpgradeGUI} owns the
 * inventory in play. Avoids the boilerplate of register/unregister per open.
 */
public final class TurretUpgradeListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof TurretUpgradeGUI gui) gui.handleClick(e);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof TurretUpgradeGUI gui) gui.handleDrag(e);
    }
}
