package com.friendlybot.friendlybot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses assistant replies. Pure logic, unit-tested. The model must answer
 * with one JSON object per message: {"tool": name, "args": {...}},
 * {"say": "..."} (shorthand for the say tool), or {"done": "..."}.
 * Anything else is logged to console and treated as thinking, never chat.
 */
public final class BotProtocol {
    private static final Gson GSON = new Gson();

    private BotProtocol() {
    }

    public sealed interface Reply permits Reply.Tool, Reply.Say, Reply.Done, Reply.Invalid {
        record Tool(String name, Map<String, String> args) implements Reply {
        }

        record Say(String text) implements Reply {
        }

        record Done(String summary) implements Reply {
        }

        record Invalid(String reason) implements Reply {
        }
    }

    public static Reply parse(String text) {
        JsonObject json;
        try {
            json = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return new Reply.Invalid("not JSON");
        }
        if (json.has("tool")) {
            Map<String, String> args = new HashMap<>();
            if (json.has("args") && json.get("args").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> entry
                        : json.getAsJsonObject("args").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        args.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            return new Reply.Tool(json.get("tool").getAsString(), args);
        }
        if (json.has("say")) {
            return new Reply.Say(json.get("say").getAsString());
        }
        if (json.has("done")) {
            return new Reply.Done(json.get("done").getAsString());
        }
        return new Reply.Invalid("no tool/say/done key");
    }

    public static boolean validBotName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }
}
