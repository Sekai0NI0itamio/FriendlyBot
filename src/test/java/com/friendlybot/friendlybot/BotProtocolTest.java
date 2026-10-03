package com.friendlybot.friendlybot;

import com.friendlybot.friendlybot.script.ToolInterpreter;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BotProtocolTest {
    @Test
    public void parsesToolSayDone() {
        BotProtocol.Reply tool = BotProtocol.parse("{\"tool\": \"dig\", \"args\": {\"dx\": \"1\"}}");
        assertTrue(tool instanceof BotProtocol.Reply.Tool);
        assertEquals("dig", ((BotProtocol.Reply.Tool) tool).name());
        assertEquals("1", ((BotProtocol.Reply.Tool) tool).args().get("dx"));

        assertTrue(BotProtocol.parse("{\"say\": \"hi\"}") instanceof BotProtocol.Reply.Say);
        assertTrue(BotProtocol.parse("{\"done\": \"ok\"}") instanceof BotProtocol.Reply.Done);
        assertTrue(BotProtocol.parse("just chatting") instanceof BotProtocol.Reply.Invalid);
        assertTrue(BotProtocol.parse("{\"foo\": 1}") instanceof BotProtocol.Reply.Invalid);
    }

    @Test
    public void validatesBotNames() {
        assertTrue(BotProtocol.validBotName("bob"));
        assertTrue(BotProtocol.validBotName("Bob_123"));
        assertTrue(!BotProtocol.validBotName(""));
        assertTrue(!BotProtocol.validBotName("way too long name here"));
        assertTrue(!BotProtocol.validBotName("semi;colon"));
        assertTrue(!BotProtocol.validBotName(null));
    }

    @Test
    public void interpreterRunsStepsWithVars() {
        String json = "{\"tools\": [{\"name\": \"demo\", \"description\": \"d\", \"params\": [\"x\"], "
                + "\"steps\": [{\"primitive\": \"echo\", \"args\": {\"v\": \"${input.x}\"}, \"save_as\": \"e\"}], "
                + "\"return\": \"got ${e}\"}]}";
        List<ToolInterpreter.ToolDef> tools = ToolInterpreter.parse(json);
        assertEquals(1, tools.size());
        com.friendlybot.friendlybot.script.PrimitiveRegistry.all().put("echo",
                (ctx, args) -> args.getOrDefault("v", ""));
        String out = ToolInterpreter.run(tools.get(0), Map.of("x", "hi"), null);
        assertEquals("got hi", out);
        assertEquals("ERROR: unknown primitive 'nope'",
                ToolInterpreter.run(new ToolInterpreter.ToolDef("bad", "", List.of(),
                        List.of(new ToolInterpreter.Step("nope", Map.of(), "")), ""), Map.of(), null));
    }
}
