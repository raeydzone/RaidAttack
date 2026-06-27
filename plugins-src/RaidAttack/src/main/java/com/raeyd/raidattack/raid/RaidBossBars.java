package com.raeyd.raidattack.raid;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.alliance.Alliance;
import com.raeyd.raidattack.alliance.AllianceManager;
import com.raeyd.raidattack.claim.Claim;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * Per-raid red boss bar lifecycle, rewritten on the Adventure API.
 *
 * <p>The Bukkit {@code org.bukkit.boss.BossBar} interface has been progressively deprecated
 * in newer Paper builds and didn't render at all on Paper 26.1 in our tests. Adventure's
 * {@link BossBar} is the modern path: it's a value object you can show/hide on any
 * {@link net.kyori.adventure.audience.Audience} (which {@link Player} implements), and
 * updates to the bar's name/progress fan out automatically to every viewer.
 *
 * <p>Visibility rules unchanged from the previous implementation:
 * <ul>
 *   <li>Zone owner sees the bar.</li>
 *   <li>Friends on the owner's {@code /hs} claim see the bar.</li>
 *   <li>Members of the owner's alliance see the bar.</li>
 *   <li>Anyone currently inside the targeted zone sees the bar (Citizens NPCs filtered).</li>
 *   <li>The attacker does NOT see the bar — UNLESS they are standing inside the targeted
 *       zone, in which case the "anyone in zone" rule re-includes them.</li>
 * </ul>
 *
 * <p>A player who qualifies under multiple raids simultaneously sees one bar per raid.
 * That's the user's "if user has a base attacked by him, and it shows, and he stands in
 * another base which also gets attacked u can show both" edge case.
 */
public final class RaidBossBars implements Listener {

    /** How often to recompute who-sees-what + refresh bar text/progress (ticks). */
    private static final long VISIBILITY_TICK_INTERVAL = 10L;

    private final HomeSystemPlugin plugin;
    /** raidId → bar value object. */
    private final Map<UUID, BossBar> barByRaid = new LinkedHashMap<>();
    /** raidId → set of player UUIDs currently shown the bar. Cached so the tick's diff-add
     *  doesn't re-send packets to viewers who already see it. */
    private final Map<UUID, Set<UUID>> viewersByRaid = new HashMap<>();
    private BukkitTask tickTask;

