package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import com.raeyd.raidattack.raid.CustomRaider;
import com.raeyd.raidattack.raid.CustomRavager;
import com.raeyd.raidattack.raid.RaidEntityManager;
import com.raeyd.raidattack.turret.Turret;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Strict allowlist enforcement for Citizens NPCs. The plugin's model: a Citizens NPC
 * requires explicit permission to exist. Anything not on the allowlist is destroyed on
 * sight. This catches the failure modes that name-pattern matching missed:
 *
 * <ul>
 *   <li><b>Stale on restart.</b> Citizens persists its NPC registry across server restarts.
 *       Raid NPCs that were live before a crash come back as ghost entities the wrapper
 *       maps don't know about. Auditor wipes them; the spawn engine's recovery path then
 *       re-spawns the saved alive count fresh.</li>
 *   <li><b>Drift mid-session.</b> If a wrapper is somehow lost (Citizens reload, plugin
 *       bug, manual /reload) the underlying NPC entity can persist while our tracking
 *       map is empty. Auditor catches it on the next 10-second sweep.</li>
 *   <li><b>Hand-created NPCs.</b> An admin running {@code /npc create} for testing leaves
 *       a registry entry that nothing in our domain owns. Auditor destroys it.</li>
 * </ul>
 *
 * <p>Aggressive by design: this server runs only RaidAttack + Citizens, so we don't
 * need to coexist with other NPC plugins. If that ever changes, the allowed-set
 * computation would need to grow a "tagged for owner X" predicate.
 *
 * <p>Allowed set is recomputed on every audit pass (cheap — a few claims × a few
 * turrets + the raider wrapper maps). No persistent state held here; the auditor is
 * purely derivative of {@link ClaimManager} + {@link RaidEntityManager}.
 */
public final class CitizensAuditor {

    /** Sweep interval — 10 s. Frequent enough to catch drift quickly, cheap enough to ignore. */
    private static final long AUDIT_INTERVAL_TICKS = 200L;

    private final HomeSystemPlugin plugin;
    private BukkitTask task;

    public CitizensAuditor(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::auditQuiet, AUDIT_INTERVAL_TICKS, AUDIT_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    /**
     * Run one full audit pass. Computes the allowed set, destroys everything outside it.
     * Returns the count of NPCs destroyed.
     */
    public int audit() {
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) return 0;
        Set<Integer> allowed = computeAllowed();
        List<NPC> toKill = new ArrayList<>();
        try {
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (allowed.contains(npc.getId())) continue;
                toKill.add(npc);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Auditor: registry iteration failed: " + t.getMessage());
            return 0;
        }
        int destroyed = 0;
        for (NPC npc : toKill) {
            try {
                plugin.getLogger().info("Auditor: destroying untracked NPC #"
                        + npc.getId() + " (" + (npc.getName() != null ? npc.getName() : "unnamed") + ")");
                npc.destroy();
                destroyed++;
            } catch (Throwable ignored) {}
        }
        return destroyed;
    }

    /** Periodic-tick wrapper: log only when something was actually destroyed (most ticks are 0). */
    private void auditQuiet() {
        int n = audit();
        if (n > 0) {
            plugin.getLogger().info("Auditor: wiped " + n + " untracked NPC(s) this sweep.");
        }
    }

    /**
     * Build the set of NPC IDs the plugin currently considers legitimate. Anything outside
     * this is fair game for destruction.
     */
    private Set<Integer> computeAllowed() {
        Set<Integer> allowed = new HashSet<>();
        // Turret NPCs — each claim's deployed turret has an npcId referencing the Citizens
        // entity that renders the shulker body. Pending placements (npcId = -1) are skipped.
        if (plugin.getClaimManager() != null) {
            for (Claim c : plugin.getClaimManager().all().values()) {
                for (Turret t : c.getTurrets()) {
                    if (t.getNpcId() >= 0) allowed.add(t.getNpcId());
                }
            }
        }
        // Raid NPCs — wrappers in RaidEntityManager are the source of truth for "this NPC is
        // a current raider/ravager". A wrapper without a spawned NPC (npc == null) means the
        // wrapper is mid-lifecycle (spawn failed, despawn in progress); don't allowlist it.
        if (plugin.getRaidEntities() != null) {
            for (CustomRaider r : plugin.getRaidEntities().allRaiders()) {
                NPC npc = r.getNpc();
                if (npc != null) allowed.add(npc.getId());
            }
            for (CustomRavager r : plugin.getRaidEntities().allRavagers()) {
                NPC npc = r.getNpc();
                if (npc != null) allowed.add(npc.getId());
            }
        }
        return allowed;
    }
}
