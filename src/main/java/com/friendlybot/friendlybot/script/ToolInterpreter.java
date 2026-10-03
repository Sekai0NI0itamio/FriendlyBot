package com.friendlybot.friendlybot.script;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs JSON tools: ordered primitive steps with ${var} substitution.
 * A tool is data, not code: {name, description, params[], steps[]}.
 * Steps: {primitive, args{}, save_as?}. ${input.x} reads the tool call args,
 * ${name} reads an earlier step's output. The tool returns its last step's
 * output (or its "return" template).
 */
public final class ToolInterpreter {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private ToolInterpreter() {
    }

    public record ToolDef(String name, String description, List<String> params, List<Step> steps,
                          String returns) {
    }

    public record Step(String primitive, Map<String, String> args, String saveAs) {
    }

    public static List<ToolDef> parse(String json) {
        List<ToolDef> out = new ArrayList<>();
        JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        JsonArray tools = root.getAsJsonArray("tools");
        if (tools == null) {
            return out;
        }
        for (JsonElement element : tools) {
            JsonObject obj = element.getAsJsonObject();
            List<String> params = new ArrayList<>();
            if (obj.has("params")) {
                for (JsonElement param : obj.getAsJsonArray("params")) {
                    params.add(param.getAsString());
                }
            }
            List<Step> steps = new ArrayList<>();
            for (JsonElement stepElement : obj.getAsJsonArray("steps")) {
                JsonObject stepObj = stepElement.getAsJsonObject();
                Map<String, String> args = new HashMap<>();
                if (stepObj.has("args")) {
                    for (Map.Entry<String, JsonElement> entry : stepObj.getAsJsonObject("args").entrySet()) {
                        args.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                steps.add(new Step(stepObj.get("primitive").getAsString(), args,
                        stepObj.has("save_as") ? stepObj.get("save_as").getAsString() : ""));
            }
            out.add(new ToolDef(obj.get("name").getAsString(),
                    obj.has("description") ? obj.get("description").getAsString() : "",
                    params, steps,
                    obj.has("return") ? obj.get("return").getAsString() : ""));
        }
        return out;
    }

    public static String catalog(List<ToolDef> tools) {
        StringBuilder out = new StringBuilder();
        for (ToolDef tool : tools) {
            out.append("- ").append(tool.name()).append("(")
                    .append(String.join(", ", tool.params())).append("): ")
                    .append(tool.description()).append("\n");
        }
        return out.toString();
    }

    public static String run(ToolDef tool, Map<String, String> input, BotContext ctx) {
        Map<String, String> vars = new HashMap<>(input);
        String last = "";
        for (Step step : tool.steps()) {
            Primitive primitive = PrimitiveRegistry.all().get(step.primitive());
            if (primitive == null) {
                return "ERROR: unknown primitive '" + step.primitive() + "'";
            }
            Map<String, String> args = new HashMap<>();
            for (Map.Entry<String, String> entry : step.args().entrySet()) {
                args.put(entry.getKey(), fill(entry.getValue(), vars, input));
            }
            try {
                last = primitive.run(ctx, args);
            } catch (Exception e) {
                return "ERROR in " + step.primitive() + ": " + String.valueOf(e.getMessage());
            }
            if (last == null) {
                last = "";
            }
            if (!step.saveAs().isEmpty()) {
                vars.put(step.saveAs(), last);
            }
        }
        if (!tool.returns().isEmpty()) {
            return fill(tool.returns(), vars, input);
        }
        return last;
    }

    static String fill(String template, Map<String, String> vars, Map<String, String> input) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value;
            if (key.startsWith("input.")) {
                value = input.getOrDefault(key.substring(6), "");
            } else {
                value = vars.getOrDefault(key, "");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