    public RaidBossBars(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (tickTask != null) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, VISIBILITY_TICK_INTERVAL, VISIBILITY_TICK_INTERVAL);
    }

    public void stop() {
        if (tickTask != null) { tickTask.cancel(); tickTask = null; }
        // Hide every bar from every viewer before clearing state.
        for (Map.Entry<UUID, BossBar> e : barByRaid.entrySet()) {
            BossBar bar = e.getValue();
            Set<UUID> viewers = viewersByRaid.get(e.getKey());
            if (viewers == null) continue;
            for (UUID id : viewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
            }
        }
        barByRaid.clear();
        viewersByRaid.clear();
    }

    /**
     * Create the bar for a freshly-started raid and attach all initial viewers immediately.
     * Idempotent — calling twice for the same raid id refreshes the title/progress without
     * re-creating the bar.
     */
    public void onRaidStarted(ActiveRaid raid) {
        BossBar bar = barByRaid.get(raid.getRaidId());
        if (bar == null) {
            bar = BossBar.bossBar(
                    buildTitle(raid),
                    clampProgress(raid.progressFractionRemaining()),
                    BossBar.Color.RED,
                    BossBar.Overlay.PROGRESS);
            barByRaid.put(raid.getRaidId(), bar);
            viewersByRaid.put(raid.getRaidId(), new HashSet<>());
        } else {
            bar.name(buildTitle(raid));
            bar.progress(clampProgress(raid.progressFractionRemaining()));
        }
        // Immediately attach initial viewers — don't wait up to 0.5 s for the tick to add them.
        Set<UUID> tracked = viewersByRaid.computeIfAbsent(raid.getRaidId(), k -> new HashSet<>());
        for (UUID id : computeViewers(raid)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            p.showBossBar(bar);
            tracked.add(id);
        }
    }

    /** Tear down the bar for a finished raid. Idempotent. */
    public void onRaidEnded(UUID raidId) {
        BossBar bar = barByRaid.remove(raidId);
        Set<UUID> viewers = viewersByRaid.remove(raidId);
        if (bar == null || viewers == null) return;
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
    }

    // -- visibility tick ------------------------------------------------------

    private void tick() {
        if (barByRaid.isEmpty()) return;
        // Snapshot keys so we can mutate barByRaid inside the loop (stale-cleanup path).
        for (UUID raidId : new HashSet<>(barByRaid.keySet())) {
            BossBar bar = barByRaid.get(raidId);
            if (bar == null) continue;
            ActiveRaid raid = plugin.getRaidManager().getRaid(raidId);
            if (raid == null) {
                // Stale entry — somebody pruned the raid without telling us. Detach + drop.
                Set<UUID> stale = viewersByRaid.remove(raidId);
                if (stale != null) {
                    for (UUID id : stale) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null) p.hideBossBar(bar);
                    }
                }
                barByRaid.remove(raidId);
                continue;
            }
            // Refresh title + progress every tick so numbers stay live without every spawn /
            // death site having to push updates.
            bar.name(buildTitle(raid));
            bar.progress(clampProgress(raid.progressFractionRemaining()));

            Set<UUID> shouldSee = computeViewers(raid);
            Set<UUID> tracked = viewersByRaid.computeIfAbsent(raidId, k -> new HashSet<>());

            // Hide for viewers who no longer qualify.
            for (UUID id : new HashSet<>(tracked)) {
                if (!shouldSee.contains(id)) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.hideBossBar(bar);
                    tracked.remove(id);
                }
            }
            // Show for new qualifiers.
            for (UUID id : shouldSee) {
                if (tracked.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p == null) continue;
                p.showBossBar(bar);
                tracked.add(id);
            }
        }
    }

    /**
     * Compute the set of online-player UUIDs who should currently see {@code raid}'s bar.
     * Honours every rule from the class-level Javadoc, including the attacker-excluded rule
     * and the Citizens-NPC filter on the "anyone in zone" check.
     */
    private Set<UUID> computeViewers(ActiveRaid raid) {
        Set<UUID> out = new HashSet<>();
        UUID ownerId = raid.getZoneOwnerId();
        UUID attackerId = raid.getAttackerId();

        // 1. Owner.
        out.add(ownerId);
        // 2. Friends on owner's claim.
        Claim ownerClaim = plugin.getClaimManager().getClaimOf(ownerId);
        if (ownerClaim != null) out.addAll(ownerClaim.getFriends());
        // 3. Owner's alliance members.
        AllianceManager am = plugin.getAllianceManager();
        if (am != null) {
            Alliance ownerAlliance = am.getOf(ownerId);
            if (ownerAlliance != null) out.addAll(ownerAlliance.getMembers());
        }
        // 4. The attacker does NOT see their own raid's bar by default — remove them HERE, before
        // the in-zone pass below. That ordering matters: it lets rule 5 re-add the attacker if
        // they're physically standing inside the targeted base (so they can watch progress like
        // anyone else in there). An attacker who is NOT in the zone stays excluded.
        out.remove(attackerId);
        // 5. Anyone currently inside the targeted claim — including the attacker if they're in
        // there. Filter out Citizens NPCs (raider player-type entities appear in
        // world.getPlayers() but adding their UUIDs is meaningless — there's no client to render
        // the bar for).
        if (ownerClaim != null) {
            World world = Bukkit.getWorld(ownerClaim.getWorldId());
            if (world != null) {
                boolean citizens = Bukkit.getPluginManager().getPlugin("Citizens") != null;
                for (Player p : world.getPlayers()) {
                    if (citizens && net.citizensnpcs.api.CitizensAPI.getNPCRegistry().isNPC(p)) continue;
                    if (ownerClaim.contains(p.getLocation())) out.add(p.getUniqueId());
                }
            }
        }
        // Filter offline players.
        out.removeIf(id -> Bukkit.getPlayer(id) == null);
        return out;
    }

    /**
     * Boss bar title — minimal per spec: "Raid by &lt;attackerName&gt;" and nothing else.
     * Phase / countdown / alive / total counts are not shown here. The progress bar itself
     * conveys overall progress; the per-raid detail lives in the /raid GUI lore.
     */
    private Component buildTitle(ActiveRaid raid) {
        return Component.text("Raid by ").color(NamedTextColor.RED)
                .append(Component.text(raid.getAttackerName()).color(NamedTextColor.YELLOW));
    }

    private static float clampProgress(double v) {
        if (Double.isNaN(v)) return 0.0f;
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * Drop the quitting player from every raid's tracked-viewers set. Without this, the next
     * visibility tick sees the UUID still tracked, decides "they already see it", and skips the
     * {@code showBossBar} call — so the player rejoins and the bar never reappears. Clearing the
     * tracked entry lets the next tick treat them as a fresh viewer and re-send the show packet.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        for (Set<UUID> tracked : viewersByRaid.values()) {
            tracked.remove(id);
        }
    }
}
