package com.raeyd.raidattack.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Durable, crash-safe backup of a player's pre-limbo state (inventory + position), written to
 * {@code plugins/RaidAttack/limbo/<uuid>.yml}.
 *
 * Why this exists: limbo hides the inventory (clears it) and teleports the player to the spawn
 * zone. If the server is autosaved or hard-crashes while a player is mid-limbo, the persisted
 * state on disk would be the cleared inventory / spawn position — the player would lose items and
 * end up at spawn. The vault holds the REAL state so it can always be restored.
 *
 * Duplication safety: restore is SET-based (it overwrites the live inventory, never adds), so
 * applying the vault twice can never create extra items. The file is written once when limbo is
 * first entered and removed only when the player successfully authenticates; every limbo-enter
 * with an existing vault recovers from it. A player can only ever end up with exactly the stored
 * inventory, never more.
 */
public final class LimboVault {

    /** A recovered pre-limbo snapshot. {@code origin} may be null if its world is unavailable. */
    public record Saved(ItemStack[] storage, ItemStack[] armor, ItemStack offhand, Location origin) {}

    private final File dir;
    private final Logger logger;

    public LimboVault(File dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
        if (!dir.exists()) dir.mkdirs();
    }

    private File file(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    public boolean has(UUID uuid) {
        return file(uuid).isFile();
    }

    public void save(UUID uuid, ItemStack[] storage, ItemStack[] armor, ItemStack offhand, Location origin) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("storage", encode(storage));
            y.set("armor", encode(armor));
            y.set("offhand", encode(new ItemStack[] { offhand }));
            if (origin != null && origin.getWorld() != null) {
                y.set("world", origin.getWorld().getUID().toString());
                y.set("x", origin.getX());
                y.set("y", origin.getY());
                y.set("z", origin.getZ());
                y.set("yaw", (double) origin.getYaw());
                y.set("pitch", (double) origin.getPitch());
            }
            y.save(file(uuid));
        } catch (IOException e) {
            // A failed backup must not block the login flow; log it loudly so it's noticed.
            logger.severe("LimboVault: failed to back up state for " + uuid + " — " + e.getMessage());
        }
    }

    public Saved load(UUID uuid) {
        File f = file(uuid);
        if (!f.isFile()) return null;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ItemStack[] storage = decode(y.getString("storage"));
            ItemStack[] armor = decode(y.getString("armor"));
            ItemStack[] off = decode(y.getString("offhand"));
            ItemStack offhand = (off != null && off.length > 0) ? off[0] : null;

            Location origin = null;
            String worldId = y.getString("world");
            if (worldId != null) {
                World w = Bukkit.getWorld(UUID.fromString(worldId));
                if (w != null) {
                    origin = new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                            (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
                }
            }
            return new Saved(storage, armor, offhand, origin);
        } catch (Exception e) {
            logger.severe("LimboVault: failed to read backup for " + uuid + " — " + e.getMessage());
            return null;
        }
    }

    public void delete(UUID uuid) {
        File f = file(uuid);
        if (f.exists() && !f.delete()) {
            logger.warning("LimboVault: could not delete backup file " + f.getName());
        }
    }

    // ItemStack[] <-> Base64 via Bukkit's own object stream (preserves full item data, nulls ok).
    private static String encode(ItemStack[] items) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(items.length);
            for (ItemStack it : items) out.writeObject(it);
        }
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private static ItemStack[] decode(String s) throws IOException, ClassNotFoundException {
        if (s == null) return new ItemStack[0];
        byte[] data = Base64.getDecoder().decode(s);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(data))) {
            int n = in.readInt();
            ItemStack[] arr = new ItemStack[n];
            for (int i = 0; i < n; i++) arr[i] = (ItemStack) in.readObject();
            return arr;
        }
    }
}
