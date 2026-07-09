package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /hs teleport} — a 15-second channelled teleport to a random surface spot inside the
 * caller's own protected claim ("home"). A boss-bar timer counts down on screen. The channel is
 * gated and interruptible:
 * <ol>
 *   <li><b>Pre-condition:</b> the caller must NOT be in {@link PvpModeManager PvP mode} — you
 *       can't teleport out of an active fight. This single check replaces the old
 *       recent-player-damage heuristic; PvP mode is the authoritative "in combat" signal.</li>
 *   <li><b>Move-break:</b> moving from the spot they started channelling on cancels it.</li>
 *   <li><b>Damage-break:</b> taking ANY damage during the channel cancels it.</li>
 * </ol>
 */
public final class HomeTeleportManager implements Listener {

    /** Channel duration. */
    private static final long CHANNEL_TICKS = 300L;            // 15 s
    /** Movement tolerance (squared blocks) before the channel breaks. */
    private static final double MOVE_EPSILON_SQ = 0.04;        // ~0.2 blocks

    private final HomeSystemPlugin plugin;
    private BukkitTask task;

    /** Active channels by player. */
    private final Map<UUID, Channel> channels = new HashMap<>();

    public HomeTeleportManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Channel {
        final Location start;          // exact position the channel began on (move-break reference)
        final UUID claimOwner;         // whose claim we teleport into (the caller's own)
        final long endTick;
        final BossBar bar;
        int lastShownSecond = -1;
        Channel(Location start, UUID claimOwner, long endTick, BossBar bar) {
            this.start = start; this.claimOwner = claimOwner; this.endTick = endTick; this.bar = bar;
        }
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        for (Channel c : channels.values()) {
            try { c.bar.removeAll(); } catch (Throwable ignored) {}
        }
        channels.clear();
    }

    // -- command entry --------------------------------------------------------

    /** Try to begin a home-teleport channel for {@code player}. */
    public void requestTeleport(Player player) {
        UUID id = player.getUniqueId();
        if (channels.containsKey(id)) {
            player.sendMessage(ChatColor.RED + "You're already teleporting home.");
            return;
        }
        Claim claim = plugin.getClaimManager().getClaimOf(id);
        if (claim == null) {
            player.sendMessage(ChatColor.RED + "You don't have a home claim to teleport to.");
            return;
        }
        if (Bukkit.getWorld(claim.getWorldId()) == null) {
            player.sendMessage(ChatColor.RED + "Your home claim's world isn't loaded.");
            return;
        }
        if (claim.contains(player.getLocation())) {
            player.sendMessage(ChatColor.RED + "You're already inside your home.");
            return;
        }
        if (plugin.getPvpMode() != null && plugin.getPvpMode().isInPvpMode(id)) {
            player.sendMessage(ChatColor.RED + "You're in PvP mode — you can't teleport home until"
                    + " the fight is over.");
            return;
        }

        long now = Bukkit.getCurrentTick();
        BossBar bar = Bukkit.createBossBar(barTitle((int) (CHANNEL_TICKS / 20)), BarColor.GREEN, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        Channel c = new Channel(player.getLocation().clone(), id, now + CHANNEL_TICKS, bar);
        channels.put(id, c);
        player.sendMessage(ChatColor.GREEN + "Teleporting home in 15s — don't move or take damage.");
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.4f);
    }

    // -- per-tick driver ------------------------------------------------------

    private void tick() {
        if (channels.isEmpty()) return;
        long now = Bukkit.getCurrentTick();
        for (UUID id : new java.util.ArrayList<>(channels.keySet())) {
            Channel c = channels.get(id);
            if (c == null) continue;
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline() || p.isDead()) { abort(id, null, null); continue; }

            // Move-break: any meaningful displacement from the start spot cancels it.
            Location loc = p.getLocation();
            if (loc.getWorld() == null || !loc.getWorld().equals(c.start.getWorld())
                    || loc.distanceSquared(c.start) > MOVE_EPSILON_SQ) {
                abort(id, p, "you moved");
                continue;
            }

            // Countdown display.
            long remaining = c.endTick - now;
            if (remaining <= 0) { complete(id, p, c); continue; }
            int secs = (int) ((remaining + 19) / 20);
            c.bar.setProgress(Math.max(0.0, Math.min(1.0, (double) remaining / CHANNEL_TICKS)));
            if (secs != c.lastShownSecond) {
                c.lastShownSecond = secs;
                c.bar.setTitle(barTitle(secs));
            }
        }
    }

    private static String barTitle(int secs) {
        return ChatColor.AQUA + "Teleporting to home — " + ChatColor.WHITE + secs + "s";
    }

    private void complete(UUID id, Player p, Channel c) {
        channels.remove(id);
        try { c.bar.removeAll(); } catch (Throwable ignored) {}
        if (p == null) return;
        Claim claim = plugin.getClaimManager().getClaimOf(c.claimOwner);
        Location dest = claim == null ? null : randomSurfaceInClaim(claim, p);
        if (dest == null) {
            p.sendMessage(ChatColor.RED + "Couldn't find a safe spot in your home claim.");
            return;
        }
        p.teleport(dest);
        p.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        p.sendMessage(ChatColor.GREEN + "Teleported to your home.");
    }

    /** Cancel a channel and tell the player why (no message if {@code reason}/{@code p} is null). */
    private void abort(UUID id, Player p, String reason) {
        Channel c = channels.remove(id);
        if (c != null) { try { c.bar.removeAll(); } catch (Throwable ignored) {} }
        if (p != null && reason != null) {
            p.sendMessage(ChatColor.RED + "Home teleport cancelled — " + reason + ".");
        }
    }

    // -- random safe surface spot in the claim --------------------------------

    private Location randomSurfaceInClaim(Claim claim, Player facing) {
        World w = Bukkit.getWorld(claim.getWorldId());
        if (w == null) return null;
        int minX = claim.getMinX(), maxX = claim.getMaxX();
        int minZ = claim.getMinZ(), maxZ = claim.getMaxZ();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 24; attempt++) {
            int x = minX + r.nextInt(Math.max(1, maxX - minX + 1));
            int z = minZ + r.nextInt(Math.max(1, maxZ - minZ + 1));
            int y = w.getHighestBlockYAt(x, z);
            Block floor = w.getBlockAt(x, y, z);
            Material m = floor.getType();
            if (m.isAir() || floor.isLiquid()) continue;                 // need a solid, dry floor
            if (w.getBlockAt(x, y + 1, z).getType().isSolid()) continue; // need headroom
            Location dest = new Location(w, x + 0.5, y + 1, z + 0.5);
            dest.setYaw(facing.getLocation().getYaw());
            dest.setPitch(facing.getLocation().getPitch());
            return dest;
        }
        // Fallback: the claim centre surface.
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        return new Location(w, cx + 0.5, w.getHighestBlockYAt(cx, cz) + 1, cz + 0.5,
                facing.getLocation().getYaw(), facing.getLocation().getPitch());
    }

    // -- listeners ------------------------------------------------------------

    /** ANY damage during the channel cancels it. (The "in combat" pre-condition is now PvP mode,
     *  checked at request time — this listener only handles the interrupt.) */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (channels.containsKey(victim.getUniqueId())) abort(victim.getUniqueId(), victim, "you took damage");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        abort(e.getPlayer().getUniqueId(), null, null);
    }
}
