package com.friendlybot.friendlybot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Token, endpoint, model, plus owner-to-bot-name mapping. The token lives here
 * so it never appears in logs; only ops may change it.
 */
public final class BotConfig {
    public static final String DEFAULT_ENDPOINT = "https://inference-api.nousresearch.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "meituan/longcat-2.0:free";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private final Path file;
    private final Map<String, String> values = new HashMap<>();

    public BotConfig(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        values.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, String> loaded = GSON.fromJson(reader, MAP_TYPE);
            if (loaded != null) {
                values.putAll(loaded);
            }
        }
    }

    public synchronized void save() throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            Map<String, String> safe = new HashMap<>(values);
            if (safe.containsKey("token")) {
                safe.put("token", "***");
            }
            GSON.toJson(values, writer);
        }
    }

    public synchronized String token() {
        return values.getOrDefault("token", "");
    }

    public synchronized void token(String token) {
        values.put("token", token);
    }

    public synchronized String endpoint() {
        return values.getOrDefault("endpoint", DEFAULT_ENDPOINT);
    }

    public synchronized void endpoint(String endpoint) {
        values.put("endpoint", endpoint);
    }

    public synchronized String model() {
        return values.getOrDefault("model", DEFAULT_MODEL);
    }

    public synchronized void model(String model) {
        values.put("model", model);
    }

    public synchronized String botName(String ownerUuid) {
        return values.get("bot:" + ownerUuid);
    }

    public synchronized void botName(String ownerUuid, String name) {
        values.put("bot:" + ownerUuid, name);
    }

    public synchronized void clearBot(String ownerUuid) {
        values.remove("bot:" + ownerUuid);
    }
}
