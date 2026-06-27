package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Applies the configured world border to every world. Config: {@code worldborder-size} — the
 * square border's size in blocks (the diameter, centred on 0,0). The <b>Nether</b> border is set
 * to {@code size / 8} so the 8:1 overworld&lt;-&gt;nether coordinate ratio holds: a player can't
 * portal past the overworld edge by walking the compressed Nether and porting back. {@code 0}
 * (or less) = no border (Bukkit's 60,000,000 max). Applied on enable and on each
 * {@link WorldLoadEvent}, so worlds that load late still get the border.
 */
public final class WorldBorderManager implements Listener {

    /** Bukkit's effective "no border" size. */
    private static final double NO_BORDER = 60_000_000d;

    private final HomeSystemPlugin plugin;
    /** Configured overworld border size (blocks). 0 = no border. */
    private final int size;

    public WorldBorderManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.size = Math.max(0, plugin.getConfig().getInt("worldborder-size", 10000));
    }

    /** Apply to every currently-loaded world. Call once on enable. */
    public void applyAll() {
        for (World w : plugin.getServer().getWorlds()) apply(w);
        if (size > 0) {
            plugin.getLogger().info("World border set to " + size + " (Nether " + (size / 8) + "), centred on 0,0.");
        } else {
            plugin.getLogger().info("World border disabled (worldborder-size: 0).");
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        apply(e.getWorld());
    }

    private void apply(World w) {
        WorldBorder b = w.getWorldBorder();
        b.setCenter(0.0, 0.0);
        if (size <= 0) {
            b.setSize(NO_BORDER);
            return;
        }
        double diameter = (w.getEnvironment() == World.Environment.NETHER) ? (size / 8.0) : size;
        b.setSize(diameter);
    }
}
