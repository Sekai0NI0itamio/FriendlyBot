package com.friendlybot.friendlybot.script;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything a primitive may touch. The runner guarantees all calls happen
 * on the server thread.
 */
public final class BotContext {
    public final ServerPlayer bot;
    public final ServerLevel level;
    public final MinecraftServer server;
    public final ServerPlayer owner;

    public volatile double targetX;
    public volatile double targetY;
    public volatile double targetZ;
    public volatile boolean hasTarget;
    public volatile boolean followOwner;
    public volatile double lastX;
    public volatile double lastZ;
    public volatile int stillTicks;

    public BotContext(ServerPlayer bot, ServerPlayer owner) {
        this.bot = bot;
        this.owner = owner;
        this.server = bot.getServer();
        this.level = bot.serverLevel();
    }
}
