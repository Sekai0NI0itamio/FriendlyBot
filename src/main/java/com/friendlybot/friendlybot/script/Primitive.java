package com.friendlybot.friendlybot.script;

import java.util.Map;

/**
 * One built-in capability. Small, synchronous, server-thread only.
 * New information access arrives as new primitives (jar update); new
 * behavior arrives as JSON tools composing these (no update).
 */
@FunctionalInterface
public interface Primitive {
    String run(BotContext ctx, Map<String, String> args) throws Exception;
}
