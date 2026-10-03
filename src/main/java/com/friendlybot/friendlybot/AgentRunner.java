package com.friendlybot.friendlybot;

import com.friendlybot.friendlybot.script.BotContext;
import com.friendlybot.friendlybot.script.PrimitiveRegistry;
import com.friendlybot.friendlybot.script.ToolInterpreter;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;

/**
 * One agentic turn per chat message: model proposes JSON tool calls, the mod
 * executes them on the server thread, results feed back, until done/steps run
 * out. Assistant free text is console-only; only the say tool reaches chat.
 */
public final class AgentRunner {
    private AgentRunner() {
    }

    public static void ask(BotManager.Bot bot, ServerPlayer owner, String message) {
        MinecraftServer server = owner.getServer();
        CompletableFuture.runAsync(() -> {
            try {
                runLoop(server, bot, owner, message);
            } catch (Exception e) {
                FriendlyBot.LOGGER.error("Companion {} failed", bot.name(), e);
                server.execute(() -> BotManager.announce(owner,
                        bot.name() + " got confused (" + String.valueOf(e.getMessage()) + "). Try again?"));
            }
        });
    }

    private static void runLoop(MinecraftServer server, BotManager.Bot bot, ServerPlayer owner, String message)
            throws Exception {
        BotConfig config = BotEvents.config();
        if (config.token().isEmpty()) {
            server.execute(() -> BotManager.announce(owner,
                    "No AI token set. An op must run /friendlybot token <key> first."));
            return;
        }
        RemoteTools remote = BotEvents.remote();
        String snapshot = onThread(server, () -> ContextBuilder.snapshot(bot.player()));
        BotManager.pushHistory(bot, "user", message + "\n[world]\n" + snapshot);

        String system = remote.prompt() + "\n\nTools:\n" + ToolInterpreter.catalog(remote.tools());
        for (int step = 0; step < remote.maxSteps(); step++) {
            if (!stillThere(bot)) {
                return;
            }
            String reply = AiClient.chat(config.endpoint(), config.token(), remote.model(),
                    remote.temperature(), remote.maxTokens(), system, bot.history());
            FriendlyBot.LOGGER.info("[{}] thinking: {}", bot.name(), reply);
            BotProtocol.Reply parsed = BotProtocol.parse(reply);
            if (parsed instanceof BotProtocol.Reply.Done done) {
                FriendlyBot.LOGGER.info("[{}] done: {}", bot.name(), done.summary());
                BotManager.pushHistory(bot, "assistant", reply);
                return;
            }
            if (parsed instanceof BotProtocol.Reply.Say say) {
                runTool(server, bot, "say", Map.of("text", say.text()));
                BotManager.pushHistory(bot, "assistant", reply);
                return;
            }
            if (parsed instanceof BotProtocol.Reply.Tool tool) {
                String result = runTool(server, bot, tool.name(), tool.args());
                BotManager.pushHistory(bot, "assistant", reply);
                BotManager.pushHistory(bot, "user",
                        "Tool " + tool.name() + " returned: " + result + "\n[world]\n"
                                + onThread(server, () -> ContextBuilder.snapshot(bot.player())));
                continue;
            }
            BotManager.pushHistory(bot, "assistant", reply);
            BotManager.pushHistory(bot, "user",
                    "That was not a tool call. Answer with exactly one JSON object: "
                            + "{\"tool\": name, \"args\": {...}}, {\"say\": ...}, or {\"done\": ...}.");
        }
        FriendlyBot.LOGGER.info("[{}] step limit reached", bot.name());
    }

    private static String runTool(MinecraftServer server, BotManager.Bot bot, String name, Map<String, String> args)
            throws Exception {
        ToolInterpreter.ToolDef tool = BotEvents.remote().byName(name);
        if (tool == null) {
            return "ERROR: unknown tool '" + name + "'";
        }
        return onThread(server, () -> {
            ServerPlayer owner = null;
            UUID ownerId = BotManager.ownerOf(bot);
            if (ownerId != null) {
                owner = server.getPlayerList().getPlayer(ownerId);
            }
            BotContext ctx = new BotContext(bot.player(), owner);
            return ToolInterpreter.run(tool, new HashMap<>(args), ctx);
        });
    }

    private static boolean stillThere(BotManager.Bot bot) {
        return bot.player().isAlive() && !bot.player().hasDisconnected();
    }

    private static <T> T onThread(MinecraftServer server, java.util.concurrent.Callable<T> task) throws Exception {
        if (server.isSameThread()) {
            return task.call();
        }
        FutureTask<T> future = new FutureTask<>(task);
        server.execute(future);
        return future.get();
    }

    public static String defaultPrompt() {
        return "You are an in-game companion. Answer with exactly one JSON object per message.";
    }

    public record RemoteTools(String prompt, List<ToolInterpreter.ToolDef> tools, String model,
            double temperature, int maxTokens, int maxSteps) {
        public ToolInterpreter.ToolDef byName(String name) {
            for (ToolInterpreter.ToolDef tool : tools) {
                if (tool.name().equals(name)) {
                    return tool;
                }
            }
            return null;
        }
    }

    public static RemoteTools parseRemote(String toolsJson, String prompt) {
        String model = BotConfig.DEFAULT_MODEL;
        double temperature = 0.3;
        int maxTokens = 800;
        int maxSteps = 10;
        try {
            JsonObject root = JsonParser.parseString(toolsJson).getAsJsonObject();
            if (root.has("model")) {
                model = root.get("model").getAsString();
            }
            if (root.has("temperature")) {
                temperature = root.get("temperature").getAsDouble();
            }
            if (root.has("maxTokens")) {
                maxTokens = root.get("maxTokens").getAsInt();
            }
            if (root.has("maxSteps")) {
                maxSteps = root.get("maxSteps").getAsInt();
            }
        } catch (RuntimeException e) {
            FriendlyBot.LOGGER.error("Bad tools.json, using defaults", e);
        }
        return new RemoteTools(prompt, ToolInterpreter.parse(toolsJson), model, temperature, maxTokens, maxSteps);
    }
}
