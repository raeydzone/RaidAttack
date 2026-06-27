package com.raeyd.raidattack.auth;

import com.raeyd.raidattack.HomeSystemPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The login gate. AsyncPlayerPreLoginEvent denies anyone who isn't ELIGIBLE (with a red reason);
 * eligible players spawn into limbo and every action they could take is cancelled here at
 * {@link EventPriority#LOWEST} until {@code /login} succeeds. The plan's rule holds: a single
 * missed event is a bypass, so this errs toward cancelling.
 */
public final class AuthGateListener implements Listener {

    private record Pending(AuthDatabase.AccountAuth account, String ip) {}

    private final HomeSystemPlugin plugin;
    private final AuthDatabase db;
    private final AuthManager manager;
    private final AuthConfig config;
    /** Pending eligible logins, keyed by the CANONICAL account UUID (post-override). */
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** Canonical account UUIDs with a live session — enforces one session per account (any edition). */
    private final java.util.Set<UUID> onlineAccounts = ConcurrentHashMap.newKeySet();
    private final boolean floodgatePresent;

    public AuthGateListener(HomeSystemPlugin plugin, AuthDatabase db, AuthManager manager, AuthConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.manager = manager;
        this.config = config;
        this.floodgatePresent = plugin.getServer().getPluginManager().getPlugin("floodgate") != null;
    }

    /** Is this connection a Bedrock player (via Floodgate)? Safe if Floodgate isn't installed. */
    private boolean isBedrock(UUID uuid) {
        if (!floodgatePresent) return false;
        try { return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(uuid); }
        catch (Throwable t) { return false; }
    }

    private static Component red(String s) {
        return Component.text(s, NamedTextColor.RED);
    }

    // ---- the gate: runs on the async login thread, blocking DB work is fine here. HIGH priority
    //      so we run AFTER Floodgate has set up the Bedrock connection. ----------------------------
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!config.enabled) return;
        String ip = event.getAddress() != null ? event.getAddress().getHostAddress() : "unknown";
        UUID originalUuid = event.getUniqueId();
        String name = event.getName();
        try {
            boolean bedrock = isBedrock(originalUuid);

            // 0) Bedrock fail-closed: with cross-play off, the handshake bridge isn't registered, so a
            //    Bedrock client arrives on a raw Floodgate id we can't unify or gate correctly
            //    (setPlayerProfile here is ignored for Floodgate). Deny rather than let it bypass the
            //    password. With cross-play on, the bridge has already mapped them to their RID.
            if (bedrock && !config.crossplayEnabled) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        red("Bedrock cross-play is being finalized.\nPlease join from Java for now."));
                return;
            }

            // 1) Resolve by name. The handshake bridge snaps a linked Bedrock client onto its Java name
            //    + RID before this fires, so one name lookup serves both editions.
            AuthDatabase.AccountAuth account = db.findByUsernameLower(name.toLowerCase(Locale.ROOT));
            if (account == null) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, red(bedrock
                        ? "No Raid Attack account is linked to this Bedrock gamertag.\n"
                          + "Register or link it in our Discord: /raidattack register | link_bedrock"
                        : "No Raid Attack account for \"" + name + "\".\nRegister in our Discord: /raidattack register"));
                return;
            }

            UUID rid = account.rid();

            // 1b) A Bedrock connection MUST have adopted its canonical RID at the handshake. If it's
            //     still on a raw Floodgate id, the link didn't take — deny rather than run a split
            //     identity (wrong playerdata, unrecognized by the raid system).
            if (bedrock && !rid.equals(originalUuid)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        red("Your Bedrock account link isn't ready yet.\nPlease reconnect in a moment."));
                return;
            }

            // 2) approval + live holds (on the resolved account).
            switch (account.approval()) {
                case "rejected" -> {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, red("Your Raid Attack access was rejected."));
                    return;
                }
                case "pending" -> {
                    event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                            red("Your access is still pending moderator approval.\nWatch for the decision in Discord."));
                    return;
                }
                default -> { }
            }
            List<String> flags = db.activeFlagSources(rid);
            if (!flags.isEmpty()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, red(buildHoldReason(rid, flags)));
                return;
            }

            // 3) one session per account (Java OR Bedrock). onlineAccounts is keyed by the RID.
            if (onlineAccounts.contains(rid)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        red("This account is already online (Java or Bedrock).\nOnly one session at a time."));
                return;
            }

            // 4) Java identity unification: snap a Java connection (offline name-UUID) onto the RID +
            //    exact Java name, so playerdata + world data key on ONE id across renames. A linked
            //    Bedrock player already arrives as their RID (via the handshake bridge), so this no-ops.
            if (!rid.equals(originalUuid) || !account.username().equals(name)) {
                event.setPlayerProfile(org.bukkit.Bukkit.createProfile(rid, account.username()));
            }

            // Eligible — stash for PlayerJoin, keyed by the RID (post-override).
            pending.put(rid, new Pending(account, ip));
        } catch (Exception e) {
            plugin.getLogger().warning("Auth: gate DB error for " + name + " — failing closed: " + e.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    red("Raid Attack login is temporarily unavailable. Please try again in a moment."));
        }
    }

    private String buildHoldReason(UUID uuid, List<String> flags) {
        StringBuilder sb = new StringBuilder("Access on hold:");
        for (String f : flags) {
            switch (f) {
                case "login_lockout" -> {
                    Optional<Instant> exp = db.lockoutExpiry(uuid);
                    long mins = exp.map(i -> Math.max(1, Duration.between(Instant.now(), i).toMinutes() + 1)).orElse((long) config.lockoutMinutes);
                    sb.append("\n• Locked ~").append(mins).append("m after too many failed logins");
                }
                case "not_trusted" -> sb.append("\n• Your Discord standing is not Trusted");
                case "left_discord" -> sb.append("\n• You are no longer in the Discord server");
                case "mc_ban" -> {
                    var ban = db.activeBan(uuid);
                    sb.append("\n• Banned");
                    if (ban.isPresent()) {
                        if (!ban.get().reason().isBlank()) sb.append(": ").append(ban.get().reason());
                        ban.get().expires().ifPresent(exp -> {
                            long mins = Math.max(1, Duration.between(Instant.now(), exp).toMinutes() + 1);
                            long days = mins / 1440, hours = (mins % 1440) / 60;
                            String left = days > 0 ? (days + "d " + hours + "h")
                                    : (hours > 0 ? (hours + "h " + (mins % 60) + "m") : (mins + "m"));
                            sb.append(" (").append(left).append(" left)");
                        });
                    }
                }
                case "manual" -> sb.append("\n• A moderator placed a hold on your account");
                default -> sb.append("\n• ").append(f);
            }
        }
        return sb.toString();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.remove(player.getUniqueId());
        if (p != null) {
            onlineAccounts.add(player.getUniqueId());   // claim the single session (canonical uuid)
            manager.enterLimbo(player, p.account(), p.ip());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pending.remove(id);
        onlineAccounts.remove(id);                      // release the single session
        manager.handleQuit(event.getPlayer());
    }

    // ---- the freeze: cancel everything for a player still in limbo -------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!manager.isInLimbo(e.getPlayer().getUniqueId())) return;
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to != null && (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ())) {
            // Pin the body, allow head rotation.
            e.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!manager.isInLimbo(e.getPlayer().getUniqueId())) return;
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!manager.isInLimbo(e.getPlayer().getUniqueId())) return;
        String lower = e.getMessage().toLowerCase(Locale.ROOT);
        if (lower.equals("/login") || lower.startsWith("/login ")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(red("You must log in first: /login <password>"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvClick(InventoryClickEvent e) {
        if (manager.isInLimbo(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvDrag(InventoryDragEvent e) {
        if (manager.isInLimbo(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && manager.isInLimbo(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        // No damage / knockback taken while frozen (belt-and-suspenders with setInvulnerable).
        if (e.getEntity() instanceof Player p && manager.isInLimbo(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        // No damage dealt BY a frozen player either.
        if (e.getDamager() instanceof Player p && manager.isInLimbo(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && manager.isInLimbo(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBed(PlayerBedEnterEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFish(PlayerFishEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEditBook(PlayerEditBookEvent e) {
        if (manager.isInLimbo(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
}
