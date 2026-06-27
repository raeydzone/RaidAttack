package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The {@code /raid} (or {@code /raid dev}) chest GUI. Each occupied slot maps to one claim
 * elsewhere on the server, drawn as:
 * <ul>
 *   <li><b>Dirt block</b> — eligible to raid. Click starts the cost-check / start flow.</li>
 *   <li><b>Flower (allium)</b> — friendly to the actor (own claim, /hs friend, or alliance
 *       member). Display-only.</li>
 *   <li><b>Magma block</b> — already being raided. Display-only; lore shows attacker name
 *       and progress so the viewer knows what's going on.</li>
 * </ul>
 *
 * <p>Slot → owner mapping is captured in {@link #ownerBySlot} when the inventory is built so
 * the click listener can recover which claim the player clicked without ever inspecting the
 * displayed {@link ItemStack} (which a creative-mode admin could swap out).
 *
 * <p>The {@code actorId} field is the UUID the raid will be attributed to — equal to the
 * caller's UUID for normal {@code /raid}, and {@link HomeSystemPlugin#DEV_UUID} for
 * {@code /raid dev}. {@link InventoryHolder} can't be told apart in onClick without something
 * to look at, which is why this class implements it: {@code top.getHolder() instanceof RaidGUI}
 * is the listener's gate.
 */
public final class RaidGUI implements InventoryHolder {

    public static final String TITLE_NORMAL = ChatColor.DARK_RED + "Raid — choose target";
    public static final String TITLE_DEV    = ChatColor.LIGHT_PURPLE + "[dev] "
            + ChatColor.DARK_RED + "Raid — choose target";

    /** Single chest. 27 claims is plenty for a small dev server; pagination is a later task. */
    private static final int SIZE = 27;

    private final HomeSystemPlugin plugin;
    private final UUID actorId;
    private final boolean asDev;
    /** True only when this list was opened from the Bedrock /ra menu (NOT a typed /raid command).
     *  The menu path gets the Bedrock Raid Selector slider; the command path (even for a Bedrock
     *  player) behaves exactly like Java — the divergence is menu-vs-command, not client. */
    private final boolean fromMenu;
    private final Inventory inv;
    private final Map<Integer, UUID> ownerBySlot = new HashMap<>();

    public RaidGUI(HomeSystemPlugin plugin, UUID actorId, boolean asDev, boolean fromMenu) {
        this.plugin = plugin;
        this.actorId = actorId;
        this.asDev = asDev;
        this.fromMenu = fromMenu;
        this.inv = Bukkit.createInventory(this, SIZE, asDev ? TITLE_DEV : TITLE_NORMAL);
        rebuild();
    }

    @Override public Inventory getInventory() { return inv; }

    public UUID  getActorId() { return actorId; }
    public boolean isDevMode() { return asDev; }
    public boolean isFromMenu() { return fromMenu; }

    /**
     * Return the claim owner whose tile occupies the given raw slot, or null if that slot is
     * empty / outside the chest. Used by the click listener to look up the raid target without
     * trusting the displayed item.
     */
    public UUID getOwnerAt(int slot) { return ownerBySlot.get(slot); }

    /**
     * Repopulate the inventory from the current claim list. Called once at construction and
     * (later) by the visibility-tick if we want live-refreshing magma states. Drops every
     * previous slot before drawing so a recompute is safe to call on an open inventory.
     */
    public void rebuild() {
        inv.clear();
        ownerBySlot.clear();

        AllianceManager am = plugin.getAllianceManager();
        Alliance actorAlliance = am == null ? null : am.getOf(actorId);

        // Sort by owner display name so the layout is stable between opens.
        List<Claim> claims = new ArrayList<>(plugin.getClaimManager().all().values());
        claims.sort(Comparator.comparing(c -> plugin.getClaimManager().resolveName(c.getOwner())
                .toLowerCase(java.util.Locale.ROOT)));

        int slot = 0;
        for (Claim claim : claims) {
            if (slot >= SIZE) break;
            UUID ownerId = claim.getOwner();
            String ownerName = plugin.getClaimManager().resolveName(ownerId);
            ActiveRaid existingRaid = plugin.getRaidManager().getRaidOnZone(ownerId);
            boolean friendly = isFriendlyClaim(claim, actorAlliance);

            ItemStack tile;
            if (existingRaid != null) {
                tile = makeMagma(ownerName, existingRaid);
            } else if (friendly) {
                tile = makeFlower(ownerName, claim, actorAlliance);
            } else {
                tile = makeDirt(ownerName, claim);
            }
            inv.setItem(slot, tile);
            ownerBySlot.put(slot, ownerId);
            slot++;
        }
    }

    // -- friendliness check ---------------------------------------------------

    /**
     * "Friendly" = the actor cannot raid this claim. True if:
     * <ol>
     *   <li>The actor owns it, OR</li>
     *   <li>The actor is on the claim's friend list (added via {@code /hs add}), OR</li>
     *   <li>The actor and the claim owner share an alliance.</li>
     * </ol>
     * Note: this is from the ACTOR's perspective, not the caller's — for /raid dev it's the dev
     * UUID we're checking, which has its own (separate) friends + (lack of) alliance.
     */
    private boolean isFriendlyClaim(Claim claim, Alliance actorAlliance) {
        if (claim.getOwner().equals(actorId)) return true;
        if (claim.isMember(actorId)) return true;
        if (actorAlliance == null) return false;
        AllianceManager am = plugin.getAllianceManager();
        if (am == null) return false;
        Alliance ownerAlliance = am.getOf(claim.getOwner());
        return ownerAlliance != null && ownerAlliance == actorAlliance;
    }

    // -- tile builders --------------------------------------------------------

    private ItemStack makeDirt(String ownerName, Claim claim) {
        ItemStack it = new ItemStack(Material.DIRT);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GREEN + ownerName + ChatColor.GRAY + " — raidable");
        m.setLore(List.of(
                ChatColor.GRAY + "Size: " + ChatColor.YELLOW + claim.sizeX() + "×" + claim.sizeZ(),
                ChatColor.GRAY + "Turrets: " + ChatColor.YELLOW + claim.getTurrets().size()
                        + ChatColor.GRAY + "/" + Claim.MAX_TURRETS,
                "",
                ChatColor.GRAY + "Cost: " + ChatColor.AQUA + "32-64 Diamond"
                        + ChatColor.GRAY + " + " + ChatColor.AQUA + "32-64 Emerald"
                        + ChatColor.GRAY + " (1:1)",
                ChatColor.GRAY + "Takes the max possible up to 64.",
                "",
                ChatColor.GOLD + "Click to start a raid."
        ));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack makeFlower(String ownerName, Claim claim, Alliance actorAlliance) {
        ItemStack it = new ItemStack(Material.ALLIUM);
        ItemMeta m = it.getItemMeta();
        String reason;
        if (claim.getOwner().equals(actorId)) reason = "your own claim";
        else if (claim.getFriends().contains(actorId)) reason = "you are a friend";
        else reason = "alliance " + (actorAlliance == null ? "?" : actorAlliance.getName());
        m.setDisplayName(ChatColor.AQUA + ownerName + ChatColor.GRAY + " — friendly");
        m.setLore(List.of(
                ChatColor.GRAY + "Size: " + ChatColor.YELLOW + claim.sizeX() + "×" + claim.sizeZ(),
                ChatColor.GRAY + "Reason: " + ChatColor.AQUA + reason,
                "",
                ChatColor.DARK_GRAY + "(cannot be raided)"
        ));
        it.setItemMeta(m);
        return it;
    }

    private ItemStack makeMagma(String ownerName, ActiveRaid raid) {
        ItemStack it = new ItemStack(Material.MAGMA_BLOCK);
        ItemMeta m = it.getItemMeta();
        int total = raid.getTotalToSpawn();
        int spawned = raid.getSpawnedSoFar();
        int alive = raid.getAliveCount();
        int killed = Math.max(0, spawned - alive);
        m.setDisplayName(ChatColor.RED + ownerName + ChatColor.GRAY + " — under raid");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Attacker: " + ChatColor.RED + raid.getAttackerName());
        lore.add(ChatColor.GRAY + "Phase: " + ChatColor.YELLOW + raid.getPhase().displayName());
        lore.add(ChatColor.GRAY + "Spawned: " + ChatColor.YELLOW + spawned
                + ChatColor.GRAY + "/" + ChatColor.YELLOW + total);
        lore.add(ChatColor.GRAY + "Alive: " + ChatColor.YELLOW + alive
                + ChatColor.GRAY + "  Killed: " + ChatColor.YELLOW + killed);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "(already being raided)");
        m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    /**
     * Helper: open the GUI for a player. Centralised so callers don't need to call
     * {@code Player#openInventory} themselves and so we can attach event listeners later
     * without touching the call sites.
     */
    public static void open(HomeSystemPlugin plugin, Player viewer, UUID actorId, boolean asDev, boolean fromMenu) {
        RaidGUI gui = new RaidGUI(plugin, actorId, asDev, fromMenu);
        if (Collections.frequency(java.util.Arrays.asList(gui.inv.getStorageContents()), null)
                == gui.inv.getSize()) {
            // No claims at all — show a hint instead of an empty chest. Defensive: a single-
            // player dev server with no /hs claim yet would otherwise see an empty inventory
            // and not understand why.
            viewer.sendMessage(ChatColor.YELLOW + "No claims exist yet — nobody to raid.");
            return;
        }
        viewer.openInventory(gui.inv);
    }
}
