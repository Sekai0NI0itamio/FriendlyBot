package com.friendlybot.friendlybot;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(FriendlyBot.MOD_ID)
public class FriendlyBot {
    public static final String MOD_ID = "friendlybot";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FriendlyBot() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            MinecraftForge.EVENT_BUS.register(BotEvents.class);
        }
    }
}
