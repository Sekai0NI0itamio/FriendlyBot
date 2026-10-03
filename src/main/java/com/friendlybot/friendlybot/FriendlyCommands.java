package com.friendlybot.friendlybot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class FriendlyCommands {
    private FriendlyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("friendlybot")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("dismiss")
                        .executes(ctx -> dismiss(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("token")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("key", StringArgumentType.greedyString())
                                .executes(ctx -> token(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key")))))
                .then(Commands.literal("model")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> model(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "id"))))));
    }

    private static ServerPlayer playerOf(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }

    private static int create(CommandSourceStack source, String name) {
        ServerPlayer owner = playerOf(source);
        if (owner == null) {
            source.sendFailure(Component.literal("Only players can own companions."));
            return 0;
        }
        if (!BotProtocol.validBotName(name)) {
            source.sendFailure(Component.literal("Name must be 1-16 letters, digits, or underscores."));
            return 0;
        }
        if (BotManager.byName(name) != null) {
            source.sendFailure(Component.literal("That name is taken."));
            return 0;
        }
        ServerPlayer bot = BotManager.spawn(owner.getServer(), owner, name);
        if (bot == null) {
            source.sendFailure(Component.literal("Spawn failed, see console.").withStyle(ChatFormatting.RED));
            return 0;
        }
        BotEvents.rememberBot(owner.getUUID(), name);
        source.sendSuccess(() -> Component.literal(name + " joined! Talk with @" + name + " <message>.")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int dismiss(CommandSourceStack source) {
        ServerPlayer owner = playerOf(source);
        if (owner == null) {
            source.sendFailure(Component.literal("Only players can dismiss companions."));
            return 0;
        }
        if (BotManager.byOwner(owner.getUUID()) == null) {
            source.sendFailure(Component.literal("You have no companion."));
            return 0;
        }
        BotManager.dismiss(owner.getUUID());
        BotEvents.forgetBot(owner.getUUID());
        source.sendSuccess(() -> Component.literal("Companion dismissed."), false);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        BotEvents.reloadRemote(source.getServer());
        source.sendSuccess(() -> Component.literal("Reloading tools and prompt from GitHub...")
                .withStyle(ChatFormatting.YELLOW), false);
        return 1;
    }

    private static int token(CommandSourceStack source, String key) {
        BotEvents.setToken(key.trim());
        source.sendSuccess(() -> Component.literal("AI token updated (not shown, not logged).")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int model(CommandSourceStack source, String id) {
        BotEvents.setModel(id.trim());
        source.sendSuccess(() -> Component.literal("Model set to " + id.trim() + ".")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }
}
