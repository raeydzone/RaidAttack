package com.raeyd.raidattack.core;

import com.raeyd.raidattack.HomeSystemPlugin;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Per-player artificial network latency. Inserts a {@link ChannelDuplexHandler} at the
 * head of the player's Netty pipeline that schedules every inbound read, every outbound
 * write, and every flush on the channel's own event loop after a configurable delay.
 *
 * <p>This is closer to real network lag than delaying Bukkit events would be, because it
 * sits at the byte-level boundary — the player's client experiences the delay symmetrically
 * (their inputs arrive late at the server, the server's responses arrive late at them).
 *
 * <p>It is fragile because reaching the Channel from a Bukkit {@link Player} requires
 * reflection into NMS (Mojang-mapped since Paper 1.20.5): {@code CraftPlayer.getHandle()
 * → ServerPlayer.connection → ServerCommonPacketListenerImpl.connection → Connection.channel}.
 * If a future Paper update renames those fields this will silently break and the command
 * will report "lag inject failed" — that's the signal to update the field-traversal below.
 *
 * <p>Dev-only. Do not ship to production.
 */
public final class LagSimulator {

    private static final String HANDLER_NAME = "homesystem_lag";

    private final HomeSystemPlugin plugin;
    /** UUIDs of players currently lagged, mapped to the configured ms. */
    private final Map<UUID, Long> active = new HashMap<>();

    public LagSimulator(HomeSystemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Apply (or replace) artificial lag for {@code player}. Pass {@code 0} (or negative)
     * to remove. Returns whether the operation actually changed anything.
     */
    public Result apply(Player player, long ms) {
        Channel channel;
        try {
            channel = getChannel(player);
        } catch (Throwable t) {
            plugin.getLogger().warning("LagSimulator: failed to reach channel for "
                    + player.getName() + " — " + t);
            return Result.FAILED;
        }

        // Drop any existing injector first.
        if (channel.pipeline().get(HANDLER_NAME) != null) {
            try { channel.pipeline().remove(HANDLER_NAME); } catch (Exception ignored) {}
        }

        if (ms <= 0) {
            active.remove(player.getUniqueId());
            return Result.CLEARED;
        }

        try {
            channel.pipeline().addFirst(HANDLER_NAME, new LagInjector(ms));
        } catch (Exception e) {
            plugin.getLogger().warning("LagSimulator: pipeline add failed — " + e);
            return Result.FAILED;
        }
        active.put(player.getUniqueId(), ms);
        return Result.APPLIED;
    }

    public Long currentMs(Player player) {
        return active.get(player.getUniqueId());
    }

    /** Remove the injector from every still-connected player. Call on plugin disable. */
    public void clearAll() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) apply(p, 0);
        }
        active.clear();
    }

    public enum Result { APPLIED, CLEARED, FAILED }

    // -- reflection ---------------------------------------------------------

    private static Channel getChannel(Player player) throws ReflectiveOperationException {
        // CraftPlayer → ServerPlayer
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        // ServerPlayer.connection → ServerGamePacketListenerImpl
        Object listener = readField(handle, "connection");
        // ServerCommonPacketListenerImpl.connection → Connection
        Object netConn = readField(listener, "connection");
        // Connection.channel → io.netty.channel.Channel
        return (Channel) readField(netConn, "channel");
    }

    private static Object readField(Object obj, String name) throws ReflectiveOperationException {
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(obj.getClass().getName() + "." + name);
    }

    // -- the handler --------------------------------------------------------

    /**
     * Re-schedules every read/write/flush on the channel's own eventLoop after the
     * configured delay. Using the channel's loop preserves submission order so packets
     * don't get reordered relative to each other.
     */
    private static final class LagInjector extends ChannelDuplexHandler {
        private final long ms;

        LagInjector(long ms) { this.ms = ms; }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ctx.channel().eventLoop().schedule(() -> {
                try { ctx.fireChannelRead(msg); } catch (Throwable ignored) {}
            }, ms, TimeUnit.MILLISECONDS);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ctx.channel().eventLoop().schedule(() -> {
                try { ctx.write(msg, promise); }
                catch (Throwable t) {
                    if (!promise.isDone()) promise.setFailure(t);
                }
            }, ms, TimeUnit.MILLISECONDS);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            ctx.channel().eventLoop().schedule(() -> {
                try { ctx.flush(); } catch (Throwable ignored) {}
            }, ms, TimeUnit.MILLISECONDS);
        }
    }
}
