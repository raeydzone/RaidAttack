package com.raeyd.raidattack.turret;

import com.raeyd.raidattack.HomeSystemPlugin;
import com.raeyd.raidattack.claim.Claim;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Triggers immediate turret validation when a chunk containing one of our turrets loads.
 * Replaces the manual {@code /HomeSystem dev fixall} workflow — the validator's periodic 5 s
 * tick previously skipped unloaded chunks, meaning a player walking up to a long-dormant
 * turret could see a missing NPC for up to 5 s before validation noticed. Now the moment the
 * chunk loads we kick a one-off check.
 */
public final class TurretChunkListener implements Listener {

    private final HomeSystemPlugin plugin;

    public TurretChunkListener(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk chunk = e.getChunk();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        java.util.UUID worldId = chunk.getWorld().getUID();

        // Find any turret whose anchor is in this chunk and validate it.
        for (Claim claim : plugin.getClaimManager().all().values()) {
            if (!claim.getWorldId().equals(worldId)) continue;
            for (Turret t : claim.getTurrets()) {
                if ((t.getX() >> 4) == cx && (t.getZ() >> 4) == cz) {
                    // Defer one tick — the chunk-load handler runs before Citizens has had a
                    // chance to (re-)spawn its NPCs for the chunk. Let Citizens do its work,
                    // then validate.
                    plugin.getServer().getScheduler().runTaskLater(plugin,
                            () -> validateOne(claim, t), 2L);
                }
            }
        }
    }

    private void validateOne(Claim claim, Turret t) {
        // Single-turret validation. Avoids the duplicate-spawn bug where a chunk containing
        // N turrets would schedule N forceValidateAll calls — each one sweeping the entire
        // world and creating fresh NPCs for any still-dead turret BEFORE the previous
        // sweep's freshly-created NPCs were visible to the Citizens registry.
        if (plugin.getTurretEntities() != null) {
            plugin.getTurretEntities().validateSingleTurret(claim, t);
        }
    }
}
