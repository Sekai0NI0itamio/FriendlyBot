package com.friendlybot.friendlybot.script;

import com.friendlybot.friendlybot.FriendlyBot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Fetches tools.json from the mod's GitHub repo (or a configured base URL).
 * Falls back to the copy baked into the jar so the mod always works offline.
 */
public final class ToolLoader {
    public static final String DEFAULT_BASE =
            "https://raw.githubusercontent.com/Sekai0NI0itamio/FriendlyBot/main/config/";

    private ToolLoader() {
    }

    public static List<ToolInterpreter.ToolDef> defaults() {
        try (InputStream in = ToolLoader.class.getResourceAsStream("/assets/friendlybot/default-tools.json")) {
            if (in == null) {
                return Collections.emptyList();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ToolInterpreter.parse(json);
        } catch (IOException e) {
            FriendlyBot.LOGGER.error("Failed to read built-in tools", e);
            return Collections.emptyList();
        }
    }

    public static String fetchText(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "FriendlyBot/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
