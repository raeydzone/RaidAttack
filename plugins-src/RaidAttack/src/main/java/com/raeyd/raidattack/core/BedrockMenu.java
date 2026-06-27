package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.quest.QuestManager;
import com.raeyd.raidattack.turret.Turret;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.form.util.FormBuilder;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Native Bedrock UI ("Cumulus" forms via Floodgate) for the Raid Attack feature set — Bedrock-only,
 * so Java clients (who never receive a form) are unaffected and keep the chat/inventory flow.
 *
 * <p>Two roles: a <b>launcher</b> (action buttons run the matching command as the player, so all
 * existing permission/eligibility/validation is reused — required args are always collected first, so
 * commands never error on a missing arg) and a <b>viewer</b> (read-only screens — claim info, turret
 * status, alliance info/list, pending requests, quests — fetch from the managers and render INSIDE the
 * form instead of chat). After any action the relevant menu re-opens; closing the form (X) stops.
 *
 * <p>All data reads + sends run on the main thread (forms are dispatched via {@link #onMain}); the
 * form callbacks themselves fire on Floodgate's thread, so we never touch managers off-thread.
 * Colour theme: Raids §c, Home §a, Alliance §e, Utility §f. The admin {@code rights} command has no UI.
 */
public final class BedrockMenu {

    private final HomeSystemPlugin plugin;
    /** When set, raids in this menu session act "as the dev" (DEV_UUID actor, event locks bypassed).
     *  Held on the instance — the form callbacks all capture this same BedrockMenu, so it persists
     *  through the navigation. Set only after the owner + dev_mode guard in RaidAttackCommand. */
    private boolean asDev = false;

    public BedrockMenu(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBedrock(Player p) {
        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- "never close the menu" return-after-chest-GUI plumbing (raid loot inv) ----------
    /** Players who opened a raid chest GUI from the menu — value = was the session in dev mode.
     *  When that GUI closes, {@link #reopenRaidsIfPending} re-opens the Raids screen so the menu
     *  is never abandoned. Static because the close fires far from any menu instance. */
    private static final Map<UUID, Boolean> PENDING_RAIDS_RETURN = new ConcurrentHashMap<>();

    /** Mark that this player should be returned to the Raids screen when their raid chest GUI closes. */
    public static void markRaidReturn(UUID id, boolean asDev) { PENDING_RAIDS_RETURN.put(id, asDev); }

    /** If a return is pending for {@code p}, re-open the Raids screen next tick (after the GUI closes). */
    public static void reopenRaidsIfPending(HomeSystemPlugin plugin, Player p) {
        Boolean asDev = PENDING_RAIDS_RETURN.remove(p.getUniqueId());
        if (asDev == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) new BedrockMenu(plugin).openRaids(p, asDev);
        });
    }

    /** Public entry to land directly on the Raids screen (used by the return-after-close flow). */
    public void openRaids(Player p, boolean asDev) { this.asDev = asDev; raids(p); }

    // ---- main menu -----------------------------------------------------------------------
    public void open(Player p) { open(p, false); }

    public void open(Player p, boolean asDev) {
        this.asDev = asDev;
        if (!isBedrock(p)) {
            p.sendMessage(Component.text("The Raid Attack menu is a Bedrock-only UI — on Java, just use the commands directly.",
                    NamedTextColor.YELLOW));
            return;
        }
        // The logo banner (from the resource pack) is the header now, so no "RAID ATTACK" title text,
        // no "choose a section", no "welcome" line (which pushed height into a scrollbar). The thin
        // title bar just carries the version.
        // Title shows in Mojang's GUI title bar (like the sub-menus do). The pack detects the main
        // menu by the words "Main Menu" in this title. Content is empty → nothing between logo + boxes.
        onMain(() -> send(p, SimpleForm.builder()
                .title(asDev ? "Raid Attack Main Menu §4[DEV]" : "Raid Attack Main Menu")
                .content("")
                .button("§2§lHOME PROTECTION\n§r§8› claim, turrets, friends", FormImage.Type.PATH, "textures/blocks/grass_side_carried")
                .button("§6§lALLIANCE SYSTEM\n§r§8› teams & requests", FormImage.Type.PATH, "textures/items/gold_ingot")
                .button("§4§lRAIDS\n§r§8› attack & loot", FormImage.Type.PATH, "textures/items/diamond_sword")
                .button("§1§lUTILITY\n§r§8› extras", FormImage.Type.PATH, "textures/items/compass_item")
                .validResultHandler(r -> {
                    switch (r.clickedButtonId()) {
                        case 0 -> home(p);
                        case 1 -> alliance(p);
                        case 2 -> raids(p);
                        case 3 -> utility(p);
                        default -> { }
                    }
                })));
    }

    // ---- Raids (§c) ----------------------------------------------------------------------
    private void raids(Player p) {
        onMain(() -> send(p, SimpleForm.builder()
                .title("§c§lRAIDS")
                .content("§7Attack other claims and collect your loot.")
                .button("§4⚔ Raid List\n§r§8› choose a target", FormImage.Type.PATH, "textures/items/diamond_sword")
                .button("§4🛍 My Raid Loot\n§r§8› collect your stash", FormImage.Type.PATH, "textures/blocks/chest_front")
                .button("§8« Back")
                .validResultHandler(r -> {
                    switch (r.clickedButtonId()) {
                        // In dev mode raids act as the dev actor (mirrors /raid dev). Flag this open as
                        // menu-originated so the target click opens the Raid Selector (typed /raid won't).
                        case 0 -> { com.raeyd.raidattack.raid.RaidCommand.markMenuOpen(p.getUniqueId());
                                    runGui(p, asDev ? "raid dev" : "raid"); }
                        // Mark the loot view so closing it returns here (never abandon the menu).
                        case 1 -> { markRaidReturn(p.getUniqueId(), asDev); runGui(p, asDev ? "raid dev inv" : "raid inv"); }
                        case 2 -> open(p, asDev);
                        default -> { }
                    }
                })));
    }

    // ---- Home Protection (§a) ------------------------------------------------------------
    private void home(Player p) {
        onMain(() -> send(p, SimpleForm.builder()
                .title("§a§lHOME PROTECTION")
                .content("§7Claim land, defend it, and manage friends.")
                .button("§2ℹ Claim\n§r§8› info, border, unclaim", FormImage.Type.PATH, "textures/items/map_filled")
                .button("§2🔫 Turrets\n§r§8› status & manage", FormImage.Type.PATH, "textures/blocks/dispenser_front_horizontal")
                .button("§2🪧 Claim Land\n§r§8› pick a size", FormImage.Type.PATH, "textures/blocks/grass_side_carried")
                .button("§2🏠 Teleport Home", FormImage.Type.PATH, "textures/items/ender_pearl")
                .button("§2👥 Friends\n§r§8› add / remove", FormImage.Type.PATH, "textures/items/name_tag")
                .button("§8« Back")
                .validResultHandler(r -> {
                    switch (r.clickedButtonId()) {
                        case 0 -> claimInfo(p);
                        case 1 -> turrets(p);
                        case 2 -> claimLand(p);
                        case 3 -> action(p, "homesystem teleport", null);   // teleport channel; no re-open
                        case 4 -> friends(p);
                        case 5 -> open(p);
                        default -> { }
                    }
                })));
    }

    /** READ + manage: the player's claim details, plus border/unclaim actions (moved here to keep
     *  the Home menu short). With no claim, offers Claim Land instead. */
    private void claimInfo(Player p) {
        onMain(() -> {
            Claim c = plugin.getClaimManager().getClaimOf(p.getUniqueId());
            if (c == null) {
                send(p, SimpleForm.builder().title("§a§lYOUR CLAIM")
                        .content("§7You don't have a claim yet.\n§7Use §aClaim Land§7 to make one.")
                        .button("§2🪧 Claim Land\n§r§8› pick a size", FormImage.Type.PATH, "textures/blocks/grass_side_carried")
                        .button("§8« Back")
                        .validResultHandler(r -> { if (r.clickedButtonId() == 0) claimLand(p); else home(p); }));
                return;
            }
            StringBuilder f = new StringBuilder();
            List<UUID> friends = new ArrayList<>(c.getFriends());
            if (friends.isEmpty()) f.append("§8(none)");
            else for (UUID u : friends) f.append("\n  §7• §f").append(plugin.getClaimManager().resolveName(u));
            String content = "§7Size: §f" + c.sizeX() + "§7×§f" + c.sizeZ()
                    + "\n§7Corner A: §f(" + c.getMinX() + ", " + c.getMinZ() + ")"
                    + "\n§7Corner B: §f(" + c.getMaxX() + ", " + c.getMaxZ() + ")"
                    + "\n§7Turrets: §f" + c.countDeployed() + "§7/§f" + Claim.MAX_TURRETS
                    + "\n§7Attacks mobs: " + (c.attacksHostileMobs() ? "§aON" : "§cOFF")
                    + "\n§7Friends: " + f;
            send(p, SimpleForm.builder().title("§a§lYOUR CLAIM").content(content)
                    .button("§2▦ Show Border", FormImage.Type.PATH, "textures/blocks/glowstone")
                    .button("§4✖ Unclaim Land\n§r§8› delete this claim", FormImage.Type.PATH, "textures/blocks/tnt_side")
                    .button("§8« Back")
                    .validResultHandler(r -> {
                        switch (r.clickedButtonId()) {
                            case 0 -> action(p, "homesystem show border", () -> claimInfo(p));
                            case 1 -> confirm(p, "§c§lUNCLAIM?", "§7This deletes your claim and all turrets.\n§7This cannot be undone.",
                                    () -> action(p, "homesystem unclaim", () -> home(p)), () -> claimInfo(p));
                            default -> home(p);
                        }
                    }));
        });
    }

    /** READ + manage: render all 4 turret slots, then offer deploy/upgrade/remove/attack-mobs. */
    private void turrets(Player p) {
        onMain(() -> {
            Claim c = plugin.getClaimManager().getClaimOf(p.getUniqueId());
            StringBuilder sb = new StringBuilder();
            if (c == null) {
                sb.append("§7You don't have a claim yet.");
            } else {
                long now = System.currentTimeMillis();
                for (int slot = 0; slot < Claim.MAX_TURRETS; slot++) {
                    int level = c.getSlotLevel(slot);
                    Turret t = c.getSlotTurret(slot);
                    sb.append("§7Slot §f").append(slot + 1).append(" §8(L").append(level).append("§8) ");
                    if (t == null) {
                        sb.append(c.isSlotInDowntime(slot)
                                ? "§8rebuilding " + secsLeft(c.getSlotRespawnAt(slot), now) + "s"
                                : "§8empty");
                    } else if (t.isDestroyed()) {
                        sb.append("§cdestroyed §8(").append(secsLeft(t.getRespawnAtMillis(), now)).append("s)");
                    } else {
                        int hp = t.getStructureHp(), max = Turret.maxHpForLevel(level);
                        String col = hp < 50 ? "§c" : hp < 100 ? "§6" : "§a";
                        sb.append(col).append(hp).append("§7/§f").append(max).append(" HP");
                    }
                    sb.append('\n');
                }
                sb.append("§7Attacks mobs: ").append(c.attacksHostileMobs() ? "§aON" : "§cOFF");
            }
            boolean attackMobs = c != null && c.attacksHostileMobs();
            send(p, SimpleForm.builder().title("§a§lTURRETS").content(sb.toString())
                    .button("§2➕ Deploy Turret\n§r§8› at your position", FormImage.Type.PATH, "textures/blocks/dispenser_front_horizontal")
                    .button("§2⬆ Upgrade Turret\n§r§8› spend resources", FormImage.Type.PATH, "textures/items/diamond")
                    .button("§4➖ Remove Turret\n§r§8› pick a slot", FormImage.Type.PATH, "textures/blocks/tnt_side")
                    .button(attackMobs
                            ? "§2🐗 Attack Mobs: ON\n§r§8› tap to turn off"
                            : "§8🐗 Attack Mobs: OFF\n§r§8› tap to turn on", FormImage.Type.PATH, "textures/items/rotten_flesh")
                    .button("§8« Back")
                    .validResultHandler(r -> {
                        switch (r.clickedButtonId()) {
                            case 0 -> action(p, "homesystem turret deploy", () -> turrets(p));
                            case 1 -> runGui(p, "homesystem turret upgrade");       // opens GUI
                            case 2 -> turretRemove(p);
                            case 3 -> action(p, "homesystem turret attackmobs " + (attackMobs ? "off" : "on"), () -> turrets(p));
                            case 4 -> home(p);
                            default -> { }
                        }
                    }));
        });
    }

    /** Slot picker for /hs turret remove <#> — only occupied slots, so the command never errors. */
    private void turretRemove(Player p) {
        onMain(() -> {
            Claim c = plugin.getClaimManager().getClaimOf(p.getUniqueId());
            List<Integer> occupied = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            if (c != null) {
                for (int slot = 0; slot < Claim.MAX_TURRETS; slot++) {
                    if (c.getSlotTurret(slot) != null) { occupied.add(slot + 1); labels.add("Slot " + (slot + 1)); }
                }
            }
            if (occupied.isEmpty()) {
                send(p, SimpleForm.builder().title("§c§lREMOVE TURRET").content("§7You have no deployed turrets to remove.")
                        .button("§8« Back").validResultHandler(r -> turrets(p)));
                return;
            }
            send(p, CustomForm.builder().title("§c§lREMOVE TURRET")
                    .dropdown("§7Which turret?", labels)                      // component 0
                    .closedResultHandler(() -> turrets(p))
                    .validResultHandler(r -> action(p, "homesystem turret remove " + occupied.get(r.asDropdown(0)), () -> turrets(p))));
        });
    }

    /** Claim size as a slider across the full valid range (80–200 blocks/side), so any size is
     *  reachable — not just a handful of dropdown presets. Rounded to the nearest 5. */
    private void claimLand(Player p) {
        onMain(() -> send(p, CustomForm.builder().title("§a§lCLAIM LAND")
                .label("§7Stand where you want a corner, then pick a size.\n§7Your claim is a square, this many blocks per side.")
                .slider("§7Size (blocks per side)", 80, 200, 5, 100)           // component 1
                .closedResultHandler(() -> home(p))
                .validResultHandler(r -> {
                    int size = Math.round(r.asSlider(1) / 5f) * 5;
                    action(p, "homesystem claim " + size, () -> home(p));
                })));
    }

    private void friends(Player p) {
        List<String> actions = List.of("Add friend", "Remove friend");
        onMain(() -> send(p, CustomForm.builder().title("§a§lMANAGE FRIENDS")
                .dropdown("§7Action", actions)                                // component 0
                .input("§7Player name §8(or 'self_alliance')", "name")        // component 1
                .closedResultHandler(() -> home(p))
                .validResultHandler(r -> {
                    String name = r.asInput(1);
                    if (name == null || name.isBlank()) { home(p); return; }
                    action(p, "homesystem " + (r.asDropdown(0) == 0 ? "add " : "remove ") + name.trim(), () -> home(p));
                })));
    }

    // ---- Alliance (§e) -------------------------------------------------------------------
    private void alliance(Player p) {
        onMain(() -> send(p, SimpleForm.builder()
                .title("§e§lALLIANCE SYSTEM")
                .content("§7Form a team, manage members, and more.")
                .button("§6ℹ My Alliance\n§r§8› info & manage", FormImage.Type.PATH, "textures/items/gold_ingot")
                .button("§6📜 All Alliances\n§r§8› browse teams", FormImage.Type.PATH, "textures/items/book_normal")
                .button("§6📨 Pending Requests", FormImage.Type.PATH, "textures/items/paper")
                .button("§6➕ Create\n§r§8› name + color", FormImage.Type.PATH, "textures/items/nether_star")
                .button("§6🚪 Join\n§r§8› request to join", FormImage.Type.PATH, "textures/items/ender_pearl")
                .button("§8« Back")
                .validResultHandler(r -> {
                    switch (r.clickedButtonId()) {
                        case 0 -> allianceInfo(p);
                        case 1 -> allianceList(p);
                        case 2 -> pending(p);
                        case 3 -> allianceCreate(p);
                        case 4 -> input(p, "§e§lJOIN ALLIANCE", "§7Alliance name", "name", "alliance join ", () -> alliance(p));
                        case 5 -> open(p);
                        default -> { }
                    }
                })));
    }

    /** READ + manage: alliance info, plus color/rename/leave (moved here to keep the Alliance menu
     *  short). With no alliance, offers Create / Join instead. */
    private void allianceInfo(Player p) {
        onMain(() -> {
            AllianceManager am = plugin.getAllianceManager();
            Alliance a = am == null ? null : am.getOf(p.getUniqueId());
            if (a == null) {
                send(p, SimpleForm.builder().title("§e§lMY ALLIANCE")
                        .content("§7You're not in an alliance.\n§7Use §eCreate§7 or §eJoin§7 to get started.")
                        .button("§6➕ Create\n§r§8› name + color", FormImage.Type.PATH, "textures/items/nether_star")
                        .button("§6🚪 Join\n§r§8› request to join", FormImage.Type.PATH, "textures/items/ender_pearl")
                        .button("§8« Back")
                        .validResultHandler(r -> {
                            switch (r.clickedButtonId()) {
                                case 0 -> allianceCreate(p);
                                case 1 -> input(p, "§e§lJOIN ALLIANCE", "§7Alliance name", "name", "alliance join ", () -> alliance(p));
                                default -> alliance(p);
                            }
                        }));
                return;
            }
            StringBuilder m = new StringBuilder();
            for (UUID u : a.getMembers()) {
                m.append("\n  §7• §f").append(plugin.getClaimManager().resolveName(u));
                if (a.isLeader(u)) m.append(" §8(leader)");
            }
            String content = "§7Name: §e" + a.getName()
                    + "\n§7Leader: §f" + plugin.getClaimManager().resolveName(a.getLeader())
                    + "\n§7Members (§f" + a.getMembers().size() + "§7):" + m;
            send(p, SimpleForm.builder().title("§e§lMY ALLIANCE").content(content)
                    .button("§6🎨 Set Color", FormImage.Type.PATH, "textures/items/dye_powder_blue")
                    .button("§6✎ Rename", FormImage.Type.PATH, "textures/items/name_tag")
                    .button("§4🚶 Leave Alliance", FormImage.Type.PATH, "textures/blocks/tnt_side")
                    .button("§8« Back")
                    .validResultHandler(r -> {
                        switch (r.clickedButtonId()) {
                            case 0 -> allianceColor(p);
                            case 1 -> input(p, "§e§lRENAME ALLIANCE", "§7New name §8(≤8, letters/digits)", "name", "alliance change_name ", () -> allianceInfo(p));
                            case 2 -> confirm(p, "§c§lLEAVE?", "§7Leave your alliance? (Leaders must disband instead.)",
                                    () -> action(p, "alliance leave", () -> alliance(p)), () -> allianceInfo(p));
                            default -> alliance(p);
                        }
                    }));
        });
    }

    private void allianceList(Player p) {
        onMain(() -> {
            AllianceManager am = plugin.getAllianceManager();
            StringBuilder sb = new StringBuilder();
            if (am == null || am.all().isEmpty()) {
                sb.append("§7No alliances exist yet.");
            } else {
                for (Alliance a : am.all()) {
                    sb.append("§7• §e").append(a.getName()).append(" §8— §f").append(a.getMembers().size()).append(" §7member(s)\n");
                }
            }
            send(p, SimpleForm.builder().title("§e§lALL ALLIANCES").content(sb.toString())
                    .button("§8« Back").validResultHandler(r -> alliance(p)));
        });
    }

    /** Pending join requests. If the viewer is the leader, each applicant is a button → accept/reject. */
    private void pending(Player p) {
        onMain(() -> {
            AllianceManager am = plugin.getAllianceManager();
            Alliance a = am == null ? null : am.getOf(p.getUniqueId());
            if (a == null) {
                send(p, SimpleForm.builder().title("§e§lPENDING").content("§7You're not in an alliance.")
                        .button("§8« Back").validResultHandler(r -> alliance(p)));
                return;
            }
            List<UUID> reqs = new ArrayList<>(a.getPendingRequests());
            boolean leader = a.isLeader(p.getUniqueId());
            if (reqs.isEmpty()) {
                send(p, SimpleForm.builder().title("§e§lPENDING").content("§7No pending join requests.")
                        .button("§8« Back").validResultHandler(r -> alliance(p)));
                return;
            }
            SimpleForm.Builder b = SimpleForm.builder().title("§e§lPENDING REQUESTS")
                    .content(leader ? "§7Tap an applicant to accept or reject." : "§7Only the leader can accept/reject.");
            List<String> names = new ArrayList<>();
            for (UUID u : reqs) { String n = plugin.getClaimManager().resolveName(u); names.add(n); b.button("§0§l" + n); }
            b.button("§8« Back");
            b.validResultHandler(r -> {
                int i = r.clickedButtonId();
                if (i < 0 || i >= names.size()) { alliance(p); return; }       // Back
                String who = names.get(i);
                if (!leader) { pending(p); return; }
                confirm2(p, "§e" + who, "§7Accept or reject §f" + who + "§7's request?",
                        "§2✔ Accept", () -> action(p, "alliance accept " + who, () -> pending(p)),
                        "§4✖ Reject", () -> action(p, "alliance reject " + who, () -> pending(p)),
                        () -> pending(p));
            });
            send(p, b);
        });
    }

    private void allianceCreate(Player p) {
        List<String> colors = AllianceManager.colorNames();
        onMain(() -> send(p, CustomForm.builder().title("§e§lCREATE ALLIANCE")
                .input("§7Alliance name §8(≤8, letters/digits)", "name")     // component 0
                .dropdown("§7Color", colors)                                  // component 1
                .closedResultHandler(() -> alliance(p))
                .validResultHandler(r -> {
                    String name = r.asInput(0);
                    if (name == null || name.isBlank()) { alliance(p); return; }
                    action(p, "alliance create " + name.trim() + " " + colors.get(r.asDropdown(1)), () -> alliance(p));
                })));
    }

    private void allianceColor(Player p) {
        List<String> colors = AllianceManager.colorNames();
        onMain(() -> send(p, CustomForm.builder().title("§e§lALLIANCE COLOR")
                .dropdown("§7New color", colors)                              // component 0
                .closedResultHandler(() -> allianceInfo(p))
                .validResultHandler(r -> action(p, "alliance color " + colors.get(r.asDropdown(0)), () -> allianceInfo(p)))));
    }

    // ---- Utility (§f) — admin `rights` intentionally absent ------------------------------
    private void utility(Player p) {
        onMain(() -> {
            boolean armorOn = plugin.getArmorDurability() != null && plugin.getArmorDurability().isIndicatorOn(p.getUniqueId());
            send(p, SimpleForm.builder()
                    .title("§f§lUTILITY")
                    .content("§7Armor durability indicator is currently: " + (armorOn ? "§aON" : "§cOFF"))
                    .button("§1🛡 Armor Indicator: " + (armorOn ? "§4Turn OFF" : "§2Turn ON"), FormImage.Type.PATH, "textures/items/iron_chestplate")
                    .button("§1📜 Quests\n§r§8› view progress", FormImage.Type.PATH, "textures/items/book_written")
                    .button("§8« Back")
                    .validResultHandler(r -> {
                        switch (r.clickedButtonId()) {
                            case 0 -> action(p, "ra armor " + (armorOn ? "off" : "on"), () -> utility(p));
                            case 1 -> quests(p);
                            case 2 -> open(p);
                            default -> { }
                        }
                    }));
        });
    }

    private void quests(Player p) {
        onMain(() -> {
            QuestManager qm = plugin.getQuests();
            String content = qm == null ? "§7Quests are unavailable." : String.join("\n", qm.renderForm(p.getUniqueId()));
            send(p, SimpleForm.builder().title("§f§lQUESTS").content(content)
                    .button("§8« Back").validResultHandler(r -> utility(p)));
        });
    }

    // ---- shared helpers ------------------------------------------------------------------

    /** A single free-text input that runs {@code cmdPrefix + value}, then re-opens {@code back}.
     *  Custom forms can't carry a Back button, so closing (X) returns to the parent (never strands). */
    private void input(Player p, String title, String label, String placeholder, String cmdPrefix, Runnable back) {
        onMain(() -> send(p, CustomForm.builder().title(title)
                .input(label, placeholder)                                    // component 0
                .closedResultHandler(back)
                .validResultHandler(r -> {
                    String v = r.asInput(0);
                    if (v == null || v.isBlank()) { back.run(); return; }
                    action(p, cmdPrefix + v.trim(), back);
                })));
    }

    /** Yes/Cancel confirmation (button 0 = confirm). Closing (X) cancels back to the parent. */
    private void confirm(Player p, String title, String content, Runnable onYes, Runnable onCancel) {
        onMain(() -> send(p, SimpleForm.builder().title(title).content(content)
                .button("§4✔ Yes").button("§8✖ Cancel")
                .closedResultHandler(onCancel)
                .validResultHandler(r -> { if (r.clickedButtonId() == 0) onYes.run(); else onCancel.run(); })));
    }

    /** Two-action choice (e.g. Accept / Reject), button 0 = first action, 1 = second, 2 = back. */
    private void confirm2(Player p, String title, String content, String b0, Runnable a0, String b1, Runnable a1, Runnable back) {
        onMain(() -> send(p, SimpleForm.builder().title(title).content(content)
                .button(b0).button(b1).button("§8« Back")
                .closedResultHandler(back)
                .validResultHandler(r -> {
                    switch (r.clickedButtonId()) { case 0 -> a0.run(); case 1 -> a1.run(); default -> back.run(); }
                })));
    }

    /** Run a command as the player (reuses all command logic/restrictions), then re-open {@code reopen}. */
    private void action(Player p, String command, Runnable reopen) {
        onMain(() -> {
            if (!p.isOnline()) return;
            p.performCommand(command);
            if (reopen != null) reopen.run();
        });
    }

    /** Run a command that opens its own chest GUI — do NOT re-open a form over it. */
    private void runGui(Player p, String command) {
        onMain(() -> { if (p.isOnline()) p.performCommand(command); });
    }

    private void send(Player p, FormBuilder<?, ?, ?> form) {
        try {
            FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
        } catch (Throwable t) {
            plugin.getLogger().warning("BedrockMenu: failed to send form to " + p.getName() + ": " + t.getMessage());
        }
    }

    /** Run on the main thread (form callbacks fire on Floodgate's thread; managers are main-thread). */
    private void onMain(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }

    private static long secsLeft(long deadlineMillis, long now) {
        return Math.max(0, (deadlineMillis - now + 999) / 1000);
    }
}
