package com.raeyd.raidattack.auth;

import com.raeyd.raidattack.HomeSystemPlugin;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinIdentifier;
import net.skinsrestorer.api.property.SkinProperty;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Gives every session the skin of the edition it's actually being played on: a Bedrock session shows
 * the player's Bedrock skin, a Java session shows their Java skin — even though both editions of a
 * linked account share ONE canonical identity (the RID = {@code player.getUniqueId()}).
 *
 * <p>That shared identity is the catch. Because {@link BedrockLinkHandshake} connects a Bedrock client
 * <em>as</em> its Java account, SkinsRestorer's per-player skin slot is keyed by the RID and is therefore
 * the SAME slot for both editions — and SkinsRestorer re-applies whatever sits in that slot on every
 * join. So we must never leave a persistent, edition-specific skin parked in it; we key skin choice off
 * the <em>live</em> connection instead:
 *
 * <ul>
 *   <li><b>Bedrock session</b> (a live Floodgate connection): Floodgate treats the linked player as Java
 *       and looks up the RID's Mojang skin, which doesn't exist for our offline accounts — leaving them
 *       skinless. So we fetch their real Bedrock skin from GeyserMC's public API (by XUID) and hand it to
 *       SkinsRestorer keyed by the RID for this session.</li>
 *   <li><b>Java session</b> (a genuine Java client): if a past Bedrock login parked its Bedrock skin in
 *       the shared slot, we clear it so SkinsRestorer falls back to this account's real Java (name-based)
 *       skin. This also self-heals accounts poisoned by the old "one identity, one look" behaviour — no
 *       DB surgery, they fix themselves on the next Java login.</li>
 * </ul>
 *
 * <p>A skin the player explicitly chose with {@code /skin} is never touched on either edition: we only
 * ever manage the slot when it's empty or holds our own auto marker ({@code rabedrock:<rid>}).
 */
public final class BedrockSkinSync implements Listener {

