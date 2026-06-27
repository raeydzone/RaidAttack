package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.combat.WitherCombatManager;
import com.raeyd.raidattack.quest.Quest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * The raid looting system. Two responsibilities:
 *
 * <ol>
 *   <li><b>Persistent /raid inventories.</b> Every player has a single 27-slot loot chest
 *       ({@link RaidLootInventory}), stored in {@code raid_inventories.yml} with full NBT
 *       fidelity (Bukkit serialises {@link ItemStack} losslessly). Loaded on enable, saved on
 *       every deposit and on inventory close.</li>
 *   <li><b>The steal ticker.</b> Every 10 s, for each active raid whose zone currently has all of
 *       its turrets destroyed (a base with no turrets counts as breached), one slice of loot is
 *       moved out of a zone chest into the attacker's loot inventory. The stealable set is scanned
 *       <b>once per all-turrets-down window</b> (every stealable stack across all chests, scan
 *       capped at {@link #SCAN_CAP}) and then <b>locked</b> — we don't rescan each tick. Each tick
 *       pulls one entry from the locked plan at random: unstackable items go whole, stackables go
 *       {@link #MIN_STEAL_AMOUNT}–{@link #MAX_STEAL_AMOUNT} units at a time (so a 64-stack of ingots
 *       or diamond/emerald blocks is drained a slice per interval, not all at once). Looting only
 *       runs while the raid is ACTIVE — never during the DELAY/"Incoming" countdown. The ticker
 *       pauses and the plan is <b>discarded</b> the instant any turret comes back online; if the
 *       turrets are all knocked down again a fresh plan is built, so each all-down window is its own
 *       looting opportunity. (The one-shot XP bonus for downing all turrets lives in
 *       {@link WitherCombatManager} and is unaffected — it still fires only the first time.)</li>
 * </ol>
 */
public final class RaidLootManager implements Listener {

    /** 10 s between steals (20 ticks/s). */
    private static final long STEAL_PERIOD_TICKS = 200L;
    /** Hard cap on stacks examined per zone scan, to bound the cost on huge bases. The plan is
     *  built once per all-down window and then locked, so this scan happens at most once per
     *  window — no per-tick rescanning. */
    private static final int SCAN_CAP = 1500;
    /** Minimum units of a (stackable) item we steal at once — never just 1-3 of something. */
    private static final int MIN_STEAL_AMOUNT = 4;
    /** Maximum units of a (stackable) item we steal in a single 10 s interval. Caps how fast a big
     *  stack drains: a full 64 of ingots or diamond/emerald blocks now leaves 8-at-a-time instead
     *  of all in one tick (which was too strong). The remainder is taken over the next intervals. */
    private static final int MAX_STEAL_AMOUNT = 8;

    /**
     * Placeable blocks are only worth stealing if they're on this list — junk building blocks
     * (dirt, cobble, planks, …) are ignored entirely. Non-block items (tools, food, ingots, gems,
     * …) are always fair game; only BLOCKS are gated. Edit freely to add/remove "valuables".
     */
    private static final Set<Material> VALUABLE_BLOCKS = EnumSet.of(
            Material.OBSIDIAN, Material.CRYING_OBSIDIAN,
            Material.SOUL_SAND, Material.SOUL_SOIL,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_BLOCK,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.GOLD_BLOCK, Material.IRON_BLOCK,
            Material.LAPIS_BLOCK, Material.REDSTONE_BLOCK, Material.COAL_BLOCK, Material.COPPER_BLOCK,
            Material.RAW_IRON_BLOCK, Material.RAW_GOLD_BLOCK, Material.RAW_COPPER_BLOCK,
            Material.AMETHYST_BLOCK);

    /** Whether this stack is even a candidate to steal: any non-block item, or a valuable block. */
    private static boolean isStealable(ItemStack it) {
        Material m = it.getType();
        if (m.isBlock()) return VALUABLE_BLOCKS.contains(m);
        return true;
    }

    private final HomeSystemPlugin plugin;
    /** ownerId → their persistent loot inventory. Hydrated lazily / on load. */
    private final Map<UUID, RaidLootInventory> inventories = new HashMap<>();
    /** raidId → the current steal plan. Rebuilt on each transition into all-turrets-down. */
    private final Map<UUID, LootPlan> plans = new HashMap<>();
    /** raidIds that had all four turrets down on the previous tick. Used to detect the
     *  not-all-down → all-down transition that (re)builds a plan and starts a new steal window. */
    private final Set<UUID> downRaids = new HashSet<>();

    private BukkitTask task;

    public RaidLootManager(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    // -- lifecycle ------------------------------------------------------------

    public void start() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                STEAL_PERIOD_TICKS, STEAL_PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        // Synchronous flush on shutdown so the last deposits aren't lost to an unscheduled async task.
        plugin.getWorldDatabase().saveRaidInventoryBlobs(serializeAll());
    }

    public void load() {
        inventories.clear();
        for (Map.Entry<UUID, byte[]> e : plugin.getWorldDatabase().loadRaidInventoryBlobs().entrySet()) {
            UUID id = e.getKey();
            try {
                ItemStack[] contents = deserializeContents(e.getValue());
                RaidLootInventory inv = new RaidLootInventory(id, titleFor(id));
                for (int i = 0; i < contents.length && i < RaidLootInventory.SIZE; i++) {
                    if (contents[i] != null && !contents[i].getType().isAir()) inv.getInventory().setItem(i, contents[i]);
                }
                inventories.put(id, inv);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load raid loot for " + id + ": " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + inventories.size() + " raid loot inventory(ies).");
    }

    /** Serialize (on the main thread — ItemStack serialization isn't async-safe), write off it. */
    public void save() {
        Map<UUID, byte[]> blobs = serializeAll();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.getWorldDatabase().saveRaidInventoryBlobs(blobs));
    }

    private Map<UUID, byte[]> serializeAll() {
        Map<UUID, byte[]> blobs = new HashMap<>();
        for (Map.Entry<UUID, RaidLootInventory> e : inventories.entrySet()) {
            try { blobs.put(e.getKey(), serializeContents(e.getValue().getInventory().getContents())); }
            catch (IOException ex) { plugin.getLogger().warning("Failed to serialize raid loot for " + e.getKey() + ": " + ex.getMessage()); }
        }
        return blobs;
    }

    private static byte[] serializeContents(ItemStack[] contents) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(contents.length);
            for (ItemStack it : contents) out.writeObject(it);
        }
        return bos.toByteArray();
    }

    private static ItemStack[] deserializeContents(byte[] data) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            ItemStack[] arr = new ItemStack[n];
            for (int i = 0; i < n; i++) arr[i] = (ItemStack) in.readObject();
            return arr;
        }
    }

    // -- inventory access -----------------------------------------------------

    /** Get-or-create this player's persistent loot inventory. */
    public RaidLootInventory get(UUID ownerId) {
        return inventories.computeIfAbsent(ownerId, id -> new RaidLootInventory(id, titleFor(id)));
    }

    /** Open {@code ownerId}'s loot inventory to {@code viewer}. */
    public void open(Player viewer, UUID ownerId) {
        viewer.openInventory(get(ownerId).getInventory());
    }

    /** Persist whenever a loot inventory is closed (the owner may have emptied it). Also returns
     *  Bedrock menu users to the Raids screen so the /ra menu is never abandoned (no-op otherwise). */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof RaidLootInventory) {
            save();
            if (e.getPlayer() instanceof Player p) {
                com.raeyd.raidattack.core.BedrockMenu.reopenRaidsIfPending(plugin, p);
            }
        }
    }

    /**
     * Withdraw-only enforcement for the raid stash. A player may take items OUT of their own loot
     * inventory but may NEVER put anything IN — otherwise the 27-slot chest doubles as free extra
     * storage (stash junk, shuffle it around, retrieve later). We block every click action that
     * could add an item to the top (loot) inventory and allow only pure take-outs. Rearranging items
     * back INTO a loot slot is also blocked (it's indistinguishable from a deposit) — items picked up
     * out of the stash can only go to the player's own inventory or be dropped.
     *
     * <p>The loot ticker deposits via {@link RaidLootInventory#tryDepositFull} (a direct
     * {@code Inventory#addItem}, not a click event), so it is unaffected by this gate.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLootClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof RaidLootInventory)) return;

        InventoryAction action = e.getAction();
        switch (action) {
            // Pure removals / cursor drops / double-click gather — none of these ADD to the top inv.
            case PICKUP_ALL, PICKUP_HALF, PICKUP_SOME, PICKUP_ONE,
                 DROP_ALL_SLOT, DROP_ONE_SLOT, DROP_ALL_CURSOR, DROP_ONE_CURSOR,
                 COLLECT_TO_CURSOR, NOTHING:
                return;
            // Shift-click: dangerous only when moving FROM the player inv INTO the loot inv.
            case MOVE_TO_OTHER_INVENTORY: {
                Inventory clicked = e.getClickedInventory();
                if (clicked != null && clicked.getHolder() instanceof RaidLootInventory) {
                    return;                 // clicked a loot slot → moving OUT to the player inv: allow
                }
                e.setCancelled(true);       // clicked a player slot → would insert INTO loot: block
                return;
            }
            // PLACE_*, SWAP_WITH_CURSOR, HOTBAR_SWAP / HOTBAR_MOVE_AND_READD, SWAP_OFFHAND,
            // CLONE_STACK, UNKNOWN, … : all can insert into a slot. Block whenever the targeted raw
            // slot is in the top (loot) inventory; the same actions on the player's own slots are fine.
            default:
                if (e.getRawSlot() >= 0 && e.getRawSlot() < top.getSize()) e.setCancelled(true);
        }
    }

    /** A drag can scatter cursor items across multiple slots. If any of them land in the top (loot)
     *  inventory it's an insertion → cancel the whole drag. Drags wholly within the player inventory
     *  are left alone. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onLootDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof RaidLootInventory)) return;
        int topSize = top.getSize();
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < topSize) { e.setCancelled(true); return; }
        }
    }

    private String titleFor(UUID ownerId) {
        return ownerId.equals(HomeSystemPlugin.DEV_UUID)
                ? "Raid Inventory (dev)" : "Raid Inventory";
    }

    // -- steal ticker ---------------------------------------------------------

    private void tick() {
        RaidManager rm = plugin.getRaidManager();
        WitherCombatManager tsc = plugin.getWitherCombatManager();
        if (rm == null || tsc == null) return;

        // Drop plans + down-flags for raids that have ended so the maps don't grow without bound.
        plans.keySet().removeIf(id -> {
            ActiveRaid r = rm.getRaid(id);
            return r == null || r.getPhase() == ActiveRaid.Phase.ENDED;
        });
        downRaids.removeIf(id -> {
            ActiveRaid r = rm.getRaid(id);
            return r == null || r.getPhase() == ActiveRaid.Phase.ENDED;
        });

        for (ActiveRaid raid : new ArrayList<>(rm.allActive())) {
            if (raid.getPhase() == ActiveRaid.Phase.ENDED) continue;
            UUID raidId = raid.getRaidId();
            Claim zone = plugin.getClaimManager().getClaimOf(raid.getZoneOwnerId());
            if (zone == null) continue;

            // Looting may only begin once the raid is ACTIVE (raiders are spawning/draining).
            // During the DELAY ("Incoming") pre-spawn countdown NOTHING may be stolen yet —
            // otherwise a base with no turrets, which counts as vacuously breached
            // (allTurretsDestroyed == true for zero standing turrets), would get looted during
            // the countdown before the raid is even live. Treat DELAY like "not breached": drop
            // any stale plan/flag and wait. When the raid transitions to SPAWNING the next tick's
            // not-all-down → all-down detection rebuilds a fresh plan and stealing starts then.
            if (raid.getPhase() == ActiveRaid.Phase.DELAY) {
                downRaids.remove(raidId);
                plans.remove(raidId);
                continue;
            }

            // Sample defender presence for the ≥50%-time-in-zone bonus gate. 10 s cadence (= the
            // steal period), only while the raid is actively spawning/draining (the "fighting").
            if (raid.getPhase() == ActiveRaid.Phase.SPAWNING
                    || raid.getPhase() == ActiveRaid.Phase.DRAINING) {
                rm.sampleDefenderPresence(raid, zone, (int) (STEAL_PERIOD_TICKS / 20L));
            }

            boolean allDown = tsc.allTurretsDestroyed(zone);
            if (!allDown) {
                // A turret is back online — stop stealing AND throw away the plan. The next time
                // all four go down we build a fresh plan over whatever items remain, so each
                // all-down window is its own looting opportunity (per spec). Already-stolen items
                // stay gone (they're no longer in the chests to be re-planned).
                downRaids.remove(raidId);
                plans.remove(raidId);
                continue;
            }

            // All turrets are down. Build the steal plan ONCE, on the not-all-down → all-down
            // transition, and LOCK it: it's a fixed snapshot of the base's stealable stacks for this
            // window. We deliberately do NOT rescan/rebuild every tick — that old behaviour resampled
            // a fresh random ~10% each tick, which made stealing erratic and slow (~1 steal/1-2 min
            // instead of 1/10 s). Each tick now pulls exactly one stack out of the locked plan (see
            // stealOne). Once the plan is fully drained, nothing's left to steal this window.
            if (downRaids.add(raidId)) {                // add() returns true only on transition
                plans.put(raidId, buildPlan(zone));
            }
            LootPlan plan = plans.computeIfAbsent(raidId, id -> buildPlan(zone));
            if (plan.entries.isEmpty()) continue;       // locked plan fully drained — nothing left
            stealOne(raid, zone, plan);
        }
    }

    /**
     * Scan every loaded chest in the zone <b>once</b> and collect <b>every</b> stealable stack into
     * the plan (scan capped at {@link #SCAN_CAP} stacks). No random sampling — the plan is the full
     * stealable inventory of the base at breach time, shuffled so the per-tick pull is random order
     * ("pick one by one randomly"). The caller locks this plan for the whole all-down window. Double
     * chests are handled via {@link Chest#getBlockInventory()} so each half's 27 slots are counted
     * exactly once — {@code getInventory()} would return the merged 54-slot view and double-count.
     */
    private LootPlan buildPlan(Claim zone) {
        LootPlan plan = new LootPlan();
        World w = Bukkit.getWorld(zone.getWorldId());
        if (w == null) return plan;

        int minCX = zone.getMinX() >> 4, maxCX = zone.getMaxX() >> 4;
        int minCZ = zone.getMinZ() >> 4, maxCZ = zone.getMaxZ() >> 4;
        int scanned = 0;

        outer:
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                Chunk chunk = w.getChunkAt(cx, cz);
                for (BlockState bs : chunk.getTileEntities(false)) {
                    if (!(bs instanceof Container container)) continue;
                    Location loc = bs.getLocation();
                    if (!zone.contains(loc)) continue;
                    Inventory inv = inventoryOf(container);
                    if (inv == null) continue;
                    ItemStack[] contents = inv.getContents();
                    for (int slot = 0; slot < contents.length; slot++) {
                        ItemStack it = contents[slot];
                        if (it == null || it.getType().isAir()) continue;
                        if (++scanned > SCAN_CAP) break outer;
                        if (!isStealable(it)) continue;     // skip junk blocks; valuables + items pass
                        plan.entries.add(new LootEntry(
                                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), slot));
                    }
                }
            }
        }
        Collections.shuffle(plan.entries);
        if (!plan.entries.isEmpty()) {
            plugin.getLogger().info("Loot plan for zone "
                    + plugin.getClaimManager().resolveName(zone.getOwner()) + ": "
                    + plan.entries.size() + " stealable stack(s) from " + scanned
                    + " scanned (locked for this all-down window).");
        }
        return plan;
    }

    /**
     * Move one slice of loot into the attacker's loot inventory, then stop for the tick. Re-resolves
     * every plan entry against the live world (chests may have changed), pruning dead ones.
     *
     * <p><b>Amount + preference.</b> We prefer a "real" bite — a random
     * {@link #MIN_STEAL_AMOUNT}–{@link #MAX_STEAL_AMOUNT} units — from any entry that can supply it:
     * an unstackable item (taken whole) or a stackable slot holding ≥ {@link #MIN_STEAL_AMOUNT}.
     * Shuffle order makes the pick among those random. ONLY when nothing in the plan can supply the
     * minimum do we fall back to the biggest small straggler — a 3-stack, else a 2, else a 1 — so a
     * defender can't permanently shield valuables by hoarding them in sub-4 stacks (the exploit). A
     * stackable slot with loot left after the cut is rotated to the back so big stacks drain a slice
     * at a time. Nothing resolvable → nothing stolen this tick.
     */
    private void stealOne(ActiveRaid raid, Claim zone, LootPlan plan) {
        World w = Bukkit.getWorld(zone.getWorldId());
        if (w == null) return;
        UUID attackerId = raid.getAttackerId();

        // One pass over the (shuffled) plan. Resolve each entry live; prune dead ones. Capture:
        //   - the FIRST preferred entry (unstackable, or stackable with >= MIN units) — random pick
        //     thanks to the shuffle; stop scanning the moment we find one.
        //   - failing that, the LARGEST small straggler (3 > 2 > 1) as the fallback victim.
        LootEntry preferred = null; ItemStack preferredItem = null; Inventory preferredInv = null;
        LootEntry fallback  = null; ItemStack fallbackItem  = null; Inventory fallbackInv  = null;
        int fallbackHave = 0;

        Iterator<LootEntry> it = plan.entries.iterator();
        while (it.hasNext()) {
            LootEntry entry = it.next();
            if (!w.isChunkLoaded(entry.x >> 4, entry.z >> 4)) continue;   // unloaded — leave for later
            BlockState bs = w.getBlockAt(entry.x, entry.y, entry.z).getState();
            if (!(bs instanceof Container container)) { it.remove(); continue; }
            Inventory inv = inventoryOf(container);
            if (inv == null || entry.slot < 0 || entry.slot >= inv.getSize()) { it.remove(); continue; }
            ItemStack item = inv.getItem(entry.slot);
            if (item == null || item.getType().isAir()) { it.remove(); continue; }

            boolean unstackable = item.getMaxStackSize() <= 1;
            int have = item.getAmount();
            if (unstackable || have >= MIN_STEAL_AMOUNT) {
                preferred = entry; preferredItem = item; preferredInv = inv;
                break;                                   // first preferred wins (random via shuffle)
            }
            if (have > fallbackHave) {                   // remember the biggest 1-3 straggler
                fallback = entry; fallbackItem = item; fallbackInv = inv; fallbackHave = have;
            }
        }

        // Preferred bite: whole for unstackables, random 4-8 for stackables (capped by what's there).
        if (preferred != null) {
            boolean unstackable = preferredItem.getMaxStackSize() <= 1;
            int have = preferredItem.getAmount();
            int take = unstackable ? have
                    : Math.min(have, ThreadLocalRandom.current()
                            .nextInt(MIN_STEAL_AMOUNT, MAX_STEAL_AMOUNT + 1));
            if (!depositAndRemove(raid, plan, preferred, preferredInv, preferredItem, take)) {
                announceFull(attackerId);
            }
            return;
        }

        // Fallback: nothing has >= 4 left. Take the biggest straggler whole (3, else 2, else 1).
        if (fallback != null) {
            if (!depositAndRemove(raid, plan, fallback, fallbackInv, fallbackItem, fallbackHave)) {
                announceFull(attackerId);
            }
        }
        // else: nothing resolvable in the plan — nothing to steal this tick.
    }

    /**
     * Try to move {@code take} units of {@code item} (at {@code entry}'s slot in {@code inv}) into the
     * attacker's loot inventory. On success: removes them from the chest, drops the entry from the
     * plan (or rotates it to the back if loot remains in the slot), persists, announces, returns true.
     * On a full loot inventory: leaves everything untouched and returns false (caller alerts).
     */
    private boolean depositAndRemove(ActiveRaid raid, LootPlan plan, LootEntry entry,
                                     Inventory inv, ItemStack item, int take) {
        ItemStack toSteal = item.clone();
        toSteal.setAmount(take);
        if (!get(raid.getAttackerId()).tryDepositFull(toSteal)) return false;
        plan.entries.remove(entry);
        int remaining = item.getAmount() - take;
        if (remaining > 0) {
            item.setAmount(remaining);
            inv.setItem(entry.slot, item);
            plan.entries.addLast(entry);     // still has loot — revisit on a later tick
        } else {
            inv.setItem(entry.slot, null);
        }
        save();
        announceSteal(raid, take, toSteal);
        return true;
    }

    /**
     * Announce a successful steal to BOTH sides, each with the {@code [Raid]} prefix:
     * <ul>
     *   <li>Attacker + companions (claim friends + alliance) — GREEN "you stole it"; the attacker
     *       also gets the pickup blip.</li>
     *   <li>Owner + companions — RED "it was stolen from your base", symmetric to the attacker
     *       side so the defenders always see what's leaving their chests.</li>
     * </ul>
     */
    private void announceSteal(ActiveRaid raid, int amount, ItemStack item) {
        String ownerName = plugin.getClaimManager().resolveName(raid.getZoneOwnerId());
        String attackerName = raid.getAttackerName();
        String itemName = prettyName(item);

        // Quest: the attacker successfully stole something during a raid.
        if (plugin.getQuests() != null) plugin.getQuests().complete(raid.getAttackerId(), Quest.STEAL_LOOT);

        for (UUID id : sidePool(raid.getAttackerId())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            RaidMessages.bonus(p, "Stole " + amount + "× " + itemName + " from " + ownerName + "'s base.");
            if (id.equals(raid.getAttackerId())) p.playSound(p, Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        }
        for (UUID id : sidePool(raid.getZoneOwnerId())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            RaidMessages.negative(p, attackerName + " stole " + amount + "× " + itemName
                    + " from " + ownerName + "'s base!");
        }
    }

    /** A player's "side" for steal notifications: themselves + their claim friends + alliance. */
    private Set<UUID> sidePool(UUID anchorId) {
        Set<UUID> pool = new HashSet<>();
        pool.add(anchorId);
        Claim c = plugin.getClaimManager().getClaimOf(anchorId);
        if (c != null) pool.addAll(c.getFriends());
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance a = am.getOf(anchorId);
            if (a != null) pool.addAll(a.getMembers());
        }
        return pool;
    }

    private void announceFull(UUID attackerId) {
        Player atk = Bukkit.getPlayer(attackerId);
        if (atk == null) return;
        atk.sendMessage(ChatColor.RED
                + "Your raid inventory is full — item left in the chest. Empty it with /raid inv.");
    }

    /** Block-local inventory for chests (double-chest safe), full inventory otherwise. */
    private static Inventory inventoryOf(Container c) {
        if (c instanceof Chest chest) return chest.getBlockInventory();
        return c.getInventory();
    }

    private static String prettyName(ItemStack it) {
        String raw = it.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return raw;
    }

    // -- plan data ------------------------------------------------------------

    private static final class LootEntry {
        final int x, y, z, slot;
        LootEntry(int x, int y, int z, int slot) {
            this.x = x; this.y = y; this.z = z; this.slot = slot;
        }
    }

    private static final class LootPlan {
        final LinkedList<LootEntry> entries = new LinkedList<>();
    }
}
