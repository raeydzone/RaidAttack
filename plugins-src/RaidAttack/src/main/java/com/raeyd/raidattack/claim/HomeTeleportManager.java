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
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

/**
 * {@code /hs teleport} — a 15-second channelled teleport to a random surface spot inside the
 * caller's own protected claim ("home"). A boss-bar timer counts down on screen. The channel is
 * gated and interruptible:
 * <ol>
 *   <li><b>Pre-condition:</b> the caller must NOT have been damaged by another player in the last
 *       {@value #NO_PVP_SECONDS}s (can't bail out of active PvP).</li>
 *   <li><b>Move-break:</b> moving from the spot they started channelling on cancels it.</li>
 *   <li><b>Damage-break:</b> taking ANY damage during the channel cancels it.</li>
 * </ol>
 */
public final class HomeTeleportManager implements Listener {

    /** Channel duration. */
    private static final long CHANNEL_TICKS = 300L;            // 15 s
    /** Recent-PvP lockout window before a channel may start. */
    private static final long NO_PVP_TICKS = 1800L;            // 90 s
    private static final int NO_PVP_SECONDS = 90;
    /** Movement tolerance (squared blocks) before the channel breaks. */
    private static final double MOVE_EPSILON_SQ = 0.04;        // ~0.2 blocks

    private final HomeSystemPlugin plugin;
    private BukkitTask task;

    /** Active channels by player. */
    private final Map<UUID, Channel> channels = new HashMap<>();
    /** Last tick each player was damaged by another player (for the PvP lockout). */
    private final Map<UUID, Long> lastPvpTick = new HashMap<>();

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
        lastPvpTick.clear();
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
        Long lastPvp = lastPvpTick.get(id);
        if (lastPvp != null) {
            long elapsed = Bukkit.getCurrentTick() - lastPvp;
            if (elapsed < NO_PVP_TICKS) {
                long waitS = (NO_PVP_TICKS - elapsed + 19) / 20;
                player.sendMessage(ChatColor.RED + "You were recently in combat. Wait "
                        + waitS + "s before teleporting home.");
                return;
            }
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        // Record player-vs-player damage for the 90 s pre-condition. ONLY real human players
        // count — Citizens raider NPCs also arrive as Player instances, but being attacked by a
        // raid mob must NOT block a home teleport (that's exactly when you'd want to bail).
        if (e instanceof EntityDamageByEntityEvent ev) {
            Entity root = resolveAttacker(ev.getDamager());
            if (root != null && isRealPlayer(root) && !root.getUniqueId().equals(victim.getUniqueId())) {
                lastPvpTick.put(victim.getUniqueId(), (long) Bukkit.getCurrentTick());
            }
        }
        // ANY damage breaks an in-progress channel.
        if (channels.containsKey(victim.getUniqueId())) abort(victim.getUniqueId(), victim, "you took damage");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        abort(e.getPlayer().getUniqueId(), null, null);
        lastPvpTick.remove(e.getPlayer().getUniqueId());
    }

    /** True only for a genuine human player — excludes Citizens player-type NPCs (raiders). */
    private static boolean isRealPlayer(Entity e) {
        if (!(e instanceof Player)) return false;
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return true;
        try {
            return !net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(e);
        } catch (Throwable t) {
            return true;   // Citizens API hiccup — fail safe by treating it as a real player
        }
    }

    /** Resolve a projectile to its shooter, else the damager itself. */
    private static Entity resolveAttacker(Entity damager) {
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            return (src instanceof Entity en) ? en : null;
        }
        return damager;
    }
}
