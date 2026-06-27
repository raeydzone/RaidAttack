package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.season.EventManager;
import com.raeyd.raidattack.season.SeasonEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Owns the user-facing surface of the raid feature:
 * <ul>
 *   <li>{@code /raid} — opens the {@link RaidGUI} from the caller's perspective.</li>
 *   <li>{@code /raid dev} — same, but actor = {@link HomeSystemPlugin#DEV_UUID}. Lets the dev
 *       fake-player be the attacker for self-testing (mirrors the {@code /HomeSystem dev}
 *       pattern). Cost is waived in dev mode because the dev actor has no inventory.</li>
 *   <li>{@link InventoryClickEvent} listener — when the player clicks a dirt tile in our GUI,
 *       runs the cost check / charge / {@link RaidManager#startRaid} sequence and fires the
 *       owner-side notifications + sound + boss bar.</li>
 * </ul>
 *
 * <p>This class implements {@link Listener} because the click flow and the command flow are
 * tightly coupled (both touch the same cost helpers + start logic). Splitting them across two
 * classes would mean threading the same dependencies through twice without much benefit.
 */
public final class RaidCommand implements CommandExecutor, TabCompleter, Listener {

    /** Minimum units that must be available in inventory to start a raid. */
    public static final int MIN_UNITS = 32;
    /** Cap on units taken from inventory — anything beyond is left untouched. */
    public static final int MAX_UNITS = 64;

    /** Players whose NEXT raid-list open came from the Bedrock /ra menu (so it gets the Raid
     *  Selector slider). A typed /raid never sets this, so a Bedrock player using the COMMAND
     *  behaves exactly like a Java player — the divergence is menu-vs-command, not client. */
    private static final Set<UUID> MENU_RAID_OPEN = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Called by BedrockMenu right before it runs /raid, to flag that open as menu-originated. */
    public static void markMenuOpen(UUID id) { MENU_RAID_OPEN.add(id); }

    private final HomeSystemPlugin plugin;

    public RaidCommand(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    // -- command --------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /raid.");
            return true;
        }
        // Was this /raid triggered by the Bedrock menu (vs typed)? Consumed once; drives whether the
        // target list opens with the Raid Selector slider or the plain Java auto-charge behaviour.
        boolean fromMenu = MENU_RAID_OPEN.remove(player.getUniqueId());
        // /raid inv — open the caller's own persistent loot inventory (the 3x9 "stolen goods"
        // chest that the steal-ticker drops items into when they raid someone).
        if (args.length >= 1 && args[0].equalsIgnoreCase("inv")) {
            plugin.getRaidLoot().open(player, player.getUniqueId());
            return true;
        }
        // /raid dev — actor is the imaginary dev player. Permission-gated the same way
        // /HomeSystem dev is, since it bypasses the cost.
        if (args.length >= 1 && args[0].equalsIgnoreCase("dev")) {
            // x2 shield: (1) owner per our rights system (not op); (2) dev mode on.
            if (!plugin.getRightsManager().isOwner(player)) {
                player.sendMessage(ChatColor.RED + "You don't have permission for dev raid mode.");
                return true;
            }
            if (!plugin.isDevMode()) {
                player.sendMessage(ChatColor.RED + "Dev raid mode is disabled. Set "
                        + ChatColor.WHITE + "dev_mode: true" + ChatColor.RED + " in config.yml (server off).");
                return true;
            }
            // /raid dev inv — open the dev actor's loot inventory. In dev raids the attacker is
            // DEV_UUID, so stolen items land in DEV_UUID's inventory; this is how the tester
            // inspects the loot the fake attacker pulled.
            if (args.length >= 2 && args[1].equalsIgnoreCase("inv")) {
                plugin.getRaidLoot().open(player, HomeSystemPlugin.DEV_UUID);
                return true;
            }
            RaidGUI.open(plugin, player, HomeSystemPlugin.DEV_UUID, true, fromMenu);
            return true;
        }
        // Season gate: raiding is locked until its event date (no bypass for normal players).
        if (!plugin.getEventManager().raidingEnabled()
                && !player.hasPermission(EventManager.BYPASS_PERM)) {
            sendRaidingLocked(player);
            return true;
        }
        RaidGUI.open(plugin, player, player.getUniqueId(), false, fromMenu);
        return true;
    }

    /** Tell a player when raiding unlocks (absolute UTC date + relative countdown). */
    private void sendRaidingLocked(Player player) {
        EventManager em = plugin.getEventManager();
        player.sendMessage(ChatColor.GOLD + "Raiding unlocks " + em.formattedWhen(SeasonEvent.RAIDING)
                + ChatColor.GRAY + "  (in " + EventManager.relative(em.remainingMillis(SeasonEvent.RAIDING))
                + ").");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String pre = args[0].toLowerCase(java.util.Locale.ROOT);
            if ("inv".startsWith(pre)) out.add("inv");
            if (plugin.devCommandsAllowed(player)) {
                if ("dev".startsWith(pre)) out.add("dev");
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("dev") && plugin.devCommandsAllowed(player)) {
            List<String> out = new ArrayList<>();
            if ("inv".startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) out.add("inv");
            return out;
        }
        return Collections.emptyList();
    }

    // -- GUI click listener ---------------------------------------------------

    /**
     * Click handler. Three responsibilities, in order:
     * <ol>
     *   <li>Lock the inventory — we never want the player to drag items in/out.</li>
     *   <li>Identify the clicked claim from the slot map (not the displayed item, which a
     *       creative admin could swap).</li>
     *   <li>If the clicked tile is a dirt (raidable), run the start flow.</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent ev) {
        Inventory top = ev.getView().getTopInventory();
        if (!(top.getHolder() instanceof RaidGUI gui)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player player)) return;
        if (ev.getClickedInventory() != top) return;     // ignore clicks in the player's own inv

        UUID ownerId = gui.getOwnerAt(ev.getRawSlot());
        if (ownerId == null) return;
        ItemStack tile = ev.getCurrentItem();
        if (tile == null) return;
        if (tile.getType() != Material.DIRT) {
            // Magma / allium are display-only. Friendly toast for the click so the player
            // doesn't think nothing happened.
            if (tile.getType() == Material.MAGMA_BLOCK) {
                player.sendMessage(ChatColor.RED + "That zone is already being raided.");
            } else if (tile.getType() == Material.ALLIUM) {
                player.sendMessage(ChatColor.AQUA + "That zone is friendly — you can't raid it.");
            }
            return;
        }

        // Eligible. Run the start flow. Close the GUI either way so the click outcome is
        // unambiguous and a follow-up message land cleanly in chat. requestedUnits = -1 means
        // "decide now" — Java auto-charges the max; Bedrock opens the Raid Selector slider.
        player.closeInventory();
        attemptStart(player, gui, ownerId, -1);
    }

    /** Block drag-and-drop on our chest — easier to cancel here than handle partials. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent ev) {
        if (ev.getView().getTopInventory().getHolder() instanceof RaidGUI) {
            ev.setCancelled(true);
        }
    }

    // -- start flow -----------------------------------------------------------

    /**
     * Run every check + side-effect needed to actually begin a raid against {@code ownerId}.
     * Anything that goes wrong sends a friendly message and bails — we never half-start a raid
     * and leave the player's inventory short of diamonds.
     */
    private void attemptStart(Player caller, RaidGUI gui, UUID ownerId, int requestedUnits) {
        UUID actorId = gui.getActorId();
        boolean asDev = gui.isDevMode();
        String actorName = asDev ? "dev" : caller.getName();

        // Season gate (defence-in-depth — the GUI open is already gated, this covers a GUI that was
        // opened just before the unlock instant and clicked just after). Dev raids bypass.
        if (!asDev && !plugin.getEventManager().raidingEnabled()
                && !caller.hasPermission(EventManager.BYPASS_PERM)) {
            sendRaidingLocked(caller);
            return;
        }

        Claim targetClaim = plugin.getClaimManager().getClaimOf(ownerId);
        if (targetClaim == null) {
            caller.sendMessage(ChatColor.RED + "That claim no longer exists.");
            return;
        }
        // Race: another raid started on this zone after the GUI was opened but before the
        // click landed. Re-check.
        if (plugin.getRaidManager().getRaidOnZone(ownerId) != null) {
            caller.sendMessage(ChatColor.RED + "That zone is already being raided.");
            return;
        }
        // Race: zone became friendly between open and click (added as friend, joined alliance).
        if (isFriendlyToActor(targetClaim, actorId)) {
            caller.sendMessage(ChatColor.YELLOW + "That zone is friendly to you now — can't raid.");
            return;
        }
        // You also can't raid anyone on YOUR OWN friends list / in your alliance (the reverse
        // direction that isFriendlyToActor doesn't cover). Real raids only — dev mode bypasses so
        // the tester can still raid a friended base to verify the defence behaves.
        if (!asDev && plugin.getRaidManager().isFriendlyToAttacker(actorId, ownerId)) {
            caller.sendMessage(ChatColor.YELLOW + "You can't raid someone on your friends list or in your alliance.");
            return;
        }

        // Raid-entanglement lock — both sides are frozen out of starting a raid for the duration:
        //   * if you're already raiding someone, you can't open a second raid (one at a time), and
        //   * if your OWN base is currently being raided, you can't raid back or raid anyone else.
        // (This is the same entanglement predicate as RaidManager#isInActiveRaid, split out here so
        // each case gets a tailored message.) Dev bypasses so the tester can stack raids freely; the
        // DEV_UUID actor is never a real attacker/defender so it would never trip these anyway.
        if (!asDev) {
            RaidManager rm = plugin.getRaidManager();
            if (rm.getRaidOnZone(actorId) != null) {
                caller.sendMessage(ChatColor.RED
                        + "Your base is under raid — you can't start a raid until it's over.");
                return;
            }
            if (!rm.raidsByAttacker(actorId).isEmpty()) {
                caller.sendMessage(ChatColor.RED
                        + "You're already raiding someone — finish that raid first.");
                return;
            }
        }

        // The dev base (owned by DEV_UUID) is the always-available test target: it ignores the
        // online-only requirement AND the 36 h re-raid cooldown, no matter how it's raided
        // (dev mode or a normal /raid that happens to target it). Real bases are unaffected.
        boolean devBase = ownerId.equals(HomeSystemPlugin.DEV_UUID);

        // Phase 2 raid limits. Both are skipped in dev mode so the tester can re-raid freely.
        if (!asDev) {
            // (2a) The defender must be online — no raiding an absent player's base. The dev
            // fake-player (DEV_UUID) is never a real online entity, so we treat it as always
            // online; otherwise its base could never be raided to test this very feature.
            boolean ownerOnline = devBase || Bukkit.getPlayer(ownerId) != null;
            if (!ownerOnline) {
                caller.sendMessage(ChatColor.RED + "You can only raid "
                        + plugin.getClaimManager().resolveName(ownerId) + " while they're online.");
                return;
            }
            // (2b) A base that was raided is protected for 36 h — except the dev base, which is
            // exempt so it can be re-raided immediately during testing.
            if (!devBase) {
                long cooldown = plugin.getRaidManager().getZoneCooldownRemainingMs(ownerId);
                if (cooldown > 0) {
                    caller.sendMessage(ChatColor.RED + "That base was recently raided — it's protected for another "
                            + formatDuration(cooldown) + ".");
                    return;
                }
            }
        }

        // ───────────────────────────────────────────────────────────────────────────────────────
        // BEDROCK DIVERGENCE — the ONLY place the Bedrock flow differs from Java. Java auto-charges
        // the max affordable the instant you click a target. Bedrock instead opens the Raid Selector
        // slider FIRST (so the player picks their spend), and that picker re-enters THIS SAME method
        // with the chosen units (requestedUnits >= 0). It is opened BEFORE the cost check on purpose,
        // so the selector always shows — the affordability gate then applies on "Start Raid", exactly
        // like Java's gate applies on click. Everything BELOW this block is the single shared source
        // of truth (cost check → charge → start), run identically for both editions.
        if (requestedUnits < 0 && gui.isFromMenu() && isFloodgate(caller)) {
            openRaidSelector(caller, gui, ownerId);     // → re-enters attemptStart with chosen units
            return;
        }

        // ── SHARED cost flow (Java auto-spend, or the Bedrock selector's chosen spend) ───────────
        // BOTH /raid and /raid dev pay from the CALLER's (real player's) inventory and run every
        // check. The asDev flag only affects who's recorded as the attacker. This way "dev" is a
        // real end-to-end test of the cost flow, not a sandbox that skips half the logic.
        int dia = countMaterial(caller.getInventory(), Material.DIAMOND);
        int eme = countMaterial(caller.getInventory(), Material.EMERALD);
        int have = Math.min(dia, eme);
        if (have < MIN_UNITS) {
            caller.sendMessage(ChatColor.RED + "Not enough resources. Need at least "
                    + MIN_UNITS + " diamond + " + MIN_UNITS + " emerald (1:1).  "
                    + "You have " + ChatColor.AQUA + dia + ChatColor.RED + " diamond / "
                    + ChatColor.AQUA + eme + ChatColor.RED + " emerald.");
            return;
        }
        final int units;
        if (requestedUnits < 0) {
            units = Math.min(have, MAX_UNITS);                                  // Java: max affordable
        } else {
            units = Math.max(MIN_UNITS, Math.min(requestedUnits, MAX_UNITS));   // Bedrock: chosen spend
            if (have < units) {
                caller.sendMessage(ChatColor.RED + "Not enough resources for a " + units + "-unit raid. Need "
                        + units + " diamond + " + units + " emerald (1:1).  You have "
                        + ChatColor.AQUA + dia + ChatColor.RED + " diamond / "
                        + ChatColor.AQUA + eme + ChatColor.RED + " emerald.");
                return;
            }
        }
        // Charge — both must succeed or we refund (defensive). removeItem returns leftover
        // counts it COULDN'T remove, which should be empty given our count above.
        ItemStack charge1 = new ItemStack(Material.DIAMOND, units);
        ItemStack charge2 = new ItemStack(Material.EMERALD, units);
        var leftDia = caller.getInventory().removeItem(charge1);
        var leftEme = caller.getInventory().removeItem(charge2);
        if (!leftDia.isEmpty() || !leftEme.isEmpty()) {
            // Refund anything we did take and bail. Inventory shouldn't change.
            int refundedDia = units - leftDia.values().stream().mapToInt(ItemStack::getAmount).sum();
            int refundedEme = units - leftEme.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (refundedDia > 0) caller.getInventory().addItem(new ItemStack(Material.DIAMOND, refundedDia));
            if (refundedEme > 0) caller.getInventory().addItem(new ItemStack(Material.EMERALD, refundedEme));
            caller.sendMessage(ChatColor.RED + "Inventory changed during charge — please retry.");
            return;
        }

        // All checks passed — stamp the raid into RaidManager.
        ActiveRaid raid = plugin.getRaidManager().startRaid(
                actorId, actorName, ownerId, targetClaim.getWorldId(), units);

        // Start the 36 h re-raid protection now. Skipped in dev mode AND for the dev base itself
        // (so the dev base never gets stamped with a cooldown, allowing immediate re-raids).
        if (!asDev && !devBase) plugin.getRaidManager().stampZoneCooldown(ownerId);

        // Boss bar — created here, visibility resolves over the next tick. Even though the
        // raid is in the DELAY phase, we want the bar up immediately so defenders see the
        // countdown.
        plugin.getRaidBossBars().onRaidStarted(raid);

        // Notify everyone. A server-wide broadcast announces the raid to ALL players (the
        // attacker included — so they no longer need a separate "you started raiding" line).
        // The defending side (owner + friends + alliance) additionally gets the raid horns.
        // Detailed numbers live only in the /raid GUI lore and the boss bar, per spec.
        broadcastRaidStart(raid);
        soundDefendingSide(raid, targetClaim);
    }

    /** Same friendly-check as the GUI builder, kept duplicated here so the race-check path
     *  doesn't need to instantiate a fresh GUI to ask the question. */
    private boolean isFriendlyToActor(Claim claim, UUID actorId) {
        if (claim.isMember(actorId)) return true;
        AllianceManager am = plugin.getAllianceManager();
        if (am == null) return false;
        Alliance actorAlliance = am.getOf(actorId);
        if (actorAlliance == null) return false;
        Alliance ownerAlliance = am.getOf(claim.getOwner());
        return ownerAlliance != null && ownerAlliance == actorAlliance;
    }

    // -- notifications --------------------------------------------------------

    /**
     * Server-wide announcement that a raid is starting. Goes to EVERY online player so the whole
     * server knows "&lt;attacker&gt; is about to raid &lt;owner&gt;'s base!" — this is also what the
     * attacker now sees (we dropped the dedicated attacker-only confirmation, since the broadcast
     * already covers them). No numbers / countdown per spec; the detail lives in the /raid GUI
     * lore and the boss bar.
     */
    private void broadcastRaidStart(ActiveRaid raid) {
        String ownerName = plugin.getClaimManager().resolveName(raid.getZoneOwnerId());
        String line = ChatColor.RED + "" + ChatColor.BOLD + raid.getAttackerName()
                + ChatColor.RESET + ChatColor.RED + " is about to raid "
                + ChatColor.BOLD + ownerName + ChatColor.RESET + ChatColor.RED + "'s base!";
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(line);
        }
    }

    /**
     * Play the raid horns to the defending side only — owner + their claim friends + their
     * alliance members. The broadcast (above) already delivered the text to everyone; this adds
     * the audible alarm for the people who actually need to scramble to defend.
     */
    private void soundDefendingSide(ActiveRaid raid, Claim targetClaim) {
        // Compose the recipient set: owner + friends + alliance members. Dedup via Set.
        Set<UUID> defenders = new HashSet<>();
        defenders.add(raid.getZoneOwnerId());
        defenders.addAll(targetClaim.getFriends());
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(raid.getZoneOwnerId());
            if (a != null) defenders.addAll(a.getMembers());
        }
        for (UUID id : defenders) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            // Two layered sounds — both vanilla 1.21, both attention-grabbing. EVENT_RAID_HORN
            // is the village raid horn (thematic), ITEM_GOAT_HORN_SOUND_0 is the "Ponder Horn"
            // alarm tone (loud + carries across distance). Volume 1.0 is the max audible
            // value; pitch tweaks differentiate the two so they don't mask each other.
            // Entity-attached overload (p.playSound(p, ...)) instead of the location overload
            // so the sound source follows the player. Otherwise the client snapshots the
            // location at play-time and the sound audibly "drifts behind" if the player moves —
            // exactly the bug the user reported.
            p.playSound(p, Sound.EVENT_RAID_HORN,        1.0f, 1.0f);
            p.playSound(p, Sound.ITEM_GOAT_HORN_SOUND_0, 1.0f, 0.9f);
        }
    }

    // -- Bedrock Raid Selector (slider) ---------------------------------------

    /**
     * Bedrock-only raid spend picker. Unlike Java (which auto-charges the max affordable on the
     * chest click), Bedrock players choose how many diamonds + emeralds to spend on a slider —
     * {@link #MIN_UNITS} ({@value #MIN_UNITS}) up to the most they can afford (capped at
     * {@link #MAX_UNITS}). Each slider step shows that spend's simultaneous-raider and total-spawn
     * numbers live, then a Start/Cancel confirm. "Start" re-enters {@link #attemptStart} with the
     * chosen units — which re-runs every eligibility + cost check (state may have shifted while the
     * player was sliding). Magma-block themed (magma = a zone actively being raided).
     */
    private void openRaidSelector(Player caller, RaidGUI gui, UUID ownerId) {
        String target = plugin.getClaimManager().resolveName(ownerId);
        boolean asDev = gui.isDevMode();
        // Full range — affordability is enforced when they press Start (the shared cost check),
        // exactly like Java. So the picker always opens, even if the player can't afford it yet.
        // ONE page: the step slider is the picker AND the live detail panel. Each step label is
        // multi-line (Bedrock renders \n + § colours and updates the shown step as you slide), so the
        // cost + raid stats stay in sync with the slider, numbers in green. Submit starts the raid;
        // the X cancels. No emoji (they render as "?" on Bedrock).
        List<String> steps = new ArrayList<>();
        for (int u = MIN_UNITS; u <= MAX_UNITS; u++) {
            steps.add("§a" + u + " §fdiamonds & emeralds"
                    + "\n\n§7Raid Details:"
                    + "\n§fSimultaneous Raiders: §a" + ActiveRaid.activeGoalFor(u)
                    + "\n§fTotal Spawns: §a" + ActiveRaid.totalSpawnsFor(u));
        }
        sendForm(caller, CustomForm.builder()
                .title("§4Raid Selector §8— §c" + target)
                .label("§7Slide to set your spend, then press §aSubmit§7 to start the raid.")
                .stepSlider("§fCosts", steps, 0)                               // Bedrock appends ": " itself
                .closedResultHandler(() -> reopenTargetList(caller, asDev))    // X = cancel
                .validResultHandler(r -> onMain(() ->
                        attemptStart(caller, gui, ownerId, MIN_UNITS + r.asStepSlider(1)))));   // Submit = start
    }

    /** Cancel path — re-open the raid target chest GUI so the player lands back where they were. */
    private void reopenTargetList(Player caller, boolean asDev) {
        onMain(() -> { if (caller.isOnline()) caller.performCommand(asDev ? "raid dev" : "raid"); });
    }

    private boolean isFloodgate(Player p) {
        try { return FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId()); }
        catch (Throwable t) { return false; }
    }

    /** Cumulus form callbacks fire on Floodgate's thread; bounce back to the main thread for managers. */
    private void onMain(Runnable r) { plugin.getServer().getScheduler().runTask(plugin, r); }

    private void sendForm(Player p, FormBuilder<?, ?, ?> form) {
        try { FloodgateApi.getInstance().sendForm(p.getUniqueId(), form); }
        catch (Throwable t) { plugin.getLogger().warning("Raid selector form failed for " + p.getName() + ": " + t.getMessage()); }
    }

    // -- helpers --------------------------------------------------------------

    /** Human-readable "Xh Ym" / "Ym" from a millisecond duration, for cooldown messages. */
    private static String formatDuration(long ms) {
        long totalMin = ms / 60_000L;
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (h > 0) return h + "h " + m + "m";
        return Math.max(1, m) + "m";
    }

    private static int countMaterial(PlayerInventory inv, Material mat) {
        int total = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it != null && it.getType() == mat) total += it.getAmount();
        }
        // Don't count off-hand / armour for material costs — the player's main storage is what
        // intuitively "counts" when they think about spending resources.
        return total;
    }
}
