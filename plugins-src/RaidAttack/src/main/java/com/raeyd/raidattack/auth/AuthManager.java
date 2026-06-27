package com.raeyd.raidattack.auth;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Owns the "limbo" state of every eligible-but-not-yet-authenticated player: freezes them, hides
 * (stores) their inventory, runs the 30s timeout, and drives the /login outcomes.
 *
 * Persistence model (fixes item-loss + spawn-position bugs): on limbo-enter we snapshot the
 * player's real inventory + position to a durable {@link LimboVault} file, then hide the inventory
 * and teleport them to the spawn zone. The snapshot is restored on EVERY exit (login, timeout,
 * quit, server stop) and the vault file is removed only on a SUCCESSFUL login — so a crash or quit
 * mid-limbo is recovered on the next join, and restore is set-based so items can never duplicate.
 */
public final class AuthManager {

    private static final class Session {
        final UUID uuid;
        final String username;
        final String passwordHash;
        final String ip;
        ItemStack[] savedStorage;
        ItemStack[] savedArmor;
        ItemStack savedOffhand;
        Location origin;          // where the player was before limbo — restored on exit
        BukkitTask timeoutTask;
        volatile boolean verifying;
        volatile boolean finished; // set once login/timeout/quit has run, to make exits idempotent

        Session(UUID uuid, String username, String passwordHash, String ip) {
            this.uuid = uuid;
            this.username = username;
            this.passwordHash = passwordHash;
            this.ip = ip;
        }
    }

    private final HomeSystemPlugin plugin;
    private final AuthDatabase db;
    private final AuthConfig config;
    private final LimboVault vault;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AuthManager(HomeSystemPlugin plugin, AuthDatabase db, AuthConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
        this.vault = new LimboVault(new File(plugin.getDataFolder(), "limbo"), plugin.getLogger());
    }

    public boolean isInLimbo(UUID uuid) {
        return sessions.containsKey(uuid);
    }

