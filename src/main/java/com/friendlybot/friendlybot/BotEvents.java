package com.friendlybot.friendlybot;

import com.friendlybot.friendlybot.script.BotContext;
import com.friendlybot.friendlybot.script.ToolInterpreter;
import com.friendlybot.friendlybot.script.ToolLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class BotEvents {
    private static BotConfig config;
    private static Path configFile;
    private static MinecraftServer server;
    private static AgentRunner.RemoteTools remote =
            new AgentRunner.RemoteTools("", List.of(), BotConfig.DEFAULT_MODEL, 0.3, 800, 10);

    private BotEvents() {
    }

    public static BotConfig config() {
        return config;
    }

    public static AgentRunner.RemoteTools remote() {
        return remote;
    }

    static void rememberBot(UUID owner, String name) {
        if (config == null) {
            return;
        }
        config.botName(owner.toString(), name);
        saveConfig();
    }

    static void forgetBot(UUID owner) {
        if (config == null) {
            return;
        }
        config.clearBot(owner.toString());
        saveConfig();
    }

    static void setToken(String token) {
        if (config == null) {
            return;
        }
        config.token(token);
        saveConfig();
    }

    static void setModel(String model) {
        if (config == null) {
            return;
        }
        config.model(model);
        saveConfig();
    }

    private static void saveConfig() {
        try {
            config.save();
        } catch (IOException e) {
            FriendlyBot.LOGGER.error("Failed to save FriendlyBot config", e);
        }
    }

    public static void reloadRemote(MinecraftServer server) {
        CompletableFuture.runAsync(() -> {
            try {
                String toolsJson = ToolLoader.fetchText(ToolLoader.DEFAULT_BASE + "tools.json");
                String prompt = ToolLoader.fetchText(ToolLoader.DEFAULT_BASE + "prompt.md");
                AgentRunner.RemoteTools next = AgentRunner.parseRemote(toolsJson, prompt);
                if (next.tools().isEmpty()) {
                    throw new IOException("tools.json defined no tools");
                }
                remote = next;
                FriendlyBot.LOGGER.info("FriendlyBot reloaded {} tools", next.tools().size());
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        Component.literal("FriendlyBot tools reloaded (" + next.tools().size() + ").")
                                .withStyle(ChatFormatting.GREEN),
                        false));
            } catch (Exception e) {
                FriendlyBot.LOGGER.error("FriendlyBot reload failed, keeping old tools", e);
                server.execute(() -> server.getPlayerList().broadcastSystemMessage(
                        Component.literal("FriendlyBot reload failed, keeping old tools.")
                                .withStyle(ChatFormatting.RED),
                        false));
            }
        });
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        configFile = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("friendlybot.json");
        config = new BotConfig(configFile);
        try {
            config.load();
        } catch (IOException e) {
            FriendlyBot.LOGGER.error("Failed to load FriendlyBot config", e);
        }
        remote = new AgentRunner.RemoteTools("", ToolLoader.defaults(),
                config.model(), 0.3, 800, 10);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BotManager.dismissAll();
        saveConfig();
        config = null;
        server = null;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        FriendlyCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (config == null || !(event.getEntity() instanceof ServerPlayer owner)) {
            return;
        }
        String name = config.botName(owner.getUUID().toString());
        if (name != null && BotManager.byOwner(owner.getUUID()) == null && BotProtocol.validBotName(name)) {
            ServerPlayer bot = BotManager.spawn(owner.getServer(), owner, name);
            if (bot != null) {
                FriendlyBot.LOGGER.info("Respawned companion {} for {}", name, owner.getGameProfile().getName());
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer owner) {
            BotManager.dismiss(owner.getUUID());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer bot) {
            BotManager.Bot owned = null;
            for (BotManager.Bot candidate : BotManager.active()) {
                if (candidate.player() == bot) {
                    owned = candidate;
                    break;
                }
            }
            if (owned != null) {
                UUID ownerId = BotManager.ownerOf(owned);
                BotManager.dismiss(ownerId);
                ServerPlayer owner = bot.getServer().getPlayerList().getPlayer(ownerId);
                if (owner != null) {
                    BotManager.announce(owner, owned.name() + " died. Recreate with /friendlybot create " + owned.name());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        String message = event.getMessage().getString();
        if (!message.startsWith("@")) {
            return;
        }
        int space = message.indexOf(' ');
        if (space < 2) {
            return;
        }
        BotManager.Bot bot = BotManager.byName(message.substring(1, space));
        if (bot == null) {
            return;
        }
        ServerPlayer owner = event.getPlayer();
        String ask = message.substring(space + 1).trim();
        if (ask.isEmpty()) {
            return;
        }
        AgentRunner.ask(bot, owner, owner.getGameProfile().getName() + " says: " + ask);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        for (BotManager.Bot bot : List.copyOf(BotManager.active())) {
            if (!bot.player().isAlive() || bot.player().hasDisconnected()) {
                continue;
            }
            tickBot(server, bot);
        }
        if (server.getTickCount() % 100 == 0) {
            for (BotManager.Bot bot : List.copyOf(BotManager.active())) {
                eatIfHungry(bot);
            }
        }
    }

    private static void tickBot(MinecraftServer server, BotManager.Bot bot) {
        ServerPlayer player = bot.player();
        BotContext ctx = contextOf(server, bot);
        if (ctx == null) {
            return;
        }
        if (ctx.hasTarget) {
            double dx = ctx.targetX - player.getX();
            double dy = ctx.targetY - player.getY();
            double dz = ctx.targetZ - player.getZ();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.6) {
                ctx.hasTarget = false;
            } else {
                double step = Math.min(0.35, dist);
                player.teleportTo(player.serverLevel(),
                        player.getX() + dx / dist * step,
                        player.getY() + dy / dist * step,
                        player.getZ() + dz / dist * step,
                        player.getYRot(), player.getXRot());
            }
            return;
        }
        if (ctx.followOwner && ctx.owner != null && ctx.owner.isAlive()) {
            double dx = ctx.owner.getX() - player.getX();
            double dy = ctx.owner.getY() - player.getY();
            double dz = ctx.owner.getZ() - player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 3.0 && dist < 64.0) {
                double step = Math.min(0.35, dist);
                player.teleportTo(player.serverLevel(),
                        player.getX() + dx / dist * step, player.getY(), player.getZ() + dz / dist * step,
                        player.getYRot(), player.getXRot());
            }
        }
    }

    private static BotContext contextOf(MinecraftServer server, BotManager.Bot bot) {
        UUID ownerId = BotManager.ownerOf(bot);
        ServerPlayer owner = ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
        return TickContexts.get(bot, owner);
    }

    private static void eatIfHungry(BotManager.Bot bot) {
        ServerPlayer player = bot.player();
        if (!player.isAlive() || player.getFoodData().getFoodLevel() > 14) {
            return;
        }
        BotContext ctx = contextOf(player.getServer(), bot);
        if (ctx != null) {
            PrimitiveRegistryHolder.eat(ctx);
        }
    }

    private static final class TickContexts {
        private static final java.util.IdentityHashMap<BotManager.Bot, BotContext> CONTEXTS =
                new java.util.IdentityHashMap<>();

        static BotContext get(BotManager.Bot bot, ServerPlayer owner) {
            CONTEXTS.keySet().removeIf(candidate -> !BotManager.active().contains(candidate));
            BotContext ctx = CONTEXTS.get(bot);
            if (ctx == null || ctx.bot != bot.player()) {
                ctx = new BotContext(bot.player(), owner);
                CONTEXTS.put(bot, ctx);
            }
            return ctx;
        }
    }

    private static final class PrimitiveRegistryHolder {
        static void eat(BotContext ctx) {
            try {
                com.friendlybot.friendlybot.script.PrimitiveRegistry.all().get("eat").run(ctx, Map.of());
            } catch (Exception e) {
                FriendlyBot.LOGGER.error("Companion auto-eat failed", e);
            }
        }
    }
}
