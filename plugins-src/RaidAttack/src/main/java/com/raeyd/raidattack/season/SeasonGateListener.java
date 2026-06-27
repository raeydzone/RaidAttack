package com.raeyd.raidattack.season;

import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import com.raeyd.raidattack.HomeSystemPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World.Environment;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.EndPortalFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Hard-locks the Nether and the End until their season-event dates — with <b>no bypass for normal
 * players</b>. Only holders of {@link EventManager#BYPASS_PERM} (dev/admin) are exempt, for testing.
 *
 * <p>Blocks every entry vector, not just portals, so there's no sneaking in:
 * <ul>
 *   <li>{@link PlayerPortalEvent} — nether / end portal travel (matched by cause).</li>
 *   <li>{@link PlayerTeleportEvent} — ANY non-portal teleport whose destination is a locked
 *       dimension ({@code /tp}, spectate, plugin warps, command blocks). The catch-all.</li>
 *   <li>{@link EntityPortalEvent} — mobs / dropped items through portals.</li>
 *   <li>{@link PlayerRespawnEvent} — never respawn into a locked dimension (anchor/bed edge case).</li>
 *   <li>{@link PlayerAdvancementCriterionGrantEvent} — suppress that dimension's advancements plus
 *       their toast + sound (the leak the vanilla {@code allow-nether} / bukkit {@code allow-end}
 *       toggles still let through).</li>
 * </ul>
 */
public final class SeasonGateListener implements Listener {

    private final HomeSystemPlugin plugin;

    public SeasonGateListener(HomeSystemPlugin plugin) { this.plugin = plugin; }

    private EventManager em() { return plugin.getEventManager(); }

    /** Locked iff the environment is a not-yet-enabled dimension. */
    private boolean locked(Environment env) {
        if (env == Environment.NETHER)  return !em().netherEnabled();
        if (env == Environment.THE_END) return !em().endEnabled();
        return false;
    }

    private void deny(Player p, Environment env) {
        SeasonEvent ev = env == Environment.NETHER ? SeasonEvent.NETHER : SeasonEvent.END;
        p.sendMessage(Component.text("✦ ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("The ", NamedTextColor.GRAY))
                .append(Component.text(env == Environment.NETHER ? "Nether" : "End", ev.color()))
                .append(Component.text(" is sealed until ", NamedTextColor.GRAY))
                .append(Component.text(em().formattedWhen(ev), NamedTextColor.WHITE))
                .append(Component.text("  (in " + EventManager.relative(em().remainingMillis(ev)) + ").",
                        NamedTextColor.GRAY)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent e) {
        Player p = e.getPlayer();
        if (em().canBypass(p)) return;
        Environment env = switch (e.getCause()) {
            case NETHER_PORTAL -> Environment.NETHER;
            case END_PORTAL    -> Environment.THE_END;
            default            -> null;
        };
        if (env != null && locked(env)) { e.setCancelled(true); deny(p, env); }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e instanceof PlayerPortalEvent) return;        // portals handled above (by cause)
        Player p = e.getPlayer();
        if (em().canBypass(p)) return;
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        Environment env = to.getWorld().getEnvironment();
        if (locked(env)) { e.setCancelled(true); deny(p, env); }
    }

    /**
     * Stop the End portal from ever <b>forming</b> while the End is sealed, by cancelling the
     * insertion of an Eye of Ender into an End Portal Frame. End Portal Frames aren't craftable —
     * they exist only in strongholds / creative — so the last eye is the sole player-driven path to
     * completion. Blocking it here heads off the portal blocks, the {@code BLOCK_END_PORTAL_SPAWN}
     * sound, and the entry advancement <i>before</i> they happen, rather than cancelling travel after
     * the fact (the sound fires on formation, independent of whether anyone can step through). Lifts
     * automatically once the End unlocks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEyeInsert(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.END_PORTAL_FRAME) return;
        if (b.getBlockData() instanceof EndPortalFrame epf && epf.hasEye()) return;  // already eyed
        ItemStack item = e.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE) return;
        Player p = e.getPlayer();
        if (em().canBypass(p) || em().endEnabled()) return;
        e.setCancelled(true);
        deny(p, Environment.THE_END);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        Location to = e.getTo();
        if (to == null || to.getWorld() == null) return;
        if (locked(to.getWorld().getEnvironment())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (em().canBypass(e.getPlayer())) return;
        Location to = e.getRespawnLocation();
        if (to == null || to.getWorld() == null) return;
        if (!locked(to.getWorld().getEnvironment())) return;
        World overworld = firstNormalWorld();
        if (overworld != null) e.setRespawnLocation(overworld.getSpawnLocation());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementCriterionGrantEvent e) {
        if (em().canBypass(e.getPlayer())) return;
        NamespacedKey key = e.getAdvancement().getKey();
        if (!key.getNamespace().equals("minecraft")) return;   // leave datapack advancements alone
        String path = key.getKey();
        boolean netherAdv = path.startsWith("nether/") || path.equals("story/enter_the_nether");
        boolean endAdv    = path.startsWith("end/")    || path.equals("story/enter_the_end");
        if (netherAdv && !em().netherEnabled()) e.setCancelled(true);
        if (endAdv    && !em().endEnabled())    e.setCancelled(true);
    }

    private World firstNormalWorld() {
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == Environment.NORMAL) return w;
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }
}