    /** Main thread (from PlayerJoinEvent). Snapshot + hide inventory + freeze + start the countdown. */
    public void enterLimbo(Player player, AuthDatabase.AccountAuth account, String ip) {
        UUID uuid = player.getUniqueId();
        Session s = new Session(uuid, account.username(), account.passwordHash(), ip);
        PlayerInventory inv = player.getInventory();

        // If a vault file already exists, a previous session crashed/quit mid-limbo: the vault is
        // the AUTHORITATIVE pre-limbo state (the inventory the client just joined with may be the
        // cleared one). Recover from it instead of re-snapshotting the live inventory.
        LimboVault.Saved recovered = vault.has(uuid) ? vault.load(uuid) : null;
        if (recovered != null) {
            s.savedStorage = recovered.storage();
            s.savedArmor = recovered.armor();
            s.savedOffhand = recovered.offhand();
            s.origin = recovered.origin();
        } else {
            s.savedStorage = inv.getStorageContents();
            s.savedArmor = inv.getArmorContents();
            s.savedOffhand = inv.getItemInOffHand();
            s.origin = player.getLocation().clone();
            vault.save(uuid, s.savedStorage, s.savedArmor, s.savedOffhand, s.origin);
        }

        // Hide everything so an impostor on someone else's name can't even see the items.
        inv.setStorageContents(new ItemStack[s.savedStorage.length]);
        inv.setArmorContents(new ItemStack[s.savedArmor.length]);
        inv.setItemInOffHand(null);

        player.setInvulnerable(true);     // no damage / knockback while frozen
        // NB: we deliberately DON'T touch food/health here — a frozen player is invulnerable and
        // FoodLevelChangeEvent is cancelled in limbo, so nothing depletes; resetting food would just
        // hand out a free hunger refill on relog. Real potion effects are likewise preserved (limbo
        // only overlays blindness/slowness), so a relog can't cleanse a debuff.
        applyLimboEffects(player);

        Location spawn = plugin.getSpawnArea() != null ? plugin.getSpawnArea().randomSurfaceSpawn() : null;
        if (spawn == null) spawn = player.getWorld().getSpawnLocation();
        player.teleport(spawn);

        sessions.put(uuid, s);
        player.sendMessage(Component.text("🔒 Log in to play: ", NamedTextColor.GOLD)
                .append(Component.text("/login <password>", NamedTextColor.YELLOW)));
        player.sendMessage(Component.text("You have " + config.limboSeconds + " seconds. No account in-game — register in Discord.", NamedTextColor.GRAY));

        s.timeoutTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> timeout(player), config.limboSeconds * 20L);
    }

    private void applyLimboEffects(Player player) {
        int inf = PotionEffect.INFINITE_DURATION;
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, inf, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, inf, 6, false, false, false));
    }

    private void clearLimboEffects(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    /**
     * Re-apply the limbo freeze potions after a respawn. Edge case: a player who dies (e.g. AFK),
     * quits on the death screen, then rejoins is back in limbo but still dead — pressing respawn
     * makes vanilla wipe ALL potion effects, so the blindness + slowness vanish even though they're
     * still gated (can't move/chat, gets kicked). Not an exploit, just the visible freeze missing.
     * Re-applied next tick so it lands after the respawn finishes; no-op once authenticated.
     */
    public void reapplyLimboEffectsAfterRespawn(Player player) {
        if (!isInLimbo(player.getUniqueId())) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && isInLimbo(player.getUniqueId())) applyLimboEffects(player);
        });
    }

    /** Main thread (from /login). Verify off-thread, then resolve on the main thread. */
    public void attemptLogin(Player player, String password) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Component.text("You are already logged in.", NamedTextColor.GRAY));
            return;
        }
        if (s.verifying) return;
        s.verifying = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok;
            try {
                ok = org.mindrot.jbcrypt.BCrypt.checkpw(password, s.passwordHash);
            } catch (Exception e) {
                ok = false;
            }
            final boolean match = ok;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                s.verifying = false;
                if (s.finished) return;
                if (match) completeLogin(player, s);
                else failLogin(player, s);
            });
        });
    }

    private void completeLogin(Player player, Session s) {
        if (s.finished) return;
        s.finished = true;
        cancelTimeout(s);
        sessions.remove(s.uuid);            // remove first so the teleport-back isn't cancelled by the gate
        restoreInventory(player, s);
        clearLimboEffects(player);
        player.setInvulnerable(false);
        teleportToOrigin(player, s);
        vault.delete(s.uuid);               // authenticated → drop the durable backup
        player.sendMessage(Component.text("✅ Logged in. Welcome to Raid Attack!", NamedTextColor.GREEN));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.markSuccess(s.uuid, s.ip);
                db.recordAttempt(s.uuid, s.username, s.ip, "success");
            } catch (Exception e) {
                plugin.getLogger().warning("Auth: markSuccess failed for " + s.username + ": " + e.getMessage());
            }
        });
    }

    private void failLogin(Player player, Session s) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            AuthDatabase.LockResult r;
            try {
                r = db.bumpFailedAndMaybeLock(s.uuid, config.maxAttempts, config.windowMinutes, config.lockoutMinutes);
                db.recordAttempt(s.uuid, s.username, s.ip, r.lockedNow() ? "locked_out" : "wrong_password");
            } catch (Exception e) {
                plugin.getLogger().warning("Auth: failLogin DB error for " + s.username + ": " + e.getMessage());
                r = new AuthDatabase.LockResult(0, false);
            }
            final AuthDatabase.LockResult res = r;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (s.finished) return;
                if (res.lockedNow()) {
                    finishAndKick(player, s, Component.text(
                            "⛔ Too many failed logins. Your account is locked for "
                                    + config.lockoutMinutes + " minutes.", NamedTextColor.RED));
                } else {
                    int left = Math.max(0, config.maxAttempts - res.count());
                    player.sendMessage(Component.text("❌ Wrong password. " + left + " attempt(s) left.", NamedTextColor.RED));
                }
            });
        });
    }

    /** Scheduled on the main thread when limbo time runs out. A no-show still counts as 1 attempt. */
    private void timeout(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.finished) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AuthDatabase.LockResult r = db.bumpFailedAndMaybeLock(s.uuid, config.maxAttempts, config.windowMinutes, config.lockoutMinutes);
                db.recordAttempt(s.uuid, s.username, s.ip, r.lockedNow() ? "locked_out" : "timeout");
            } catch (Exception e) {
                plugin.getLogger().warning("Auth: timeout DB error for " + s.username + ": " + e.getMessage());
            }
        });
        finishAndKick(player, s, Component.text("⏱ You did not log in within "
                + config.limboSeconds + " seconds.", NamedTextColor.RED));
    }

    /** Main thread (from PlayerQuitEvent). Restore inventory + position so persisted state is real. */
    public void handleQuit(Player player) {
        Session s = sessions.remove(player.getUniqueId());
        if (s == null || s.finished) return;
        s.finished = true;
        cancelTimeout(s);
        restoreInventory(player, s);
        clearLimboEffects(player);
        player.setInvulnerable(false);
        teleportToOrigin(player, s);
        // Vault is intentionally KEPT (they never authenticated) — next join recovers from it.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { db.recordAttempt(s.uuid, s.username, s.ip, "quit"); } catch (Exception ignored) {}
        });
    }

    /** onDisable: restore inventory + position for everyone still frozen, so a stop never eats state. */
    public void shutdownRestoreAll() {
        for (Session s : sessions.values()) {
            cancelTimeout(s);
            Player p = plugin.getServer().getPlayer(s.uuid);
            if (p != null && !s.finished) {
                s.finished = true;
                restoreInventory(p, s);
                clearLimboEffects(p);
                p.setInvulnerable(false);
                teleportToOrigin(p, s);
                // Vault KEPT — they never authenticated.
            }
        }
        sessions.clear();
    }

    private void finishAndKick(Player player, Session s, Component reason) {
        s.finished = true;
        cancelTimeout(s);
        sessions.remove(s.uuid);
        restoreInventory(player, s);   // persist real inventory before the kick saves player data
        clearLimboEffects(player);
        player.setInvulnerable(false);
        teleportToOrigin(player, s);
        // Vault KEPT — they never authenticated; recovered on next join.
        player.kick(reason);
    }

    private void restoreInventory(Player player, Session s) {
        if (s.savedStorage == null) return;
        PlayerInventory inv = player.getInventory();
        inv.setStorageContents(s.savedStorage);
        inv.setArmorContents(s.savedArmor);
        inv.setItemInOffHand(s.savedOffhand != null ? s.savedOffhand : new ItemStack(org.bukkit.Material.AIR));
        s.savedStorage = null; // guard against a double-restore overwriting real items with stale copies
    }

    private void teleportToOrigin(Player player, Session s) {
        if (s.origin != null && s.origin.getWorld() != null) {
            player.teleport(s.origin);
        }
    }

    private void cancelTimeout(Session s) {
        if (s.timeoutTask != null) {
            s.timeoutTask.cancel();
            s.timeoutTask = null;
        }
    }
}
