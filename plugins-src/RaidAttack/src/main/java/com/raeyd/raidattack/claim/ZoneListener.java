package com.raeyd.raidattack.claim;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.combat.WitherCombatManager;
import com.raeyd.raidattack.core.HoverWatchdog;
import com.raeyd.raidattack.raid.RaidManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Two layers of zone enforcement.
 *
 * <p><b>Layer 1 — gamemode.</b> Any non-member, non-bypass player inside a claim is held in
 * {@link GameMode#ADVENTURE}; their previous mode is restored when they leave.
 *
 * <p><b>Layer 2 — block actions.</b> Adventure alone doesn't fully protect a zone — an
 * outsider can stand 1 block away and break/place inside via reach. So we also cancel block
 * breaks, block placements, bucket empty/fill, fluid flow into the zone, ignition, and any
 * explosion damage to claim blocks.
 *
 * <p>On top of that, players see a coloured "entering" message when they cross into a zone:
 * green for their own/a friend's, red for a hostile one.
 */
public final class ZoneListener implements Listener {

    private static final long DENY_MESSAGE_COOLDOWN_MS = 2000L;

    private final HomeSystemPlugin plugin;
    private final ClaimManager claims;

    /** Tracks which claim (by owner UUID) each player is currently standing in. */
    private final Map<UUID, UUID> currentClaim = new HashMap<>();
    /** Players currently inside the public spawn area, for enter/exit messaging. */
    private final Set<UUID> inSpawnArea = new HashSet<>();
    /** Last block-deny chat message timestamp per player, to throttle spam. */
    private final Map<UUID, Long> lastDenyMessage = new HashMap<>();

    public ZoneListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
        this.claims = plugin.getClaimManager();
    }

    // ====================================================================
    // Layer 1: gamemode + entry messages
    // ====================================================================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        update(e.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() != null) update(e.getPlayer(), e.getTo());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        claims.noteName(e.getPlayer().getUniqueId(), e.getPlayer().getName());
        update(e.getPlayer(), e.getPlayer().getLocation());
        // Owners "have OP rights" per the rights system — (re)assert it on login. Never revokes.
        if (plugin.getRightsManager() != null) {
            plugin.getRightsManager().ensureOwnerOp(e.getPlayer());
        }
        // Restore scoreboard team membership for alliance members — the main scoreboard does
        // persist team entities across restarts in modern Paper, but a fresh server tick after
        // login is the safest time to re-apply if anything got out of sync.
        if (plugin.getAllianceManager() != null) {
            plugin.getAllianceManager().onPlayerJoin(e.getPlayer());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        plugin.getServer().getScheduler().runTask(plugin,
                () -> update(e.getPlayer(), e.getPlayer().getLocation()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        update(e.getPlayer(), e.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        // Drop per-player transient state. The saved-gamemode entry in ClaimManager
        // intentionally stays so the player resumes correctly when they log back in.
        currentClaim.remove(e.getPlayer().getUniqueId());
        lastDenyMessage.remove(e.getPlayer().getUniqueId());
        inSpawnArea.remove(e.getPlayer().getUniqueId());
    }

    /** Re-evaluate gamemode + entry/exit messages for the given player at the given location. */
    public void update(Player player, Location loc) {
        // Citizens NPCs (raid raiders/ravagers, turret shulkers) are player-type entities that can
        // reach this path via move/teleport events and refreshAll(). They must NEVER be gamemode-
        // managed: forcing a raider into Adventure is pointless, and because an NPC dies/despawns
        // without ever triggering the clean "leave zone" path, each one leaked a permanent
        // saved-gamemode entry — that's where the runaway gamemodes.yml growth came from. Skip them.
        if (player.hasMetadata("NPC")) return;
        UUID id = player.getUniqueId();

        // Bypass players are fully opted out of zone behaviour — no gamemode swap, no messages.
        if (player.hasPermission("homesystem.bypass")) {
            currentClaim.remove(id);
            inSpawnArea.remove(id);
            return;
        }

        Claim claim = claims.getClaimAt(loc);
        UUID newOwner = claim != null ? claim.getOwner() : null;
        UUID oldOwner = currentClaim.get(id);

        // Boundary crossing → fire entry message + update tracking.
        if (!Objects.equals(newOwner, oldOwner)) {
            if (claim != null) announceEnter(player, claim);
            if (newOwner == null) currentClaim.remove(id);
            else                  currentClaim.put(id, newOwner);
        }

        // Spawn-area enter/exit message.
        SpawnAreaManager spawnArea = plugin.getSpawnArea();
        boolean nowInSpawn = spawnArea != null && spawnArea.contains(loc);
        if (nowInSpawn && !inSpawnArea.contains(id)) {
            inSpawnArea.add(id);
            player.sendMessage(ChatColor.AQUA + "Entering the spawn area " + ChatColor.GRAY
                    + "— a safe public zone (no building, no claiming, explosion-proof).");
        } else if (!nowInSpawn && inSpawnArea.remove(id)) {
            player.sendMessage(ChatColor.AQUA + "Leaving the spawn area.");
        }

        // Gamemode update. Adventure when in a foreign claim OR anywhere in the public spawn area
        // (so the spawn can't be built in / griefed — see SpawnAreaManager).
        boolean shouldBeAdventure = (claim != null && !claim.isMember(id)) || nowInSpawn;
        if (shouldBeAdventure) {
            if (player.getGameMode() != GameMode.ADVENTURE) {
                claims.setSavedGamemode(id, player.getGameMode());
                player.setGameMode(GameMode.ADVENTURE);
            } else if (claims.getSavedGamemode(id) == null) {
                // Already in Adventure (e.g. crash recovery). Assume natural mode was Survival.
                claims.setSavedGamemode(id, GameMode.SURVIVAL);
            }
        } else {
            GameMode saved = claims.clearSavedGamemode(id);
            if (saved != null && player.getGameMode() == GameMode.ADVENTURE) {
                player.setGameMode(saved);
            }
        }
    }

    private void announceEnter(Player player, Claim claim) {
        UUID id = player.getUniqueId();
        if (claim.getOwner().equals(id)) {
            player.sendMessage(ChatColor.GREEN + "Entering your zone.");
        } else if (claim.getFriends().contains(id)) {
            player.sendMessage(ChatColor.GREEN + "Entering a friendly zone.");
        } else {
            String ownerName = claims.resolveName(claim.getOwner());
            player.sendMessage(ChatColor.RED + "Entering " + ownerName + "'s hostile zone.");
            // Tag for the turret-targeting 60-second active-enemy window. Without this hook
            // a player that ducks in and out faster than the 1 Hz tracker tick wouldn't count.
            if (plugin.getEnemyTracker() != null && !player.hasPermission("homesystem.bypass")) {
                plugin.getEnemyTracker().note(claim.getOwner(), id);
            }
        }
    }

    /** Force re-evaluation for all online players. Called when claims/friends change. */
    public void refreshAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            update(p, p.getLocation());
        }
    }

    // ====================================================================
    // Layer 2: block-action protection
    // ====================================================================

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (denyIfForeign(e.getPlayer(), e.getBlock().getLocation(), "break")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Location target = e.getBlockPlaced().getLocation();
        if (denyIfForeign(e.getPlayer(), target, "place")) {
            e.setCancelled(true);
            // Feed the ghost-block detector. The watchdog uses this as the cause-signal
            // for snap-back: airborne in a foreign claim within 5 blocks of a recent
            // denied placement → exploit, teleport back.
            HoverWatchdog hw = plugin.getHoverWatchdog();
            if (hw != null) hw.recordDeniedPlacement(e.getPlayer(), target);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        // Where the fluid would be deposited = the face clicked on the targeted block.
        Block target = e.getBlockClicked().getRelative(e.getBlockFace());
        if (denyIfForeign(e.getPlayer(), target.getLocation(), "pour fluid into")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denyIfForeign(e.getPlayer(), e.getBlockClicked().getLocation(), "take fluid from")) {
            e.setCancelled(true);
        }
    }

    /**
     * Cancel any fluid that tries to flow INTO a claim from a block that isn't part of the
     * same claim. Stops "lava placed outside, lets it flow into the zone" griefing.
     */
    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent e) {
        Claim toClaim = claims.getClaimAt(e.getToBlock().getLocation());
        if (toClaim == null) return;
        Claim fromClaim = claims.getClaimAt(e.getBlock().getLocation());
        if (fromClaim != toClaim) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Claim claim = claims.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        Player p = e.getPlayer();
        if (p != null && (claim.isMember(p.getUniqueId()) || p.hasPermission("homesystem.bypass"))) return;
        e.setCancelled(true);
        if (p != null) denyMessage(p, "ignite blocks in", claim);
    }

    /**
     * Strip claim blocks AND public-spawn-area blocks out of an explosion's affected-block list.
     * The explosion still happens — entities still take damage, particles still spawn — but the
     * listed blocks inside any claim or the spawn area are left untouched (creeper / TNT can't grief
     * spawn, per the anti-camp design).
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        SpawnAreaManager sa = plugin.getSpawnArea();
        e.blockList().removeIf(b -> claims.getClaimAt(b.getLocation()) != null
                || (sa != null && sa.contains(b.getLocation())));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        SpawnAreaManager sa = plugin.getSpawnArea();
        e.blockList().removeIf(b -> claims.getClaimAt(b.getLocation()) != null
                || (sa != null && sa.contains(b.getLocation())));
    }

    /**
     * Entity-driven block changes inside a claim. Wither's direct "wither dash" block-break
     * doesn't fire EntityExplodeEvent — it goes through EntityChangeBlockEvent. Endermen
     * picking up blocks, ravagers smashing leaves, all route through here too. Cancel anything
     * inside a claim — EXCEPT member-owned gravity: falling blocks (sand, gravel, anvils,
     * concrete powder) fire this event both when they start to fall (block → air) and when they
     * land (air → block), and the old blanket cancel froze every gravity block inside every base.
     *
     * <p>Letting gravity work is safe because of how each half is gated:
     * <ul>
     *   <li><b>Start-of-fall — allowed.</b> The block already exists inside the claim, and every
     *       path an outsider could use to get one there is blocked elsewhere (block place, bucket,
     *       cross-border pistons, explosions). So it's member-placed or natural terrain.</li>
     *   <li><b>Landing — allowed only if the fall began inside this same claim.</b> A falling
     *       block arriving from anywhere else (sand/anvil cannon lobbing entities over the border,
     *       a drop-tower hugging the edge, plugin-spawned = null origin) is hostile: cancel AND
     *       remove the entity without drops so nothing lingers or litters into the base.</li>
     * </ul>
     * Turret columns are unaffected either way — {@code TurretProtectionListener} keeps its own
     * blanket EntityChangeBlock cancel there, so gravity blocks still can't bury a turret.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        Claim claim = claims.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        if (e.getEntity() instanceof FallingBlock fallingBlock) {
            if (e.getTo().isAir()) return;                       // start-of-fall: member gravity
            Location origin = fallingBlock.getOrigin();
            if (origin != null && claims.getClaimAt(origin) == claim) return;   // same-claim landing
            e.setCancelled(true);
            fallingBlock.remove();   // don't leave the cancelled entity re-trying (or dropping) each tick
            return;
        }
        e.setCancelled(true);
    }

    /**
     * Cancel any door-relevant interaction inside a foreign zone — direct right-click of a
     * door / trapdoor / fence gate, click of a button / lever, and walking onto a pressure
     * plate or tripwire. This shuts every player-driven path to opening a door inside
     * someone else's claim, regardless of the redstone wiring behind it.
     *
     * <p>Right-clicks emit a throttled chat warning; PHYSICAL (stepping) events stay silent
     * so a hostile player walking back and forth doesn't get a wall of red text.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null) return;  // RIGHT_CLICK_AIR has no block
        if (!isDoorRelated(block.getType())) return;

        Claim claim = claims.getClaimAt(block.getLocation());
        if (claim == null) return;
        Player p = e.getPlayer();
        if (claim.isMember(p.getUniqueId())) return;
        if (p.hasPermission("homesystem.bypass")) return;

        e.setCancelled(true);
        if (e.getAction() != Action.PHYSICAL) {
            denyMessage(p, "open or trigger doors in", claim);
        }
    }

    /**
     * Container access control inside a claim. Two distinct rules, both handled here:
     *
     * <ol>
     *   <li><b>Non-members never open a claim's containers</b> — at any time, raid or not. This is
     *       baseline home protection: Adventure mode does NOT stop a player from right-clicking a
     *       chest, so without this an outsider (e.g. an attacker standing in the zone) could open
     *       and empty chests by hand. Only members (owner + friends) may ever open them.</li>
     *   <li><b>During a full raid, even members are sealed out.</b> When a zone is being raided and
     *       ALL turrets are down, the base is wide open and nobody — owner, friends, or attacker —
     *       may open a chest by hand: items leave only via the automated loot ticker, so a defender
     *       can't race to rescue a chest. Reopens for members automatically the instant any turret
     *       comes back online ({@code allTurretsDestroyed} flips false).</li>
     * </ol>
     *
     * <p>Runs as its own handler (separate from the door logic) and only fires the heavier
     * raid/turret lookups after a cheap material + membership gate, so normal interaction is
     * unaffected.
     */
    @EventHandler(ignoreCancelled = true)
    public void onContainerOpen(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (!isLootContainer(block.getType())) return;

        Claim claim = claims.getClaimAt(block.getLocation());
        if (claim == null) return;
        Player p = e.getPlayer();
        if (p.hasPermission("homesystem.bypass")) return;

        // Rule 1 — non-members can NEVER open this claim's containers (always, raid or not).
        if (!claim.isMember(p.getUniqueId())) {
            e.setCancelled(true);
            denyMessage(p, "open chests in", claim);
            return;
        }

        // Rule 2 — members are sealed out only while a raid has all four turrets down.
        RaidManager rm = plugin.getRaidManager();
        WitherCombatManager tsc = plugin.getWitherCombatManager();
        if (rm == null || tsc == null) return;
        if (rm.getRaidOnZone(claim.getOwner()) == null) return;   // zone isn't being raided
        if (!tsc.allTurretsDestroyed(claim)) return;              // at least one turret still up

        e.setCancelled(true);
        denyMessage(p, "open chests during a raid in", claim);
    }

    /** Container blocks the raid lockout seals: chests, barrels, shulker boxes, and the rest. */
    private static boolean isLootContainer(Material m) {
        if (Tag.SHULKER_BOXES.isTagged(m)) return true;
        return switch (m) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DISPENSER, DROPPER,
                 FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND -> true;
            default -> false;
        };
    }

    /** Door, every means of opening one, and tripwires that could trigger one. */
    private static boolean isDoorRelated(Material m) {
        if (Tag.DOORS.isTagged(m)) return true;
        if (Tag.TRAPDOORS.isTagged(m)) return true;
        if (Tag.FENCE_GATES.isTagged(m)) return true;
        if (Tag.BUTTONS.isTagged(m)) return true;
        if (Tag.WOODEN_PRESSURE_PLATES.isTagged(m)) return true;
        return switch (m) {
            case STONE_PRESSURE_PLATE,
                 LIGHT_WEIGHTED_PRESSURE_PLATE,
                 HEAVY_WEIGHTED_PRESSURE_PLATE,
                 POLISHED_BLACKSTONE_PRESSURE_PLATE,
                 LEVER,
                 TRIPWIRE -> true;
            default -> false;
        };
    }

    /**
     * Cancel projectile-triggered block interactions inside foreign zones. This is what
     * stops a player from shooting an arrow over the wall to press a wooden button or
     * tripwire. Bow firing itself isn't touched — only the resulting interaction.
     *
     * <p>Mob-source projectiles (skeleton arrows etc.) and non-projectile entity
     * interactions are left alone so the owner's own redstone mechanics still work.
     */
    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent e) {
        if (!(e.getEntity() instanceof Projectile p)) return;
        Claim claim = claims.getClaimAt(e.getBlock().getLocation());
        if (claim == null) return;
        ProjectileSource source = p.getShooter();
        if (source instanceof Player shooter) {
            if (claim.isMember(shooter.getUniqueId())) return;
            if (shooter.hasPermission("homesystem.bypass")) return;
            e.setCancelled(true);
        }
        // Non-player shooters (skeletons, dispensers' projectiles, etc.) are allowed —
        // they don't represent a player griefing through the wall.
    }

    /**
     * Cancel piston operations that would cross a claim boundary. A piston entirely
     * inside its own claim (or entirely outside any claim) is unaffected; a piston
     * outside trying to push into a claim, or a cross-claim push, is denied.
     */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistonCrossesClaims(e.getBlock(), e.getDirection(), e.getBlocks(), false)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistonCrossesClaims(e.getBlock(), e.getDirection(), e.getBlocks(), true)) {
            e.setCancelled(true);
        }
    }

    private boolean pistonCrossesClaims(Block piston, BlockFace direction, List<Block> blocks, boolean retracting) {
        Claim pistonClaim = claims.getClaimAt(piston.getLocation());
        BlockFace moveDir = retracting ? direction.getOppositeFace() : direction;

        // On extend, the piston head moves into the block adjacent to the piston body.
        if (!retracting) {
            Claim headDest = claims.getClaimAt(piston.getRelative(direction).getLocation());
            if (headDest != pistonClaim) return true;
        }
        for (Block b : blocks) {
            Claim from = claims.getClaimAt(b.getLocation());
            Claim to = claims.getClaimAt(b.getRelative(moveDir).getLocation());
            if (from != pistonClaim || to != pistonClaim) return true;
        }
        return false;
    }

    /**
     * @return true if the block at {@code blockLoc} is inside a claim the player isn't a
     *         member of (and they don't have bypass). Sends a throttled chat warning if so.
     */
    private boolean denyIfForeign(Player p, Location blockLoc, String actionVerb) {
        Claim claim = claims.getClaimAt(blockLoc);
        if (claim == null) return false;
        if (claim.isMember(p.getUniqueId())) return false;
        if (p.hasPermission("homesystem.bypass")) return false;
        denyMessage(p, actionVerb, claim);
        return true;
    }

    private void denyMessage(Player p, String actionVerb, Claim claim) {
        long now = System.currentTimeMillis();
        Long last = lastDenyMessage.get(p.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        lastDenyMessage.put(p.getUniqueId(), now);
        p.sendMessage(ChatColor.RED + "You can't " + actionVerb + " "
                + claims.resolveName(claim.getOwner()) + "'s zone.");
    }
}
