package com.raeyd.raidattack.raid;

/**
 * Baked-in MineSkin texture for the player-type Raider NPC. Both fields together form a
 * Mojang-signed skin: clients accept it because the {@code SIGNATURE} is Mojang's RSA signature
 * over {@code VALUE} (a base64 JSON blob pointing at {@code textures.minecraft.net}).
 *
 * <p>Skin was uploaded to MineSkin (id {@code 0575b873}) — no HTTP at runtime, the values are
 * applied to the Citizens {@code SkinTrait} directly. If we ever need to rotate the texture,
 * upload a new PNG to mineskin.org and paste the new value/signature pair here.
 *
 * <p>Why this is split into a tiny class of its own: the base64 string is enormous (~500 chars
 * for value, ~700 for signature) and pasting it inline in {@link CustomRaider} would make that
 * file unreadable. Both strings are loaded as constants by the classloader once; no allocation
 * cost per spawn.
 */
public final class RaiderSkin {

    private RaiderSkin() {}

    /** Stable name passed to {@code SkinTrait.setSkinPersistent} — purely a Citizens cache key. */
    public static final String NAME = "skin0575b873";

    /** Base64-encoded JSON containing the textures.minecraft.net URL for the skin PNG. */
    public static final String VALUE =
            "ewogICJ0aW1lc3RhbXAiIDogMTYzNTQwMzQ2MDUxOCwKICAicHJvZmlsZUlkIiA6ICJlZDUzZGQ4MTRmOWQ0YTNjYjRlYjY1MWRjYmE3N2U2NiIsCiAgInByb2ZpbGVOYW1lIiA6ICI0MTQxNDE0MWgiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYmU3NWE0YzI2MGM3NjZmOGU5ZDNkNzA3ZGIwMGRkZTkzYzdhZjRjYjQ0NWJkOTE4MWI0NDE5ODg4NmIwNjhiYSIKICAgIH0KICB9Cn0=";

    /** Mojang RSA signature over {@link #VALUE}. Required by the vanilla client for trust. */
    public static final String SIGNATURE =
            "kXtw4dAPK303KjqCcUjFIDAV3FJ4I+51ibRx0vJPCKu/lJDx45PZ8k4/z2X3BXzo6l4RqgEdaFvIltaVrmvziCrvXJ7I9YpygdeqIX8cf+KMqxg55Jrcwa6HIFgDVTYBujT/YMvb8ii3yKK/jXSPsklla//XBMTiIHqXECWv8oqNZ/ByR6VmGbsUlM0uAf8uyVEEL/a0WRLCrAmn+tfETApuBZzbCX0/+YrKtsi85NwxDDHVSTHUnLjJgcPy2CoorBRFqst0evBlJ92GK6oAQDW0aXk5g3QJHoGNw6Uhd4rynG+sBx+FoxTi1lKT/yJUX1SE/6avCsnyK3cEZovJLv+Iigfq+GonclnfVZ0AC1S1h941UwEXHHD6bMRlEUnd+40Dt+ZzJ14Ocpq5zQn4/ZA12xsTr6mlUU4M4p9jgzOLm7SbyYDKuMyGNeAC+pth5GzRnqspudB7NAIi6S4M/6AFmeMmOWsWYyysaBDN8O5cMt8iZg1xzBJvPN06MDkEvHLLzPMLks7jKQndOYIDZEwSW/FHuq25ZbVm4dvxQIiPkV1GgdyO7lveEs5kOlqzK+7/aqDj7rAzwJloKDtJPGDKhh7AJlYRR0udlgKnmM19HAW8pZRhNqkP5pjI4LYI2UzwGbHlAR4EK3t3VgDuMW8vuAKBWO6dhWP1vchKWtk=";
}
