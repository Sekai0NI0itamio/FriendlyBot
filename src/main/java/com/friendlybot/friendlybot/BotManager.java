package com.friendlybot.friendlybot;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns companion fake players: at most one per owner. A companion is a real
 * ServerPlayer (renders on vanilla clients, no client mod needed); its brain
 * runs in AgentRunner.
 */
public final class BotManager {
    public record Bot(ServerPlayer player, String name, List<Map<String, String>> history) {
    }

    private static final Map<UUID, Bot> ACTIVE = new HashMap<>();

    private BotManager() {
    }

    public static Collection<Bot> active() {
        return ACTIVE.values();
    }

    public static Bot byOwner(UUID owner) {
        return ACTIVE.get(owner);
    }

    public static UUID ownerOf(Bot bot) {
        for (Map.Entry<UUID, Bot> entry : ACTIVE.entrySet()) {
            if (entry.getValue() == bot) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static Bot byName(String name) {
        for (Bot bot : ACTIVE.values()) {
            if (bot.name().equalsIgnoreCase(name)) {
                return bot;
            }
        }
        return null;
    }

    public static ServerPlayer spawn(MinecraftServer server, ServerPlayer owner, String name) {
        dismiss(owner.getUUID());
        UUID id = UUID.nameUUIDFromBytes(("FriendlyBot:" + owner.getUUID() + ":" + name.toLowerCase())
                .getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, name);
        Collection<Property> textures = owner.getGameProfile().getProperties().get("textures");
        if (textures != null && !textures.isEmpty()) {
            profile.getProperties().putAll("textures", new ArrayList<>(textures));
        }
        ServerLevel level = owner.serverLevel();
        ServerPlayer bot = new ServerPlayer(server, level, profile);
        bot.setPos(owner.getX() + 1.5, owner.getY(), owner.getZ() + 1.5);
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet) {
            }

            @Override
            public void send(Packet<?> packet,
                    io.netty.util.concurrent.GenericFutureListener<? extends io.netty.util.concurrent.Future<? super Void>> listener) {
            }

            @Override
            public void tick() {
            }

            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void disconnect(Component message) {
            }
        };
        ServerGamePacketListenerImpl handler = new ServerGamePacketListenerImpl(server, connection, bot);
        bot.connection = handler;
        try {
            server.getPlayerList().placeNewPlayer(connection, bot);
        } catch (RuntimeException e) {
            FriendlyBot.LOGGER.error("Failed to spawn companion for {}", owner.getGameProfile().getName(), e);
            return null;
        }
        ACTIVE.put(owner.getUUID(), new Bot(bot, name, new ArrayList<>()));
        tryAuth(bot);
        return bot;
    }

    /**
     * If an auth mod like SimpleAuth jails strangers, the bot registers and
     * logs itself in through the normal commands. Harmless when absent.
     */
    private static void tryAuth(ServerPlayer bot) {
        try {
            var dispatcher = bot.getServer().getCommands().getDispatcher();
            var source = bot.createCommandSourceStack().withSuppressedOutput();
            dispatcher.execute("register botpass1 botpass1", source);
            dispatcher.execute("login botpass1", source);
        } catch (Exception e) {
            FriendlyBot.LOGGER.info("Companion self-auth skipped: {}", String.valueOf(e.getMessage()));
        }
    }

    public static void dismiss(UUID owner) {
        Bot bot = ACTIVE.remove(owner);
        if (bot != null) {
            try {
                bot.player().getServer().getPlayerList().remove(bot.player());
            } catch (RuntimeException e) {
                FriendlyBot.LOGGER.error("Failed to dismiss companion {}", bot.name(), e);
            }
        }
    }

    public static void dismissAll() {
        for (UUID owner : new ArrayList<>(ACTIVE.keySet())) {
            dismiss(owner);
        }
    }

    public static void pushHistory(Bot bot, String role, String content) {
        bot.history().add(Map.of("role", role, "content", content));
        while (bot.history().size() > 30) {
            bot.history().remove(0);
        }
    }

    public static void announce(ServerPlayer owner, String text) {
        owner.sendSystemMessage(Component.literal(text), false);
    }
}
