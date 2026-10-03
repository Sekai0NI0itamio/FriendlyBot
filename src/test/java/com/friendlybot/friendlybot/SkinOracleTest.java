package com.friendlybot.friendlybot;

import org.junit.Test;

import java.util.Arrays;

/**
 * Temporary oracle: real 1.20.1 constructor signatures for fake-player wiring.
 */
public class SkinOracleTest {
    @Test
    public void dumpConstructors() {
        dump("net.minecraft.server.level.ServerPlayer");
        dump("net.minecraft.server.network.ServerGamePacketListenerImpl");
        dump("net.minecraft.network.Connection");
    }

    static void dump(String name) {
        try {
            Class<?> found = Class.forName(name);
            System.out.println("ORACLECTOR-CLASS " + name);
            for (java.lang.reflect.Constructor<?> ctor : found.getDeclaredConstructors()) {
                System.out.println("ORACLECTOR-CTOR " + Arrays.toString(ctor.getParameterTypes()));
            }
        } catch (ClassNotFoundException e) {
            System.out.println("ORACLECTOR-MISSING " + name);
        }
    }
}