    private static final String SKIN_API = "https://api.geysermc.org/v2/skin/";
    private static final Pattern VALUE = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SIGNATURE = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"");
    private static final long CACHE_TTL_MS = Duration.ofMinutes(30).toMillis();

    private final HomeSystemPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(SkinProperty skin, long at) {}

    public BedrockSkinSync(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /** The custom-skin name we store a player's Bedrock skin under, so we can tell it apart from a
     *  skin the player picked themselves with {@code /skin}. */
    private static String customName(UUID rid) {
        return "rabedrock:" + rid;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID rid = player.getUniqueId();   // canonical account key — the SAME for both editions

        // Branch on the edition of the LIVE connection, not the stored identity. isFloodgatePlayer(rid)
        // is true only for an active Bedrock session; a genuine Java client is not a Floodgate player.
        if (isBedrockSession(rid)) {
            applyBedrockSessionSkin(player, rid);
        } else {
            applyJavaSessionSkin(player, rid);
        }

        // GUARANTEED re-propagation, independent of who applied the skin. SkinsRestorer resolves an
        // ordinary Java player's name skin itself and does NOT fire an event our hook can catch, so
        // relying on the event alone left those players skinless to everyone but themselves. Instead
        // we unconditionally re-track this player a few seconds after join — after async skin
        // resolution has settled — so every OTHER client re-renders them with the applied skin.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> forceRetrackToAll(player), 70L);
    }

    /** True if this join is a live Bedrock (Floodgate) session. False for a genuine Java client, and
     *  false if Floodgate isn't present at all (then every player is Java). */
    private boolean isBedrockSession(UUID rid) {
        try {
            return org.geysermc.floodgate.api.FloodgateApi.getInstance().isFloodgatePlayer(rid);
        } catch (Throwable t) {
            return false;   // Floodgate not present / API mismatch — treat as Java
        }
    }

    /** BEDROCK session: fetch the player's own Bedrock skin (by XUID) and apply it for this session. */
    private void applyBedrockSessionSkin(Player player, UUID rid) {
        String xuid;
        try {
            var fp = org.geysermc.floodgate.api.FloodgateApi.getInstance().getPlayer(rid);
            xuid = fp != null ? fp.getXuid() : null;
        } catch (Throwable t) {
            return;   // can't resolve the Bedrock identity — leave whatever skin they have
        }
        if (xuid == null || xuid.isBlank()) return;

        final String xuidF = xuid;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            SkinProperty skin = fetchBedrockSkin(xuidF);
            if (skin == null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> storeAndApplyBedrockSkin(player, rid, skin));
        });
    }

    /** JAVA session: apply the player's own Java (name-based Mojang) skin ourselves, then show it to
     *  everyone. We deliberately do NOT lean on SkinsRestorer's own on-join apply: it treats our
     *  offline players as "premium" and skips the name-skin apply, leaving them Steve to everyone but
     *  themselves (confirmed via the [SkinRetrack] textures=false diagnostic). So we resolve the name
     *  skin through SR's Mojang API and apply it directly. A genuine {@code /skin} choice is respected;
     *  a Bedrock skin parked by a past Bedrock login is dropped first. Runs off the main thread
     *  (resolution is cache/network); the apply + re-track hop back to the main thread. */
    private void applyJavaSessionSkin(Player player, UUID rid) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var sr = SkinsRestorerProvider.get();
                var storage = sr.getPlayerStorage();

                Optional<SkinIdentifier> current = storage.getSkinIdOfPlayer(rid);
                boolean ours = current.map(id -> customName(rid).equalsIgnoreCase(id.getIdentifier())).orElse(false);
                if (current.isPresent() && !ours) return;   // a real /skin choice → respect it (SR applies it itself)
                if (ours) storage.removeSkinIdOfPlayer(rid); // drop the Bedrock skin a past Bedrock login parked

                var result = sr.getMojangAPI().getSkin(player.getName());   // this account's Java (name) skin
                if (result.isEmpty()) return;   // non-premium name → no Java skin exists; leave them default
                final SkinProperty prop = result.get().getSkinProperty();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    sr.getSkinApplier(Player.class).applySkin(player, prop);  // put the skin on the profile + refresh
                    forceRetrackToAll(player);                                // and show it to everyone else
                });
            } catch (Throwable t) {
                plugin.getLogger().warning("Java skin apply failed for " + player.getName() + ": " + t.getMessage());
            }
        });
    }

    /** Fetch (cached) the player's Bedrock skin texture from GeyserMC's skin API. Returns null on any
     *  failure — the player just keeps whatever skin they have. Runs off the main thread. */
    private SkinProperty fetchBedrockSkin(String xuid) {
        Cached c = cache.get(xuid);
        if (c != null && System.currentTimeMillis() - c.at() < CACHE_TTL_MS) return c.skin();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(SKIN_API + xuid))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;
            Matcher v = VALUE.matcher(resp.body());
            Matcher s = SIGNATURE.matcher(resp.body());
            if (!v.find() || !s.find()) return null;
            SkinProperty skin = SkinProperty.of(v.group(1), s.group(1));
            cache.put(xuid, new Cached(skin, System.currentTimeMillis()));
            return skin;
        } catch (Exception e) {
            plugin.getLogger().fine("Bedrock skin fetch failed for xuid " + xuid + ": " + e.getMessage());
            return null;
        }
    }

    /** Main thread. Store + apply the Bedrock skin via SkinsRestorer, but respect a skin the player chose.
     *  The store is safe despite the shared RID slot: a later Java login self-heals it back to the Java
     *  skin (see {@link #healJavaSessionSkin}), and keeping it in storage lets SkinsRestorer's own
     *  refreshes (resource pack, entity reload) hold the Bedrock skin for the rest of this session. */
    private void storeAndApplyBedrockSkin(Player player, UUID rid, SkinProperty skin) {
        if (!player.isOnline()) return;
        try {
            var sr = SkinsRestorerProvider.get();
            var storage = sr.getPlayerStorage();
            String name = customName(rid);

            // Respect an explicit /skin choice: only manage the slot if it's empty or already ours.
            Optional<SkinIdentifier> current = storage.getSkinIdOfPlayer(rid);
            boolean ours = current.map(id -> name.equalsIgnoreCase(id.getIdentifier())).orElse(false);
            if (current.isPresent() && !ours) return;

            sr.getSkinStorage().setCustomSkinData(name, skin);        // refresh the stored texture
            storage.setSkinIdOfPlayer(rid, SkinIdentifier.ofCustom(name));
            sr.getSkinApplier(Player.class).applySkin(player, skin);  // apply + refresh this session
            forceRetrackToAll(player);                                // make it show to everyone else too
        } catch (Throwable t) {
            plugin.getLogger().warning("Bedrock skin apply failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    /**
     * Subscribe to SkinsRestorer's own apply event so we re-track a player whenever SR paints a skin
     * on them — this is the path that covers ordinary Java players (SR resolves their name skin on
     * join and applies it itself; our listener never touches those). Without the re-track, SR updates
     * the wearer's own view but players who were ALREADY nearby keep the pre-skin (Steve) entity their
     * client cached at spawn. Call once, after the listener is registered.
     */
    public void registerSkinRefreshHook() {
        try {
            SkinsRestorerProvider.get().getEventBus().subscribe(plugin,
                    net.skinsrestorer.api.event.SkinApplyEvent.class, event -> {
                        try {
                            Player p = event.getPlayer(Player.class);
                            if (p != null) forceRetrackToAll(p);
                        } catch (Throwable ignored) {}
                    });
            plugin.getLogger().info("Auth: skin refresh hook registered (re-track viewers on every SkinsRestorer apply).");
        } catch (Throwable t) {
            plugin.getLogger().warning("Auth: could not register skin refresh hook — skins may not show to others: " + t.getMessage());
        }
    }

    /**
     * Force every OTHER online player to drop and re-spawn {@code target}'s player entity, so a
     * freshly-applied skin actually shows to players who were already tracking them. A client caches
     * a player's skin from the profile it received when the entity first spawned; changing the profile
     * afterwards only updates the wearer's own view unless the entity is re-sent. hide → show (2 ticks
     * later) is that re-send. Always hops to the main thread (SR's apply event can fire off-thread).
     */
    private void forceRetrackToAll(Player target) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!target.isOnline()) return;

            // Diagnostic: does the server-side profile actually carry a skin now? If this logs
            // textures=false, SkinsRestorer never wrote the skin onto the profile (so no re-track can
            // help — the fix would be elsewhere). textures=true means the skin IS on the profile and
            // the re-track below should make every viewer render it.
            boolean hasTextures = false;
            try {
                hasTextures = target.getPlayerProfile().getProperties().stream()
                        .anyMatch(p -> "textures".equalsIgnoreCase(p.getName()));
            } catch (Throwable ignored) {}

            int viewers = 0;
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                if (viewer.equals(target)) continue;
                try { viewer.hidePlayer(plugin, target); viewers++; } catch (Throwable ignored) {}
            }
            plugin.getLogger().info("[SkinRetrack] " + target.getName()
                    + " textures=" + hasTextures + " re-tracked to " + viewers + " viewer(s)");

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!target.isOnline()) return;
                for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                    if (viewer.equals(target)) continue;
                    try { viewer.showPlayer(plugin, target); } catch (Throwable ignored) {}
                }
            }, 2L);
        });
    }
}
