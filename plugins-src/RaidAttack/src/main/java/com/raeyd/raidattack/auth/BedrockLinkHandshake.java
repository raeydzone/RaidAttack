package com.raeyd.raidattack.auth;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.util.Locale;
import java.util.UUID;
import org.geysermc.floodgate.api.InstanceHolder;
import org.geysermc.floodgate.api.handshake.HandshakeData;
import org.geysermc.floodgate.api.handshake.HandshakeHandler;
import org.geysermc.floodgate.util.BedrockData;
import org.geysermc.floodgate.util.LinkedPlayer;

/**
 * Cross-edition identity, done at the ONLY layer that works for Bedrock. Floodgate injects a Bedrock
 * player's GameProfile (UUID + name) in the netty handshake — before {@code AsyncPlayerPreLoginEvent}
 * fires — so {@code event.setPlayerProfile(...)} there is silently ignored for Floodgate connections.
 *
 * <p>Floodgate's {@code SpigotDataHandler} builds the final profile from
 * {@code FloodgatePlayer.getCorrectUniqueId()/getCorrectUsername()}, and those return the linked Java
 * identity when a {@link LinkedPlayer} is present. So we register a handshake handler, resolve the
 * Bedrock connection to its Raid Attack account (the RID = {@code accounts.uuid} + the Java name), and
 * call {@link HandshakeData#setLinkedPlayer}. Floodgate then connects the Bedrock client <em>as</em>
 * its Java account: the engine loads {@code playerdata/<RID>.dat} and every {@code world}-schema row
 * keys on the same RID, so inventory / ender chest / XP / health / effects / position are shared
 * across editions for free, and the normal password gate still runs (the player looks like Java).
 *
 * <p>Resolution: fast path by the Floodgate UUID once it's bound to an account; on first login, by the
 * registered Bedrock gamertag, binding the Floodgate UUID so later logins take the fast path. The
 * Floodgate UUID derives from the (permanent) Xbox XUID, NOT the gamertag — so a Bedrock name change
 * never breaks the link.
 *
 * <p>This runs on the netty handshake thread, so the work here is a cheap indexed lookup and is fully
 * fail-safe: any error leaves the connection unlinked, and the pre-login gate then denies it with a
 * clear reason rather than letting it through on a split identity.
 */
public final class BedrockLinkHandshake implements HandshakeHandler {

    private final HomeSystemPlugin plugin;
    private final AuthDatabase db;

    private BedrockLinkHandshake(HomeSystemPlugin plugin, AuthDatabase db) {
        this.plugin = plugin;
        this.db = db;
    }

    /**
     * Register the handler with Floodgate, if Floodgate (and its API) are present. Safe to call
     * unconditionally — logs and no-ops if the API isn't available.
     */
    public static void registerIfPossible(HomeSystemPlugin plugin, AuthDatabase db) {
        try {
            InstanceHolder.getHandshakeHandlers().addHandshakeHandler(new BedrockLinkHandshake(plugin, db));
            plugin.getLogger().info("Auth: Bedrock cross-play link handler registered (Bedrock players adopt their canonical RID).");
        } catch (Throwable t) {
            plugin.getLogger().warning("Auth: could NOT register the Bedrock link handler — Bedrock cross-play identity is OFF: " + t);
        }
    }

    @Override
    public void handle(HandshakeData data) {
        try {
            if (!data.isFloodgatePlayer()) return;            // a Java connection — nothing to unify here
            if (data.getLinkedPlayer() != null) return;       // already linked upstream — respect it
            BedrockData bedrock = data.getBedrockData();
            if (bedrock == null) return;

            // The Floodgate UUID (derived from the XUID) — the id this client would otherwise join with.
            UUID floodgateUuid = data.getJavaUniqueId();
            if (floodgateUuid == null) return;

            // 1) Fast path: this Floodgate UUID is already bound to an account.
            AuthDatabase.AccountAuth account = db.findByBedrockUuid(floodgateUuid);

            // 2) First login: resolve by the registered gamertag, then bind the Floodgate UUID.
            if (account == null) {
                String gamertag = bedrock.getUsername();
                if (gamertag != null && !gamertag.isBlank()) {
                    account = db.findByBedrockUsernameLower(gamertag.toLowerCase(Locale.ROOT));
                    if (account != null) db.bindBedrockUuid(account.rid(), floodgateUuid);
                }
            }

            if (account == null) return;   // no linked account — leave unlinked; the gate denies with a reason

            // Connect this Bedrock client AS its canonical Java account (RID + Java name).
            data.setLinkedPlayer(LinkedPlayer.of(account.username(), account.rid(), floodgateUuid));
        } catch (Throwable t) {
            // Never break a handshake. Unlinked connections are caught and denied at pre-login.
            plugin.getLogger().warning("Auth: Bedrock link lookup failed (" + t.getClass().getSimpleName()
                    + ") — connection left unlinked.");
        }
    }
}
