package com.raeyd.raidattack.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.entity.Entity;

/**
 * Small reflective helpers for setting NMS-internal entity flags that Bukkit doesn't expose.
 *
 * <p>The only one currently used is {@link #setNoPhysics(Entity, boolean)} — turret bullets need
 * vanilla's block-collision off entirely, otherwise the bullet entity gets despawned the
 * instant its float position grazes a solid block (faster than we can cancel
 * {@code ProjectileHitEvent}). With {@code noPhysics = true}, NMS skips its collision pass and
 * our combat manager becomes the sole authority on when bullets die.
 *
 * <p>Implemented via field-walk through the class hierarchy + a small list of known field
 * aliases ({@code noPhysics} is Mojang-mapped; older Spigot mappings use {@code noClip}). On
 * any failure we log once and the bullet just behaves as before (vanilla collision active).
 */
public final class NmsReflection {

    private static final String[] NO_PHYSICS_FIELD_NAMES = {"noPhysics", "noClip"};
    private static volatile boolean warnedNoPhysics = false;

    private NmsReflection() {}

    /**
     * Sets the NMS Entity's {@code noPhysics} (a.k.a. {@code noClip}) flag. True = the entity
     * skips block collision detection entirely. Idempotent and safe to call repeatedly.
     */
    public static boolean setNoPhysics(Entity entity, boolean value) {
        try {
            Method getHandle = entity.getClass().getMethod("getHandle");
            Object handle = getHandle.invoke(entity);
            for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (String name : NO_PHYSICS_FIELD_NAMES) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        f.setBoolean(handle, value);
                        return true;
                    } catch (NoSuchFieldException ignored) {
                        // Try next alias / superclass.
                    }
                }
            }
            // Field not found by any known name. Brute-force: scan boolean fields up the
            // hierarchy and try setting each. We log the first time we land here so the user
            // knows reflection-set failed silently before.
            if (!warnedNoPhysics) {
                warnedNoPhysics = true;
                StringBuilder available = new StringBuilder();
                for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getType() == boolean.class) {
                            available.append(c.getSimpleName()).append(".").append(f.getName()).append(", ");
                        }
                    }
                }
                System.err.println("[HomeSystem] noPhysics field not found on "
                        + handle.getClass().getName()
                        + ". Available boolean fields: " + available);
            }
        } catch (Throwable t) {
            if (!warnedNoPhysics) {
                warnedNoPhysics = true;
                System.err.println("[HomeSystem] Failed to set noPhysics via reflection: "
                        + t.getMessage() + " (turret bullets may die on block grazes)");
            }
        }
        return false;
    }
}
