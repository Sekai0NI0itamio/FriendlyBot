package com.friendlybot.friendlybot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * Fresh world truth for every agent step. Coordinates are RELATIVE to the bot:
 * 0,0,0 is where it stands, +y is up. Air blocks are omitted to save tokens.
 */
public final class ContextBuilder {
    private ContextBuilder() {
    }

    public static String snapshot(ServerPlayer bot) {
        StringBuilder out = new StringBuilder();
        BlockPos origin = bot.blockPosition();
        out.append(String.format("pos %.1f %.1f %.1f %s hp %.0f food %d\n",
                bot.getX(), bot.getY(), bot.getZ(),
                bot.serverLevel().dimension().location(),
                bot.getHealth(), bot.getFoodData().getFoodLevel()));
        out.append("inventory: ").append(inventory(bot)).append("\n");
        out.append("terrain(5x5x5 rel, air omitted): ").append(terrain(bot, origin)).append("\n");
        out.append("entities: ").append(entities(bot, origin)).append("\n");
        out.append("containers: ").append(containers(bot, origin));
        return out.toString();
    }

    private static String inventory(ServerPlayer bot) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < bot.getInventory().items.size(); i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                out.add(i + ":" + ForgeRegistries.ITEMS.getKey(stack.getItem()) + "x" + stack.getCount());
            }
        }
        return out.isEmpty() ? "empty" : String.join(" ", out);
    }

    private static String terrain(ServerPlayer bot, BlockPos origin) {
        List<String> out = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockState state = bot.serverLevel().getBlockState(origin.offset(x, y, z));
                    if (!state.isAir()) {
                        out.add(x + "," + y + "," + z + "=" + ForgeRegistries.BLOCKS.getKey(state.getBlock()));
                    }
                }
            }
        }
        return out.isEmpty() ? "all air" : String.join(" ", out);
    }

    private static String entities(ServerPlayer bot, BlockPos origin) {
        List<String> out = new ArrayList<>();
        for (Entity entity : bot.serverLevel().getEntities(null, new AABB(origin).inflate(12))) {
            if (entity == bot || out.size() >= 15) {
                continue;
            }
            BlockPos rel = entity.blockPosition().subtract(origin);
            String label = entity.getType().toShortString();
            if (entity instanceof ServerPlayer player) {
                label += ":" + player.getGameProfile().getName();
            } else if (entity.hasCustomName()) {
                label += ":" + entity.getCustomName().getString();
            }
            out.add(label + " " + rel.getX() + "," + rel.getY() + "," + rel.getZ());
        }
        return out.isEmpty() ? "none" : String.join(" | ", out);
    }

    private static String containers(ServerPlayer bot, BlockPos origin) {
        List<String> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -8, -8), origin.offset(8, 8, 8))) {
            BlockEntity blockEntity = bot.serverLevel().getBlockEntity(pos);
            if (blockEntity instanceof net.minecraft.world.Container) {
                BlockPos rel = pos.subtract(origin);
                out.add(ForgeRegistries.BLOCKS.getKey(bot.serverLevel().getBlockState(pos).getBlock())
                        + " " + rel.getX() + "," + rel.getY() + "," + rel.getZ());
            }
        }
        return out.isEmpty() ? "none" : String.join(" | ", out);
    }
}
