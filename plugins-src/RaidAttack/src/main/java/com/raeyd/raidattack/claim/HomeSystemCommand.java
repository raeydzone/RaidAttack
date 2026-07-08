package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.core.LagSimulator;
import com.raeyd.raidattack.raid.CustomRaider;
import com.raeyd.raidattack.raid.CustomRavager;
import com.raeyd.raidattack.turret.Turret;
import com.raeyd.raidattack.turret.TurretEffect;
import com.raeyd.raidattack.turret.TurretStructure;
import com.raeyd.raidattack.turret.TurretUpgradeGUI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class HomeSystemCommand implements CommandExecutor, TabCompleter {

    // Minimum bumped from 20 → 80 alongside the /raid feature. A raider zone needs a
    // 10-block-thick perimeter spawn band on each side, so anything smaller leaves the centre
    // (where AI drifts when there's nothing to attack) effectively inside the spawn ring.
    private static final int MIN_SIZE = 80;
    private static final int MAX_SIZE = 200;
    /** Minimum 3D distance between any two turrets in the same claim (blocks). Applies on
     *  deploy only — existing turrets that were placed under the old 20-block rule continue
     *  to operate even if they're now closer than the new minimum. */
    private static final double MIN_TURRET_DISTANCE = 22.0;
    private static final List<String> SUBCOMMANDS =
            Arrays.asList("claim", "add", "remove", "unclaim", "info", "show", "current", "turret", "teleport", "dev");
    /** Same as {@link #SUBCOMMANDS} minus "dev" — used for tab-completion when dev mode is off
     *  or the caller isn't an owner, so "dev" never even appears. */
    private static final List<String> SUBCOMMANDS_NO_DEV =
            Arrays.asList("claim", "add", "remove", "unclaim", "info", "show", "current", "turret", "teleport");
    private static final List<String> SHOW_SUBCOMMANDS = Arrays.asList("border");
    private static final List<String> CURRENT_SUBCOMMANDS = Arrays.asList("info");
    private static final List<String> TURRET_SUBCOMMANDS =
            Arrays.asList("deploy", "list", "remove", "upgrade", "attackmobs");
    private static final List<String> ATTACKMOBS_OPTIONS = Arrays.asList("on", "off");
    /** Second-arg options under the {@code dev} prefix: normal subcommands plus dev utilities. */
    private static final List<String> DEV_SUBCOMMANDS =
            Arrays.asList("claim", "add", "remove", "unclaim", "info", "show", "turret",
                          "clear", "wipeorphans", "wipenear", "lag",
                          "spawnraider", "spawnravager", "cleartest");
    private static final List<String> LAG_PRESETS = Arrays.asList("off", "100", "200", "500", "1000");
    private static final long MAX_LAG_MS = 5000L;

    /** Convenience alias for {@link HomeSystemPlugin#DEV_UUID}. */
    private static final UUID DEV_UUID = HomeSystemPlugin.DEV_UUID;

    private final HomeSystemPlugin plugin;

    public HomeSystemCommand(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /HomeSystem.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        // /HomeSystem current … — info about the zone the caller is currently standing in.
        if (args[0].equalsIgnoreCase("current")) {
            handleCurrent(player, args);
            return true;
        }

        // /HomeSystem dev … — alt-player subcommands (claim/add/remove/unclaim/info/show) plus
        // global dev utilities (clear/lag). args[1] selects which.
        if (args[0].equalsIgnoreCase("dev")) {
            // x2 shield: (1) must be an owner per our rights system (not op); (2) dev mode must be on.
            if (!plugin.getRightsManager().isOwner(player)) {
                player.sendMessage(ChatColor.RED + "You don't have permission for dev commands.");
                return true;
            }
            if (!plugin.isDevMode()) {
                player.sendMessage(ChatColor.RED + "Dev commands are disabled. Set "
                        + ChatColor.WHITE + "dev_mode: true" + ChatColor.RED + " in config.yml (server off).");
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED
                        + "Usage: /HomeSystem dev <claim|add|remove|unclaim|info|show|clear|lag> …");
                return true;
            }
            String devSub = args[1].toLowerCase(Locale.ROOT);
            if (devSub.equals("clear")) {
                handleDevClear(player);
                return true;
            }
            if (devSub.equals("wipeorphans")) {
                int n = plugin.getTurretEntities() == null ? 0
                        : plugin.getTurretEntities().wipeOrphanNPCs();
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                        + "Wiped " + n + " orphan turret NPC(s). Use /HomeSystem dev wipenear "
                        + "near orphan towers to raze their blocks.");
                return true;
            }
            if (devSub.equals("wipenear")) {
                int n = wipeNearbyOrphanStructures(player);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                        + "Razed " + n + " orphan turret structure(s) within 64 blocks.");
                return true;
            }
            if (devSub.equals("lag")) {
                handleDevLag(player, args);
                return true;
            }
            if (devSub.equals("spawnraider")) {
                handleDevSpawnRaider(player);
                return true;
            }
            if (devSub.equals("spawnravager")) {
                handleDevSpawnRavager(player);
                return true;
            }
            if (devSub.equals("cleartest")) {
                handleDevClearTest(player);
                return true;
            }
            // Otherwise treat args[1..] as a normal subcommand executed as the imaginary dev user.
            String[] shifted = Arrays.copyOfRange(args, 1, args.length);
            dispatch(player, DEV_UUID, shifted, true);
            return true;
        }

        dispatch(player, player.getUniqueId(), args, false);
        return true;
    }

    private void dispatch(Player caller, UUID actor, String[] args, boolean asDev) {
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "claim"   -> handleClaim(caller, actor, args, asDev);
            case "add"     -> handleAdd(caller, actor, args, asDev);
            case "remove"  -> handleRemove(caller, actor, args, asDev);
            case "unclaim" -> handleUnclaim(caller, actor, asDev);
            case "info"    -> handleInfo(caller, actor, asDev);
            case "show"    -> handleShow(caller, actor, args, asDev);
            case "turret"  -> handleTurret(caller, actor, args, asDev);
            case "teleport", "tp", "home" -> plugin.getHomeTeleport().requestTeleport(caller);
            default        -> sendHelp(caller);
        }
    }

    private static String tag(boolean asDev) {
        return asDev ? (ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.RESET) : "";
    }

    // -- claim ----------------------------------------------------------------

    private void handleClaim(Player caller, UUID actor, String[] args, boolean asDev) {
        if (args.length < 2) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "") + "claim <size>  (" + MIN_SIZE + "–" + MAX_SIZE + ")");
            return;
        }
        int size;
        try {
            size = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            caller.sendMessage(ChatColor.RED + "Size must be a number between " + MIN_SIZE + " and " + MAX_SIZE + ".");
            return;
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            caller.sendMessage(ChatColor.RED + "Size must be between " + MIN_SIZE + " and " + MAX_SIZE + ".");
            return;
        }
        ClaimManager mgr = plugin.getClaimManager();
        if (mgr.getClaimOf(actor) != null) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev already has a claim. Use /HomeSystem dev unclaim first."
                             : "You already have a claim. Use /HomeSystem unclaim first."));
            return;
        }

        Location loc = caller.getLocation();
        if (loc.getWorld() == null) {
            caller.sendMessage(ChatColor.RED + "Cannot claim here.");
            return;
        }

        int[] box = boxFromAim(loc, size);
        UUID world = loc.getWorld().getUID();
        Claim conflict = mgr.findOverlap(world, box[0], box[1], box[2], box[3]);
        if (conflict != null) {
            String ownerName = mgr.resolveName(conflict.getOwner());
            caller.sendMessage(ChatColor.RED + "That area overlaps " + ownerName + "'s claim. Move or shrink and try again.");
            return;
        }

        // The public spawn area is claim-free — reject any claim that would overlap it.
        SpawnAreaManager spawnArea = plugin.getSpawnArea();
        if (spawnArea != null && spawnArea.intersects(loc.getWorld(), box[0], box[1], box[2], box[3])) {
            caller.sendMessage(ChatColor.RED + "That area overlaps the public spawn — you can't claim inside the spawn zone. Move further out and try again.");
            return;
        }

        Claim claim = new Claim(actor, world, box[0], box[1], box[2], box[3], Collections.emptySet());
        mgr.addClaim(claim);

        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Claim created: "
                + size + "×" + size + " blocks "
                + "(" + box[0] + "," + box[1] + ") → (" + box[2] + "," + box[3] + ").");
        if (asDev) {
            caller.sendMessage(ChatColor.GRAY + "Owner = dev (" + DEV_UUID.toString().substring(0, 8) + "…). "
                    + "Walk into the area to verify the Adventure switch.");
        }
        BorderEffect.play(plugin, caller, claim);
        plugin.getZoneListener().refreshAll();
    }

    /**
     * Compute the [minX, minZ, maxX, maxZ] rectangle for a size-NxN claim whose first
     * corner is the caller's block position and whose opposite corner lies in the
     * direction the caller is looking (yaw only).
     */
    private static int[] boxFromAim(Location loc, int size) {
        int px = loc.getBlockX();
        int pz = loc.getBlockZ();
        double yawRad = Math.toRadians(loc.getYaw());
        double dx = -Math.sin(yawRad);
        double dz = Math.cos(yawRad);
        int signX = dx >= 0 ? 1 : -1;
        int signZ = dz >= 0 ? 1 : -1;
        int oppX = px + signX * (size - 1);
        int oppZ = pz + signZ * (size - 1);
        return new int[] {
                Math.min(px, oppX), Math.min(pz, oppZ),
                Math.max(px, oppX), Math.max(pz, oppZ)
        };
    }

    // -- add ------------------------------------------------------------------

    private void handleAdd(Player caller, UUID actor, String[] args, boolean asDev) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev doesn't have a claim yet." : "You don't have a claim yet."));
            return;
        }
        // Lock the friend list while this player is entangled in ANY active raid — whether their
        // own claim is being raided (defender) or they're raiding someone else (attacker). Per
        // spec, mid-raid neither side may add/remove base members. Otherwise the owner could add
        // the attacker as a friend mid-fight, which would (a) make turrets stop firing at the
        // attacker (claim members are filtered out of turret targets) and (b) widen the
        // raider-immunity set in surprising ways. Wait until the raid ends.
        if (plugin.getRaidManager().isMemberEditLocked(actor)) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + "A raid is in progress — base member list is locked until it ends.");
            return;
        }
        if (args.length < 2) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "") + "add <username|self_alliance>");
            return;
        }
        String name = args[1];
        // Magic keyword: friend every member of the caller's current alliance in one shot.
        // The alliance is the caller's, NOT the actor's — when running as dev the alt UUID
        // has no alliance, but the real player does; that's the relevant social graph.
        if (name.equalsIgnoreCase("self_alliance")) {
            handleAddSelfAlliance(caller, claim, asDev);
            return;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            caller.sendMessage(ChatColor.RED + "Player not online: " + name);
            return;
        }
        UUID id = target.getUniqueId();
        if (id.equals(claim.getOwner())) {
            caller.sendMessage(ChatColor.YELLOW + "That's the owner.");
            return;
        }
        if (!claim.getFriends().add(id)) {
            caller.sendMessage(ChatColor.YELLOW + name + " is already added to the claim.");
            return;
        }
        plugin.getClaimManager().noteName(id, target.getName()); // capture name for future resolves
        plugin.getClaimManager().addClaim(claim); // persist
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Added " + name + " to "
                + (asDev ? "dev's" : "your") + " claim.");
        if (!asDev) {
            target.sendMessage(ChatColor.GREEN + "You were added to " + caller.getName() + "'s claim.");
        } else if (!target.equals(caller)) {
            target.sendMessage(ChatColor.GREEN + "You were added to dev's claim (test).");
        }
        plugin.getZoneListener().refreshAll();
    }

    // -- remove ---------------------------------------------------------------

    private void handleRemove(Player caller, UUID actor, String[] args, boolean asDev) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev doesn't have a claim yet." : "You don't have a claim yet."));
            return;
        }
        // Mirror the lock from handleAdd — member list is frozen while this player is in any
        // active raid (as defender or attacker) so neither side can reshuffle base membership
        // mid-fight to dodge turret/raider targeting changes.
        if (plugin.getRaidManager().isMemberEditLocked(actor)) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + "A raid is in progress — base member list is locked until it ends.");
            return;
        }
        if (args.length < 2) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "") + "remove <username|self_alliance>");
            return;
        }
        String name = args[1];
        if (name.equalsIgnoreCase("self_alliance")) {
            handleRemoveSelfAlliance(caller, claim, asDev);
            return;
        }
        UUID id = null;
        for (UUID f : claim.getFriends()) {
            String fName = plugin.getClaimManager().resolveName(f);
            if (fName.equalsIgnoreCase(name)) { id = f; break; }
        }
        if (id == null) {
            caller.sendMessage(ChatColor.RED + name + " is not in "
                    + (asDev ? "dev's" : "your") + " claim.");
            return;
        }
        claim.getFriends().remove(id);
        plugin.getClaimManager().addClaim(claim); // persist
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Removed " + name + " from "
                + (asDev ? "dev's" : "your") + " claim.");
        Player online = Bukkit.getPlayer(id);
        if (online != null && !asDev) {
            online.sendMessage(ChatColor.YELLOW + "You were removed from " + caller.getName() + "'s claim.");
        }
        plugin.getZoneListener().refreshAll();
    }

    // -- self_alliance helpers ------------------------------------------------

    /**
     * Friend every member of the caller's current alliance, skipping the claim's owner and
     * anyone already on the friends list. Persists once at the end. The alliance lookup uses
     * the CALLER's UUID (not actor) on purpose — when invoked via {@code /HomeSystem dev add
     * self_alliance}, the dev alt has no alliance but the real player does, and the dev claim
     * still gets the social graph the player actually has.
     */
    private void handleAddSelfAlliance(Player caller, Claim claim, boolean asDev) {
        if (plugin.getAllianceManager() == null) {
            caller.sendMessage(ChatColor.RED + "Alliance system is not loaded.");
            return;
        }
        Alliance a = plugin.getAllianceManager().getOf(caller.getUniqueId());
        if (a == null) {
            caller.sendMessage(ChatColor.RED + "You are not in an alliance.");
            return;
        }
        int added = 0;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (UUID m : a.getMembers()) {
            if (m.equals(claim.getOwner())) continue;
            if (claim.getFriends().contains(m)) continue;
            claim.getFriends().add(m);
            added++;
            names.add(plugin.getClaimManager().resolveName(m));
        }
        if (added == 0) {
            caller.sendMessage(ChatColor.YELLOW + "All members of '" + a.getName()
                    + "' are already friends of " + (asDev ? "dev's" : "your") + " claim.");
            return;
        }
        plugin.getClaimManager().addClaim(claim);
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Added " + added
                + " alliance member" + (added == 1 ? "" : "s") + " (" + String.join(", ", names)
                + ") to " + (asDev ? "dev's" : "your") + " claim.");
        plugin.getZoneListener().refreshAll();
    }

    /**
     * Inverse of {@link #handleAddSelfAlliance}: drop every current alliance member from the
     * claim's friends list. Members who were never friends (or are no longer in the alliance)
     * are obviously untouched. Persists once at the end.
     */
    private void handleRemoveSelfAlliance(Player caller, Claim claim, boolean asDev) {
        if (plugin.getAllianceManager() == null) {
            caller.sendMessage(ChatColor.RED + "Alliance system is not loaded.");
            return;
        }
        Alliance a = plugin.getAllianceManager().getOf(caller.getUniqueId());
        if (a == null) {
            caller.sendMessage(ChatColor.RED + "You are not in an alliance.");
            return;
        }
        int removed = 0;
        java.util.List<String> names = new java.util.ArrayList<>();
        for (UUID m : a.getMembers()) {
            if (claim.getFriends().remove(m)) {
                removed++;
                names.add(plugin.getClaimManager().resolveName(m));
            }
        }
        if (removed == 0) {
            caller.sendMessage(ChatColor.YELLOW + "No alliance members are currently friends of "
                    + (asDev ? "dev's" : "your") + " claim.");
            return;
        }
        plugin.getClaimManager().addClaim(claim);
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Removed " + removed
                + " alliance member" + (removed == 1 ? "" : "s") + " (" + String.join(", ", names)
                + ") from " + (asDev ? "dev's" : "your") + " claim.");
        plugin.getZoneListener().refreshAll();
    }

    // -- unclaim --------------------------------------------------------------

    private void handleUnclaim(Player caller, UUID actor, boolean asDev) {
        if (plugin.removeClaimWithCleanup(actor)) {
            caller.sendMessage(tag(asDev) + ChatColor.GREEN
                    + (asDev ? "dev's claim has been removed." : "Your claim has been removed."));
            plugin.getZoneListener().refreshAll();
        } else {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev doesn't have a claim." : "You don't have a claim."));
        }
    }

    // -- info -----------------------------------------------------------------

    private void handleInfo(Player caller, UUID actor, boolean asDev) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) {
            caller.sendMessage(ChatColor.YELLOW + tag(asDev)
                    + (asDev ? "dev doesn't have a claim. Use /HomeSystem dev claim <" + MIN_SIZE + "–" + MAX_SIZE + ">."
                             : "You don't have a claim. Use /HomeSystem claim <" + MIN_SIZE + "–" + MAX_SIZE + ">."));
            return;
        }
        caller.sendMessage(tag(asDev) + ChatColor.GREEN
                + (asDev ? "dev's claim:" : "Your claim:"));
        caller.sendMessage("  Size: " + claim.sizeX() + "×" + claim.sizeZ());
        caller.sendMessage("  Corner A: (" + claim.getMinX() + ", " + claim.getMinZ() + ")");
        caller.sendMessage("  Corner B: (" + claim.getMaxX() + ", " + claim.getMaxZ() + ")");
        if (claim.getFriends().isEmpty()) {
            caller.sendMessage("  Friends: (none)");
        } else {
            List<String> names = new ArrayList<>();
            for (UUID id : claim.getFriends()) names.add(plugin.getClaimManager().resolveName(id));
            caller.sendMessage("  Friends: " + String.join(", ", names));
        }
        caller.sendMessage("  Turrets: " + claim.getTurrets().size() + "/" + Claim.MAX_TURRETS);
    }

    // -- current --------------------------------------------------------------

    /**
     * {@code /HomeSystem current info} — report on whichever claim the caller is currently
     * standing in. Distinct from {@code info} (which always reports on the caller's own claim).
     */
    private void handleCurrent(Player caller, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("info")) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem current info");
            return;
        }
        Claim claim = plugin.getClaimManager().getClaimAt(caller.getLocation());
        if (claim == null) {
            caller.sendMessage(ChatColor.YELLOW + "You're not standing in any claimed zone.");
            return;
        }
        UUID callerId = caller.getUniqueId();
        ClaimManager mgr = plugin.getClaimManager();
        String ownerName = mgr.resolveName(claim.getOwner());
        boolean friendly = claim.isMember(callerId);

        boolean isOwner = claim.getOwner().equals(callerId);
        ChatColor ownerColor = isOwner ? ChatColor.GREEN : (friendly ? ChatColor.AQUA : ChatColor.RED);
        int turretCount = claim.getTurrets().size();
        ChatColor turretColor = turretCount > 0 ? ChatColor.GREEN : ChatColor.GRAY;

        caller.sendMessage(ChatColor.GOLD + "Zone you're standing in:");
        caller.sendMessage(ChatColor.GRAY + "  Owner: " + ownerColor + ownerName
                + (isOwner ? ChatColor.DARK_GRAY + " (you)" : ""));
        caller.sendMessage(ChatColor.GRAY + "  Size: " + ChatColor.YELLOW
                + claim.sizeX() + "×" + claim.sizeZ()
                + ChatColor.DARK_GRAY + " (" + (claim.sizeX() * claim.sizeZ()) + " blocks)");
        caller.sendMessage(ChatColor.GRAY + "  Status: "
                + (friendly ? ChatColor.GREEN + "Friendly" : ChatColor.RED + "Hostile"));
        caller.sendMessage(ChatColor.GRAY + "  Turrets: " + turretColor + turretCount
                + ChatColor.DARK_GRAY + "/" + Claim.MAX_TURRETS);
    }

    // -- show -----------------------------------------------------------------

    private void handleShow(Player caller, UUID actor, String[] args, boolean asDev) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("border")) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "") + "show border");
            return;
        }
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev has no claim to show." : "You don't have a claim to show."));
            return;
        }
        BorderEffect.play(plugin, caller, claim, BorderEffect.SHOW_DURATION_TICKS, false);
    }

    // -- turret ---------------------------------------------------------------

    /**
     * {@code /HomeSystem turret <deploy|list|remove> …}. All ops act on the {@code actor}'s
     * claim — under the {@code dev} prefix that's the imaginary dev player's claim, so a single
     * real player can fully exercise the flow. The placement coordinate is always the caller's
     * current location regardless of who owns the claim being mutated.
     *
     * <p>Hard cap is {@link Claim#MAX_TURRETS}; turrets must be ≥ {@link #MIN_TURRET_DISTANCE}
     * blocks apart (3D Euclidean). No size-based cap is needed — spacing implicitly limits
     * density on smaller claims.
     */
    private void handleTurret(Player caller, UUID actor, String[] args, boolean asDev) {
        if (args.length < 2) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "")
                    + "turret <deploy|list|remove|upgrade|attackmobs>");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) {
            caller.sendMessage(ChatColor.RED + tag(asDev)
                    + (asDev ? "dev doesn't have a claim yet." : "You don't have a claim yet."));
            return;
        }
        switch (sub) {
            case "deploy"     -> handleTurretDeploy(caller, claim, asDev);
            case "list"       -> handleTurretList(caller, claim, asDev);
            case "remove"     -> handleTurretRemove(caller, claim, args, asDev);
            case "upgrade"    -> handleTurretUpgrade(caller, claim, asDev);
            case "attackmobs" -> handleTurretAttackMobs(caller, claim, args);
            default -> caller.sendMessage(ChatColor.RED + "Unknown turret subcommand: " + sub
                    + " (deploy | list | remove | upgrade | attackmobs)");
        }
    }

    private void handleTurretDeploy(Player caller, Claim claim, boolean asDev) {
        Location loc = caller.getLocation();
        if (loc.getWorld() == null || !loc.getWorld().getUID().equals(claim.getWorldId())
                || !claim.contains(loc)) {
            caller.sendMessage(ChatColor.RED + "You must be standing inside "
                    + (asDev ? "dev's" : "your") + " claim to deploy a turret.");
            return;
        }
        int slot = claim.firstEmptySlot();
        if (slot < 0) {
            caller.sendMessage(ChatColor.RED + tag(asDev) + "All " + Claim.MAX_TURRETS
                    + " slots are deployed. Remove one first.");
            return;
        }
        // Anchor = the block the player is standing ON (feet.y - 1). Layer 1 replaces it.
        int anchorX = loc.getBlockX();
        int anchorY = loc.getBlockY() - 1;
        int anchorZ = loc.getBlockZ();
        for (Turret existing : claim.getTurrets()) {
            double dx = existing.getX() - anchorX;
            double dy = existing.getY() - anchorY;
            double dz = existing.getZ() - anchorZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < MIN_TURRET_DISTANCE) {
                caller.sendMessage(ChatColor.RED + String.format(
                        "Too close to turret #%d %s — %.1f blocks away, need ≥ %.0f.",
                        existing.getSlot() + 1, existing, d, MIN_TURRET_DISTANCE));
                return;
            }
        }
        Turret t = new Turret(slot, anchorX, anchorY, anchorZ);
        int level = claim.getSlotLevel(slot);
        // If this slot is still in its post-destruction downtime, the player cannot bypass the
        // wait by /turret remove + /turret deploy — the new turret inherits the slot's
        // remaining downtime and starts in destroyed state. respawnDestroyed will materialise
        // the structure + NPC at the NEW deploy coords when the deadline elapses.
        if (claim.isSlotInDowntime(slot)) {
            long respawnAt = claim.getSlotRespawnAt(slot);
            t.setStructureHp(0);
            t.setRespawnAtMillis(respawnAt);
            claim.deployTurret(t);
            plugin.getClaimManager().addClaim(claim);
            int secs = (int) Math.max(1, Math.ceil((respawnAt - System.currentTimeMillis()) / 1000.0));
            caller.sendMessage(tag(asDev) + ChatColor.GOLD + "Turret #" + (slot + 1)
                    + " slot is in downtime — the new turret will materialise in " + secs + "s.");
            return;
        }
        // Initialise HP to the slot's tier max (L1=150, L2=175, L3=200).
        t.setStructureHp(Turret.maxHpForLevel(level));
        claim.deployTurret(t);
        plugin.getClaimManager().addClaim(claim);                       // persist immediately
        plugin.getTurretQueue().queue(loc.getWorld(), t);               // structure spawns after step-off
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Turret #" + (slot + 1)
                + " (Level " + level + ") deployed at " + t + ".  ("
                + claim.countDeployed() + "/" + Claim.MAX_TURRETS + ")");
        caller.sendMessage(ChatColor.GRAY
                + "Structure will materialise once you step off the 5×5 footprint.");
        TurretEffect.playDeploy(plugin, caller, loc.getWorld(), t);
    }

    private void handleTurretList(Player caller, Claim claim, boolean asDev) {
        caller.sendMessage(tag(asDev) + ChatColor.GOLD + "Turrets ("
                + claim.countDeployed() + "/" + Claim.MAX_TURRETS + "):");
        for (int slot = 0; slot < Claim.MAX_TURRETS; slot++) {
            Turret t = claim.getSlotTurret(slot);
            int level = claim.getSlotLevel(slot);
            ChatColor levelColor = level == 3 ? ChatColor.LIGHT_PURPLE
                                : level == 2 ? ChatColor.AQUA
                                             : ChatColor.GRAY;
            String body;
            if (t == null) {
                body = ChatColor.DARK_GRAY + "empty";
            } else if (t.isDestroyed()) {
                long remainingMs = Math.max(0, t.getRespawnAtMillis() - System.currentTimeMillis());
                int secs = (int) Math.ceil(remainingMs / 1000.0);
                body = ChatColor.YELLOW + t.toString() + "  "
                        + ChatColor.RED + "DESTROYED" + ChatColor.GRAY + " (respawns in " + secs + "s)";
            } else {
                int max = Turret.maxHpForLevel(level);
                // Thresholds per spec: <50 red, <100 orange, ≥100 green.
                ChatColor hpColor = t.getStructureHp() < 50  ? ChatColor.RED
                                  : t.getStructureHp() < 100 ? ChatColor.GOLD
                                                             : ChatColor.GREEN;
                body = ChatColor.YELLOW + t.toString() + "  "
                        + hpColor + t.getStructureHp() + ChatColor.GRAY + "/"
                        + hpColor + max + ChatColor.GRAY + " HP";
            }
            caller.sendMessage(ChatColor.GRAY + "  #" + (slot + 1) + "  "
                    + levelColor + "Lv " + level + "  " + body);
        }
        // Visualise only the deployed ones.
        List<Turret> deployed = claim.getTurrets();
        if (!deployed.isEmpty()) {
            TurretEffect.playList(plugin, plugin.getServer().getWorld(claim.getWorldId()), deployed);
        }
    }

    private void handleTurretRemove(Player caller, Claim claim, String[] args, boolean asDev) {
        if (args.length < 3) {
            caller.sendMessage(ChatColor.RED + "Usage: /HomeSystem " + (asDev ? "dev " : "")
                    + "turret remove <#>");
            return;
        }
        int n;
        try { n = Integer.parseInt(args[2]); }
        catch (NumberFormatException e) {
            caller.sendMessage(ChatColor.RED + "Turret # must be a number 1.." + Claim.MAX_TURRETS + ".");
            return;
        }
        Turret removed = claim.undeploySlot(n);
        if (removed == null) {
            caller.sendMessage(ChatColor.RED + "Slot #" + n + " is empty.");
            return;
        }
        // CRITICAL ORDER: tear down the physical world state FIRST (NPC + structure), THEN save
        // the empty-slot claim state. The previous order (save → despawn) was the source of
        // ghost shulker NPCs: if the server crashed or Citizens hiccupped after the save but
        // before the despawn, on next boot claims.yml said "slot empty" while Citizens kept the
        // NPC alive — an orphan shulker bossing around outside any claim's tracking.
        boolean wasPending = plugin.getTurretQueue().cancel(removed);
        if (!wasPending) {
            org.bukkit.World world = plugin.getServer().getWorld(claim.getWorldId());
            if (world != null) TurretStructure.clear(world, removed);
            plugin.getTurretEntities().despawn(removed);
        }
        plugin.getClaimManager().addClaim(claim); // persist (level for this slot is preserved)
        caller.sendMessage(tag(asDev) + ChatColor.GREEN + "Removed turret #" + n
                + " at " + removed + ".  Slot stays at Level "
                + claim.getSlotLevel(n - 1) + " for next redeploy."
                + (wasPending ? ChatColor.GRAY + "  (structure hadn't materialised yet)" : ""));
    }

    private void handleTurretUpgrade(Player caller, Claim claim, boolean asDev) {
        TurretUpgradeGUI gui = new TurretUpgradeGUI(plugin, claim, caller, asDev);
        gui.open(caller);
    }

    private void handleTurretAttackMobs(Player caller, Claim claim, String[] args) {
        if (args.length < 3) {
            caller.sendMessage(ChatColor.GRAY + "Your base's turrets attack hostile mobs: "
                    + (claim.attacksHostileMobs() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF")
                    + ChatColor.GRAY + ".  Toggle with /HomeSystem turret attackmobs <on|off>.");
            return;
        }
        String v = args[2].toLowerCase(Locale.ROOT);
        boolean on;
        if (v.equals("on") || v.equals("true") || v.equals("yes")) on = true;
        else if (v.equals("off") || v.equals("false") || v.equals("no")) on = false;
        else {
            caller.sendMessage(ChatColor.RED + "Use 'on' or 'off'.");
            return;
        }
        claim.setAttacksHostileMobs(on);
        plugin.getClaimManager().save();
        caller.sendMessage(ChatColor.GREEN + "Your base's turrets now "
                + (on ? "WILL" : "will NOT") + " target hostile mobs when no enemy player is in range.");
    }

    // -- dev (TEMPORARY: remove before production) ---------------------------

    /**
     * Dev-only utilities. The {@code dev} prefix is dispatched directly from {@link #onCommand}:
     * second-arg {@code clear}/{@code lag} run the utility handlers below; anything else is
     * treated as a normal subcommand executed under {@link #DEV_UUID} so add/remove/info/etc.
     * can be exercised without a second real player.
     */
    private void handleDevLag(Player player, String[] args) {
        LagSimulator sim = plugin.getLagSimulator();
        if (sim == null) {
            player.sendMessage(ChatColor.RED + "Lag simulator not initialised.");
            return;
        }
        if (args.length < 3) {
            Long cur = sim.currentMs(player);
            if (cur == null) {
                player.sendMessage(ChatColor.GRAY + "No artificial lag active. Usage: /HomeSystem dev lag <ms|off>");
            } else {
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.YELLOW
                        + "Current artificial lag: " + cur + " ms. /HomeSystem dev lag off to clear.");
            }
            return;
        }
        String arg = args[2].toLowerCase(Locale.ROOT);
        long ms;
        if (arg.equals("off") || arg.equals("0") || arg.equals("clear")) {
            ms = 0L;
        } else {
            try {
                ms = Long.parseLong(arg);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Lag must be a number of milliseconds, or 'off'.");
                return;
            }
            if (ms < 0) {
                player.sendMessage(ChatColor.RED + "Lag must be >= 0.");
                return;
            }
            if (ms > MAX_LAG_MS) {
                player.sendMessage(ChatColor.RED + "Lag capped at " + MAX_LAG_MS + " ms (>5s risks keepalive timeouts).");
                return;
            }
        }
        LagSimulator.Result r = sim.apply(player, ms);
        switch (r) {
            case APPLIED -> player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                    + "Artificial lag applied: " + ms + " ms each way (~" + (ms * 2) + " ms RTT).");
            case CLEARED -> player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                    + "Artificial lag cleared.");
            case FAILED  -> player.sendMessage(ChatColor.RED + "Lag inject failed — see console (NMS field traversal may need updating).");
        }
    }

    /**
     * Scan a 64-block cube around the player for turret anchor patterns (layer-1 blackstone +
     * layer-7 glowstone signature). For each one found, check if any current slot's turret has
     * matching anchor coords — if not, it's an orphan from a removed claim / old test build,
     * raze it. Costs ~262k block reads in the worst case but only when explicitly invoked.
     */
    private int wipeNearbyOrphanStructures(Player player) {
        org.bukkit.World world = player.getWorld();
        int px = player.getLocation().getBlockX();
        int py = player.getLocation().getBlockY();
        int pz = player.getLocation().getBlockZ();
        int r = 32;

        // Collect every currently-known turret anchor across all claims so we don't raze a live one.
        java.util.Set<Long> liveAnchors = new java.util.HashSet<>();
        for (Claim claim : plugin.getClaimManager().all().values()) {
            if (!claim.getWorldId().equals(world.getUID())) continue;
            for (Turret t : claim.getTurrets()) {
                liveAnchors.add(packCoords(t.getX(), t.getY(), t.getZ()));
            }
        }

        int razed = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                // Bounded Y scan — turret can't span chunks vertically, so 16 blocks around
                // player covers the realistic placement range.
                for (int dy = -16; dy <= 16; dy++) {
                    int x = px + dx, y = py + dy, z = pz + dz;
                    if (y < world.getMinHeight() || y > world.getMaxHeight() - 7) continue;
                    if (!TurretStructure.looksLikeTurretAnchor(world, x, y, z)) continue;
                    if (liveAnchors.contains(packCoords(x, y, z))) continue;
                    TurretStructure.clearAt(world, x, y, z);
                    razed++;
                }
            }
        }
        return razed;
    }

    private static long packCoords(int x, int y, int z) {
        return (((long) x) << 40) ^ (((long) y) << 20) ^ ((long) z);
    }

    /**
     * Spawn one {@link CustomRaider} at the caller's feet, using the caller's name as the
     * "attacker" placeholder. Purely a visual-verification command for the raid feature —
     * confirms the MineSkin signed texture renders, the red nametag formats correctly, and
     * the player-type Citizens NPC comes up alive. No AI is attached, so the raider just
     * stands there. Wipe with {@code /HomeSystem dev cleartest}.
     */
    private void handleDevSpawnRaider(Player player) {
        if (plugin.getRaidEntities() == null) {
            player.sendMessage(ChatColor.RED + "Raid entity manager not initialised.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            player.sendMessage(ChatColor.RED + "Citizens not loaded — cannot spawn raider NPCs.");
            return;
        }
        CustomRaider raider = new CustomRaider(plugin, player.getName(), null);
        if (!raider.spawn(player.getLocation())) {
            player.sendMessage(ChatColor.RED + "Spawn failed — see console.");
            return;
        }
        plugin.getRaidEntities().register(raider);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                + "Spawned " + ChatColor.RED + player.getName() + "'s Raider"
                + ChatColor.GREEN + " — verify the custom skin renders. "
                + ChatColor.GRAY + "(Wipe with /HomeSystem dev cleartest.)");
    }

    private void handleDevSpawnRavager(Player player) {
        if (plugin.getRaidEntities() == null) {
            player.sendMessage(ChatColor.RED + "Raid entity manager not initialised.");
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            player.sendMessage(ChatColor.RED + "Citizens not loaded — cannot spawn ravager NPCs.");
            return;
        }
        CustomRavager rav = new CustomRavager(plugin, player.getName(), null);
        if (!rav.spawn(player.getLocation())) {
            player.sendMessage(ChatColor.RED + "Spawn failed — see console.");
            return;
        }
        plugin.getRaidEntities().register(rav);
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                + "Spawned " + ChatColor.RED + player.getName() + "'s Ravager"
                + ChatColor.GREEN + " — vanilla AI disabled, empty brain. "
                + ChatColor.GRAY + "(Wipe with /HomeSystem dev cleartest.)");
    }

    /**
     * Destroy every raid-role NPC (raider + ravager) the plugin currently tracks. Companion
     * to the spawn commands above so the user can clean the world between test runs without
     * touching {@code /HomeSystem dev clear} (which also razes test claims and turrets).
     */
    private void handleDevClearTest(Player player) {
        if (plugin.getRaidEntities() == null) {
            player.sendMessage(ChatColor.RED + "Raid entity manager not initialised.");
            return;
        }
        // Use the name-pattern wipe so we catch Citizens NPCs persisted from a previous
        // session (whose in-memory wrappers were never recreated). despawnAll() alone only
        // touches the currently-tracked wrappers, which is empty right after a fresh boot.
        int wiped = plugin.getRaidEntities().wipeAllRaidNpcsByName();
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                + "Wiped " + wiped + " raid NPC(s) (matched by name pattern).");
    }

    private void handleDevClear(Player player) {
        // Find every claim whose owner UUID can't be resolved to a real player (test/dev
        // claims) and clean them up properly — raze each claim's turret structures and
        // despawn its NPCs FIRST, then remove the claim itself. Plain removeUnnamedClaims()
        // would just drop the data and leave orphan towers in the world.
        int removed = 0;
        java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();
        for (java.util.UUID owner : new java.util.ArrayList<>(plugin.getClaimManager().all().keySet())) {
            if (org.bukkit.Bukkit.getOfflinePlayer(owner).getName() == null) toRemove.add(owner);
        }
        for (java.util.UUID owner : toRemove) {
            if (plugin.removeClaimWithCleanup(owner)) removed++;
        }
        // Also sweep any orphan turret NPCs (stale Citizens entries from past sessions).
        int npcsWiped = plugin.getTurretEntities() != null
                ? plugin.getTurretEntities().wipeOrphanNPCs() : 0;
        player.sendMessage(ChatColor.LIGHT_PURPLE + "[dev] " + ChatColor.GREEN
                + "Removed " + removed + " test claim(s) + " + npcsWiped + " orphan NPC(s).");
        plugin.getZoneListener().refreshAll();
    }

    // -- help -----------------------------------------------------------------

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "HomeSystem commands:");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem claim <" + MIN_SIZE + "-" + MAX_SIZE + ">" + ChatColor.GRAY + "  — claim a square zone");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem add <player>"   + ChatColor.GRAY + "  — let a friend build inside");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem remove <player>"+ ChatColor.GRAY + "  — revoke a friend");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem unclaim"        + ChatColor.GRAY + "  — delete your claim");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem info"           + ChatColor.GRAY + "  — show your claim coords");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem show border"    + ChatColor.GRAY + "  — replay the border particles");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem current info"   + ChatColor.GRAY + "  — info about the zone you're standing in");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem teleport"       + ChatColor.GRAY + "  — 15s channel → warp home (no moving/damage; not in PvP)");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem turret deploy"  + ChatColor.GRAY + "  — place a turret at your feet (max 4, ≥22 apart)");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem turret list"    + ChatColor.GRAY + "  — list & visualise your turrets");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem turret remove <#>" + ChatColor.GRAY + "  — remove turret by slot # (level kept)");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem turret upgrade" + ChatColor.GRAY + "  — open the upgrade GUI (cost: 64 Diamond / 1 Nether Star)");
        player.sendMessage(ChatColor.YELLOW + "  /HomeSystem turret attackmobs <on|off>" + ChatColor.GRAY + "  — your base's turrets also target hostile mobs?");
        if (player.hasPermission("homesystem.dev") || player.isOp()) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  /HomeSystem dev <sub> …"
                    + ChatColor.GRAY + "  — TEMP: run any subcommand as imaginary alt player 'dev'");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  /HomeSystem dev clear"
                    + ChatColor.GRAY + "  — TEMP: wipe test claims (dev + orphans)");
            player.sendMessage(ChatColor.LIGHT_PURPLE + "  /HomeSystem dev lag <ms|off>"
                    + ChatColor.GRAY + "  — TEMP: inject self-lag for desync tests");
        }
    }

    // -- tab completion -------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();
        if (args.length == 0) return Collections.emptyList();

        // First token: top-level subcommands. "dev" is only offered to owners while dev mode is on.
        if (args.length == 1) {
            return filter(plugin.devCommandsAllowed(player) ? SUBCOMMANDS : SUBCOMMANDS_NO_DEV, args[0]);
        }

        // /HomeSystem current … — only "info" follows.
        if (args[0].equalsIgnoreCase("current")) {
            if (args.length == 2) return filter(CURRENT_SUBCOMMANDS, args[1]);
            return Collections.emptyList();
        }

        // /HomeSystem dev … — second token chooses between alt-player subcommand and dev utility.
        if (args[0].equalsIgnoreCase("dev")) {
            if (!plugin.devCommandsAllowed(player)) return Collections.emptyList();   // dev off / not owner → nothing
            if (args.length == 2) return filter(DEV_SUBCOMMANDS, args[1]);
            String devSub = args[1].toLowerCase(Locale.ROOT);
            if (args.length == 3) {
                return argFor(player, DEV_UUID, devSub, args[2]);
            }
            // 4th token under dev: "dev turret remove <#>" / "dev turret attackmobs <on|off>".
            if (args.length == 4 && devSub.equals("turret")) {
                return turretArgFor(DEV_UUID, args[2].toLowerCase(Locale.ROOT), args[3]);
            }
            return Collections.emptyList();
        }

        // Normal subcommand: second token is the argument.
        if (args.length == 2) {
            return argFor(player, player.getUniqueId(), args[0].toLowerCase(Locale.ROOT), args[1]);
        }
        // 3rd token under normal flow: "turret remove <#>" / "turret attackmobs <on|off>".
        if (args.length == 3 && args[0].equalsIgnoreCase("turret")) {
            return turretArgFor(player.getUniqueId(), args[1].toLowerCase(Locale.ROOT), args[2]);
        }
        return Collections.emptyList();
    }

    /** Suggestions for the argument that follows a subcommand. */
    private List<String> argFor(Player caller, UUID actor, String sub, String typed) {
        switch (sub) {
            case "claim":   return filter(Arrays.asList("80", "100", "150", "200"), typed);
            case "add":     return filter(addCandidates(caller, actor), typed);
            case "remove":  return filter(removeCandidates(actor), typed);
            case "show":    return filter(SHOW_SUBCOMMANDS, typed);
            case "lag":     return filter(LAG_PRESETS, typed);
            case "turret":  return filter(TURRET_SUBCOMMANDS, typed);
            default:        return Collections.emptyList();
        }
    }

    /** Suggestions for the 4th argument under the new turret subcommands. */
    private List<String> turretArgFor(UUID actor, String sub, String typed) {
        switch (sub) {
            case "remove":     return turretIndices(actor, typed);
            case "attackmobs": return filter(ATTACKMOBS_OPTIONS, typed);
            default:           return Collections.emptyList();
        }
    }

    /** "1", "2", …, n — slot numbers of currently-deployed turrets (for {@code remove <#>}). */
    private List<String> turretIndices(UUID actor, String typed) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Claim.MAX_TURRETS; i++) {
            if (claim.getSlotTurret(i) != null) out.add(String.valueOf(i + 1));
        }
        return filter(out, typed);
    }

    private List<String> addCandidates(Player caller, UUID actor) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        Set<UUID> exclude = new HashSet<>();
        exclude.add(actor);
        if (claim != null) exclude.addAll(claim.getFriends());
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!exclude.contains(p.getUniqueId())) out.add(p.getName());
        }
        // Surface the magic keyword if the caller is in an alliance with members not yet on
        // the friends list — otherwise hide it to keep the completion menu tidy.
        if (plugin.getAllianceManager() != null) {
            Alliance a = plugin.getAllianceManager().getOf(caller.getUniqueId());
            if (a != null) {
                for (UUID m : a.getMembers()) {
                    if (!m.equals(actor) && (claim == null || !claim.getFriends().contains(m))) {
                        out.add("self_alliance");
                        break;
                    }
                }
            }
        }
        return out;
    }

    private List<String> removeCandidates(UUID actor) {
        Claim claim = plugin.getClaimManager().getClaimOf(actor);
        if (claim == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (UUID id : claim.getFriends()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            if (op.getName() != null) out.add(op.getName());
        }
        // self_alliance keyword for bulk-remove, when an alliance member is on the friends list.
        if (plugin.getAllianceManager() != null) {
            // Caller == actor in normal flow; for dev flow this still surfaces the keyword
            // whenever the claim has at least one friend in the caller's alliance.
            Player caller = Bukkit.getPlayer(actor);
            Alliance a = caller != null ? plugin.getAllianceManager().getOf(caller.getUniqueId()) : null;
            if (a != null) {
                for (UUID m : a.getMembers()) {
                    if (claim.getFriends().contains(m)) { out.add("self_alliance"); break; }
                }
            }
        }
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        if (prefix == null || prefix.isEmpty()) return options;
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : options) if (s.toLowerCase(Locale.ROOT).startsWith(p)) out.add(s);
        return out;
    }
}
