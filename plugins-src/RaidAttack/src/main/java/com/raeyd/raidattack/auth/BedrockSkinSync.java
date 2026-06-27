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
 * Restores a Bedrock player's own skin after cross-play identity unification.
 *
 * <p>Because we link a Bedrock client to its Java account (the RID), Floodgate now treats them as a
 * Java player and looks up the RID's <em>Mojang</em> skin — which doesn't exist for our offline
 * accounts, leaving them skinless. So we fetch the player's actual Bedrock skin from GeyserMC's public
 * skin API (by XUID) and hand it to SkinsRestorer keyed by the RID: SkinsRestorer only overrides
 * Floodgate's (empty) skin when it has one stored, and it owns the entity refresh.
 *
 * <p>We store it as the account's skin (so it shows on either edition — one identity, one look) but we
 * never clobber a skin the player explicitly chose with {@code /skin}: if they already have a non-auto
 * skin set, we leave it alone.
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
        UUID rid = player.getUniqueId();   // already canonical post-link

        // Only Bedrock sessions. After linking the player is keyed by the RID (their correct uuid).
        String xuid;
        try {
            var api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
            if (!api.isFloodgatePlayer(rid)) return;
            var fp = api.getPlayer(rid);
            xuid = fp != null ? fp.getXuid() : null;
        } catch (Throwable t) {
            return;   // Floodgate not present / API mismatch — nothing to do
        }
        if (xuid == null || xuid.isBlank()) return;

        final String xuidF = xuid;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            SkinProperty skin = fetchBedrockSkin(xuidF);
            if (skin == null) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> applyViaSkinsRestorer(player, rid, skin));
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

    /** Main thread. Store + apply the skin via SkinsRestorer, but respect a skin the player chose. */
    private void applyViaSkinsRestorer(Player player, UUID rid, SkinProperty skin) {
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
        } catch (Throwable t) {
            plugin.getLogger().warning("Bedrock skin apply failed for " + player.getName() + ": " + t.getMessage());
        }
    }
}
