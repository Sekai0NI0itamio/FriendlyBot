package com.friendlybot.friendlybot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible chat client. Non-streaming on purpose: some
 * free-model SSE streams omit the terminal event, so streaming is unreliable.
 */
public final class AiClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private AiClient() {
    }

    public static String chat(String endpoint, String token, String model,
            double temperature, int maxTokens, String system, List<Map<String, String>> history)
            throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", system);
        messages.add(systemMessage);
        for (Map<String, String> message : history) {
            JsonObject line = new JsonObject();
            line.addProperty("role", message.getOrDefault("role", "user"));
            line.addProperty("content", message.getOrDefault("content", ""));
            messages.add(line);
        }
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            throw new IOException("AI rate limit hit, try again shortly");
        }
        if (response.statusCode() != 200) {
            throw new IOException("AI HTTP " + response.statusCode() + ": " + truncate(response.body()));
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IOException("AI returned no choices");
        }
        return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
    }

    private static String truncate(String text) {
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
