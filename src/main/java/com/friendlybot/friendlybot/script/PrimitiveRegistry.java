package com.friendlybot.friendlybot.script;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every built-in capability, behind one lookup table. Tools (JSON) compose
 * these; nothing else in the mod touches the world directly.
 */
public final class PrimitiveRegistry {
    private static final Map<String, Primitive> ALL = new HashMap<>();

    private PrimitiveRegistry() {
    }

    public static Map<String, Primitive> all() {
        if (ALL.isEmpty()) {
            registerDefaults();
        }
        return ALL;
    }

    public static boolean known(String name) {
        return all().containsKey(name);
    }

    private static void put(String name, Primitive primitive) {
        ALL.put(name, primitive);
    }

    private static int number(Map<String, String> args, String key, int fallback) {
        try {
            return Integer.parseInt(args.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double decimal(Map<String, String> args, String key, double fallback) {
        try {
            return Double.parseDouble(args.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String idOf(Item item) {
        return ForgeRegistries.ITEMS.getKey(item).toString();
    }

    private static String idOf(net.minecraft.world.level.block.Block block) {
        return ForgeRegistries.BLOCKS.getKey(block).toString();
    }

    private static BlockPos relative(BotContext ctx, Map<String, String> args) {
        int dx = number(args, "dx", 0);
        int dy = number(args, "dy", 0);
        int dz = number(args, "dz", 0);
        return new BlockPos(ctx.bot.blockPosition().offset(dx, dy, dz));
    }

    private static void registerDefaults() {
        put("pos", (ctx, args) -> String.format("%.1f %.1f %.1f %s",
                ctx.bot.getX(), ctx.bot.getY(), ctx.bot.getZ(),
                ctx.level.dimension().location()));

        put("owner_pos", (ctx, args) -> {
            if (ctx.owner == null) {
                return "owner offline";
            }
            return String.format("%.1f %.1f %.1f", ctx.owner.getX(), ctx.owner.getY(), ctx.owner.getZ());
        });

        put("health", (ctx, args) -> String.format("hp %.0f/%.0f food %d sat %.1f",
                ctx.bot.getHealth(), ctx.bot.getMaxHealth(),
                ctx.bot.getFoodData().getFoodLevel(), ctx.bot.getFoodData().getSaturationLevel()));

        put("inventory", (ctx, args) -> describeInventory(ctx.bot));

        put("hand", (ctx, args) -> {
            ItemStack hand = ctx.bot.getInventory().getSelected();
            return hand.isEmpty() ? "empty hand" : idOf(hand.getItem()) + " x" + hand.getCount();
        });

        put("nearby_blocks", (ctx, args) -> {
            int r = Math.min(4, Math.max(1, number(args, "r", 2)));
            BlockPos origin = ctx.bot.blockPosition();
            List<String> out = new ArrayList<>();
            for (int x = -r; x <= r && out.size() < 300; x++) {
                for (int y = -r; y <= r && out.size() < 300; y++) {
                    for (int z = -r; z <= r && out.size() < 300; z++) {
                        BlockPos pos = origin.offset(x, y, z);
                        net.minecraft.world.level.block.state.BlockState state = ctx.level.getBlockState(pos);
                        if (!state.isAir()) {
                            out.add(x + "," + y + "," + z + "=" + idOf(state.getBlock()));
                        }
                    }
                }
            }
            return out.isEmpty() ? "only air (omitted)" : String.join(" ", out);
        });

        put("nearby_entities", (ctx, args) -> {
            int r = Math.min(24, Math.max(2, number(args, "r", 12)));
            BlockPos origin = ctx.bot.blockPosition();
            List<String> out = new ArrayList<>();
            for (Entity entity : ctx.level.getEntities(null,
                    new AABB(origin).inflate(r))) {
                if (entity == ctx.bot || out.size() >= 20) {
                    continue;
                }
                BlockPos pos = entity.blockPosition().subtract(origin);
                String label = entity.getType().toShortString();
                if (entity instanceof ServerPlayer player) {
                    label += ":" + player.getGameProfile().getName();
                }
                out.add(label + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
            return out.isEmpty() ? "none" : String.join(" | ", out);
        });

        put("nearby_containers", (ctx, args) -> {
            int r = Math.min(12, Math.max(2, number(args, "r", 8)));
            BlockPos origin = ctx.bot.blockPosition();
            List<String> out = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
                BlockEntity blockEntity = ctx.level.getBlockEntity(pos);
                if (blockEntity instanceof Container) {
                    BlockPos rel = pos.subtract(origin);
                    out.add(idOf(ctx.level.getBlockState(pos).getBlock())
                            + " " + rel.getX() + "," + rel.getY() + "," + rel.getZ());
                }
            }
            return out.isEmpty() ? "none" : String.join(" | ", out);
        });

        put("set_target", (ctx, args) -> {
            ctx.targetX = ctx.bot.getX() + decimal(args, "dx", 0);
            ctx.targetY = ctx.bot.getY() + decimal(args, "dy", 0);
            ctx.targetZ = ctx.bot.getZ() + decimal(args, "dz", 0);
            ctx.hasTarget = true;
            ctx.followOwner = false;
            return "walking";
        });

        put("follow", (ctx, args) -> {
            ctx.followOwner = args.getOrDefault("on", "true").equalsIgnoreCase("true");
            if (!ctx.followOwner) {
                ctx.hasTarget = false;
            }
            return ctx.followOwner ? "following" : "staying";
        });

        put("stop", (ctx, args) -> {
            ctx.hasTarget = false;
            ctx.followOwner = false;
            return "stopped";
        });

        put("break_block", (ctx, args) -> {
            BlockPos pos = relative(ctx, args);
            boolean ok = ctx.bot.gameMode.destroyBlock(pos);
            return ok ? "broke " + idOf(ctx.level.getBlockState(pos).getBlock()) : "cannot break there";
        });

        put("place", (ctx, args) -> {
            int slot = number(args, "slot", -1);
            Inventory inventory = ctx.bot.getInventory();
            if (slot < 0 || slot >= inventory.items.size()) {
                return "bad slot";
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                return "slot empty";
            }
            BlockPos pos = relative(ctx, args);
            net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                    new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5),
                    net.minecraft.core.Direction.UP, pos, false);
            inventory.selected = slot;
            net.minecraft.world.InteractionResult result = ctx.bot.gameMode.useItemOn(
                    ctx.bot, ctx.level, stack, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
            return result.consumesAction() ? "placed" : "place failed";
        });

        put("select", (ctx, args) -> {
            int slot = number(args, "slot", 0);
            if (slot < 0 || slot >= 36) {
                return "slot must be 0-35";
            }
            ctx.bot.getInventory().selected = slot;
            return "selected " + slot;
        });

        put("move_stack", (ctx, args) -> {
            Inventory inventory = ctx.bot.getInventory();
            int from = number(args, "from", -1);
            int to = number(args, "to", -1);
            int count = number(args, "count", -1);
            if (from < 0 || from >= inventory.items.size() || to < 0 || to >= inventory.items.size()) {
                return "bad slots";
            }
            ItemStack source = inventory.getItem(from);
            if (source.isEmpty()) {
                return "source empty";
            }
            if (count <= 0 || count >= source.getCount()) {
                inventory.setItem(to, source.copy());
                inventory.setItem(from, ItemStack.EMPTY);
                return "moved all";
            }
            ItemStack taken = source.split(count);
            ItemStack target = inventory.getItem(to);
            if (target.isEmpty()) {
                inventory.setItem(to, taken);
                return "moved " + count;
            }
            if (ItemStack.isSameItemSameTags(target, taken)
                    && target.getCount() + taken.getCount() <= target.getMaxStackSize()) {
                target.grow(taken.getCount());
                return "merged " + count;
            }
            inventory.setItem(from, source.copy());
            return "target occupied";
        });

        put("merge_all", (ctx, args) -> {
            Inventory inventory = ctx.bot.getInventory();
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < inventory.items.size(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
                inventory.setItem(i, ItemStack.EMPTY);
            }
            int slot = 0;
            for (ItemStack stack : stacks) {
                while (!stack.isEmpty() && slot < inventory.items.size()) {
                    ItemStack target = inventory.getItem(slot);
                    if (target.isEmpty()) {
                        inventory.setItem(slot, stack.copy());
                        stack.setCount(0);
                    } else if (ItemStack.isSameItemSameTags(target, stack)) {
                        int room = target.getMaxStackSize() - target.getCount();
                        int move = Math.min(room, stack.getCount());
                        if (move <= 0) {
                            slot++;
                            continue;
                        }
                        target.grow(move);
                        stack.shrink(move);
                        if (!stack.isEmpty()) {
                            slot++;
                        }
                    } else {
                        slot++;
                    }
                }
                if (!stack.isEmpty()) {
                    return "inventory full";
                }
                slot = 0;
            }
            return "tidied";
        });

        put("drop", (ctx, args) -> {
            Inventory inventory = ctx.bot.getInventory();
            int slot = number(args, "slot", inventory.selected);
            int count = number(args, "count", -1);
            if (slot < 0 || slot >= inventory.items.size()) {
                return "bad slot";
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                return "slot empty";
            }
            ItemStack tossed = count <= 0 || count >= stack.getCount()
                    ? inventory.removeItemNoUpdate(slot)
                    : inventory.removeItem(slot, count);
            ctx.bot.drop(tossed, false);
            return "dropped " + idOf(tossed.getItem()) + " x" + tossed.getCount();
        });

        put("recipes_for", (ctx, args) -> describeRecipes(ctx, args.getOrDefault("item", "")));

        put("craft_once", (ctx, args) -> craftOnce(ctx, args.getOrDefault("item", "")));

        put("container_list", (ctx, args) -> {
            Container container = containerAt(ctx, args);
            if (container == null) {
                return "no container there";
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    out.add(i + ":" + idOf(stack.getItem()) + "x" + stack.getCount());
                }
            }
            return out.isEmpty() ? "empty" : String.join(" ", out);
        });

        put("container_take", (ctx, args) -> takeFrom(ctx, args));
        put("container_put", (ctx, args) -> putInto(ctx, args));

        put("furnace", (ctx, args) -> furnace(ctx, args));

        put("eat", (ctx, args) -> {
            Inventory inventory = ctx.bot.getInventory();
            int best = -1;
            int bestFood = -1;
            for (int i = 0; i < inventory.items.size(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem().isEdible()
                        && stack.getItem().getFoodProperties() != null) {
                    int nutrition = stack.getItem().getFoodProperties().getNutrition();
                    if (nutrition > bestFood) {
                        bestFood = nutrition;
                        best = i;
                    }
                }
            }
            if (best < 0) {
                return "no food";
            }
            ItemStack stack = inventory.getItem(best);
            ctx.bot.getFoodData().eat(
                    stack.getItem().getFoodProperties().getNutrition(),
                    stack.getItem().getFoodProperties().getSaturationModifier());
            stack.shrink(1);
            return "ate " + idOf(stack.getItem());
        });

        put("say", (ctx, args) -> {
            String text = args.getOrDefault("text", "");
            ctx.server.getPlayerList().broadcastSystemMessage(
                    Component.literal("<" + ctx.bot.getGameProfile().getName() + "> " + text), false);
            return "said";
        });

        put("status", (ctx, args) -> {
            StringBuilder out = new StringBuilder();
            out.append(String.format("pos %.1f %.1f %.1f hp %.0f food %d ",
                    ctx.bot.getX(), ctx.bot.getY(), ctx.bot.getZ(),
                    ctx.bot.getHealth(), ctx.bot.getFoodData().getFoodLevel()));
            if (ctx.hasTarget) {
                out.append(String.format("en-route %.1f %.1f %.1f ",
                        ctx.targetX, ctx.targetY, ctx.targetZ));
            } else if (ctx.followOwner) {
                out.append("following ");
            } else {
                out.append("idle ");
            }
            out.append(describeInventory(ctx.bot));
            return out.toString();
        });
    }

    static String describeInventory(ServerPlayer bot) {
        Inventory inventory = bot.getInventory();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                out.add(i + ":" + idOf(stack.getItem()) + "x" + stack.getCount());
            }
        }
        return out.isEmpty() ? "inventory empty" : String.join(" ", out);
    }

    private static Container containerAt(BotContext ctx, Map<String, String> args) {
        BlockEntity blockEntity = ctx.level.getBlockEntity(relative(ctx, args));
        return blockEntity instanceof Container container ? container : null;
    }

    private static String takeFrom(BotContext ctx, Map<String, String> args) {
        Container container = containerAt(ctx, args);
        if (container == null) {
            return "no container there";
        }
        String want = args.getOrDefault("item", "");
        int count = number(args, "count", -1);
        int moved = 0;
        for (int i = 0; i < container.getContainerSize() && (count < 0 || moved < count); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !idOf(stack.getItem()).equals(want)) {
                continue;
            }
            int take = count < 0 ? stack.getCount() : Math.min(stack.getCount(), count - moved);
            ItemStack part = container.removeItem(i, take);
            ItemStack rest = addToBot(ctx, part);
            moved += take - rest.getCount();
            if (!rest.isEmpty()) {
                container.setItem(i, rest);
                break;
            }
        }
        return moved <= 0 ? "nothing taken" : "took " + want + " x" + moved;
    }

    private static String putInto(BotContext ctx, Map<String, String> args) {
        Container container = containerAt(ctx, args);
        if (container == null) {
            return "no container there";
        }
        int slot = number(args, "slot", ctx.bot.getInventory().selected);
        Inventory inventory = ctx.bot.getInventory();
        if (slot < 0 || slot >= inventory.items.size()) {
            return "bad slot";
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack.isEmpty()) {
            return "slot empty";
        }
        int count = number(args, "count", stack.getCount());
        ItemStack part = inventory.removeItem(slot, Math.min(count, stack.getCount()));
        ItemStack rest = addToContainer(container, part);
        if (!rest.isEmpty()) {
            addToBot(ctx, rest);
            return "container full, kept remainder";
        }
        return "stored " + idOf(part.getItem()) + " x" + part.getCount();
    }

    static ItemStack addToBot(BotContext ctx, ItemStack stack) {
        Inventory inventory = ctx.bot.getInventory();
        for (int i = 0; i < inventory.items.size() && !stack.isEmpty(); i++) {
            ItemStack target = inventory.getItem(i);
            if (!target.isEmpty() && ItemStack.isSameItemSameTags(target, stack)) {
                int room = target.getMaxStackSize() - target.getCount();
                int move = Math.min(room, stack.getCount());
                target.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < inventory.items.size() && !stack.isEmpty(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack;
    }

    private static ItemStack addToContainer(Container container, ItemStack stack) {
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            ItemStack target = container.getItem(i);
            if (!target.isEmpty() && ItemStack.isSameItemSameTags(target, stack)) {
                int room = Math.min(target.getMaxStackSize(), container.getMaxStackSize()) - target.getCount();
                int move = Math.min(room, stack.getCount());
                target.grow(move);
                stack.shrink(move);
            }
        }
        for (int i = 0; i < container.getContainerSize() && !stack.isEmpty(); i++) {
            if (container.getItem(i).isEmpty() && container.canPlaceItem(i, stack)) {
                container.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        container.setChanged();
        return stack;
    }

    private static String describeRecipes(BotContext ctx, String itemId) {
        List<String> out = new ArrayList<>();
        for (Recipe<?> recipe : ctx.level.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = recipe.getResultItem(ctx.level.registryAccess());
            } catch (RuntimeException e) {
                continue;
            }
            if (!idOf(result.getItem()).equals(itemId)) {
                continue;
            }
            if (recipe instanceof ShapedRecipe shaped) {
                StringBuilder pattern = new StringBuilder();
                for (String row : shaped.getPattern()) {
                    pattern.append(row).append("/");
                }
                out.add("shaped x" + result.getCount() + " [" + pattern + "]");
            } else {
                out.add(recipe.getType() + " x" + result.getCount());
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out.isEmpty() ? "no recipe for " + itemId : String.join(" | ", out);
    }

    private static String craftOnce(BotContext ctx, String itemId) {
        for (Recipe<?> recipe : ctx.level.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = recipe.getResultItem(ctx.level.registryAccess());
            } catch (RuntimeException e) {
                continue;
            }
            if (!idOf(result.getItem()).equals(itemId)) {
                continue;
            }
            if (!(recipe instanceof CraftingRecipe crafting)) {
                continue;
            }
            SimpleContainer grid = new SimpleContainer(9);
            if (!fillGrid(ctx, crafting, grid)) {
                continue;
            }
            if (!crafting.matches(grid, ctx.level)) {
                continue;
            }
            if (!consumeGrid(ctx, grid)) {
                return "NEED:" + missingFor(ctx, crafting);
            }
            ItemStack made = crafting.assemble(grid, ctx.level.registryAccess());
            for (ItemStack rest : crafting.getRemainingItems(grid)) {
                if (!rest.isEmpty()) {
                    addToBot(ctx, rest.copy());
                }
            }
            addToBot(ctx, made);
            return "crafted " + itemId + " x" + made.getCount();
        }
        return "no crafting recipe for " + itemId;
    }

    private static boolean fillGrid(BotContext ctx, CraftingRecipe recipe, SimpleContainer grid) {
        if (recipe instanceof ShapedRecipe shaped) {
            List<net.minecraft.world.item.crafting.Ingredient> ingredients = shaped.getIngredients();
            int width = shaped.getWidth();
            for (int i = 0; i < ingredients.size(); i++) {
                net.minecraft.world.item.crafting.Ingredient ingredient = ingredients.get(i);
                if (ingredient.isEmpty()) {
                    continue;
                }
                ItemStack[] options = ingredient.getItems();
                if (options.length == 0) {
                    return false;
                }
                int x = i % width;
                int y = i / width;
                ItemStack resolved = new ItemStack(options[0].getItem());
                grid.setItem(y * 3 + x, resolved);
            }
            return true;
        }
        List<net.minecraft.world.item.crafting.Ingredient> ingredients = recipe.getIngredients();
        int slot = 0;
        for (net.minecraft.world.item.crafting.Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] options = ingredient.getItems();
            if (options.length == 0 || slot >= 9) {
                return false;
            }
            grid.setItem(slot++, new ItemStack(options[0].getItem(), 1));
        }
        return true;
    }

    private static Map<Item, Integer> needMap(SimpleContainer grid) {
        Map<Item, Integer> need = new HashMap<>();
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack stack = grid.getItem(i);
            if (!stack.isEmpty()) {
                need.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return need;
    }

    private static String missingFor(BotContext ctx, CraftingRecipe recipe) {
        SimpleContainer probe = new SimpleContainer(9);
        if (!fillGrid(ctx, recipe, probe)) {
            return "unresolvable ingredients";
        }
        Map<Item, Integer> need = needMap(probe);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Item, Integer> entry : need.entrySet()) {
            int have = 0;
            for (int i = 0; i < ctx.bot.getInventory().items.size(); i++) {
                ItemStack stack = ctx.bot.getInventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() == entry.getKey()) {
                    have += stack.getCount();
                }
            }
            if (have < entry.getValue()) {
                missing.add(idOf(entry.getKey()) + " need " + entry.getValue() + " have " + have);
            }
        }
        return String.join(", ", missing);
    }

    private static boolean consumeGrid(BotContext ctx, SimpleContainer grid) {
        Map<Item, Integer> need = needMap(grid);
        Inventory inventory = ctx.bot.getInventory();
        for (Map.Entry<Item, Integer> entry : need.entrySet()) {
            int left = entry.getValue();
            for (int i = 0; i < inventory.items.size() && left > 0; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == entry.getKey()) {
                    int take = Math.min(left, stack.getCount());
                    stack.shrink(take);
                    left -= take;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return true;
    }

    private static String furnace(BotContext ctx, Map<String, String> args) {
        BlockEntity blockEntity = ctx.level.getBlockEntity(relative(ctx, args));
        if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
            return "no furnace there";
        }
        String action = args.getOrDefault("action", "status");
        ItemStack input = furnace.getItem(0);
        ItemStack fuel = furnace.getItem(1);
        ItemStack output = furnace.getItem(2);
        if (action.equals("status")) {
            return (furnace.isLit() ? "lit " : "unlit ")
                    + "in:" + (input.isEmpty() ? "-" : idOf(input.getItem()) + "x" + input.getCount())
                    + " fuel:" + (fuel.isEmpty() ? "-" : idOf(fuel.getItem()) + "x" + fuel.getCount())
                    + " out:" + (output.isEmpty() ? "-" : idOf(output.getItem()) + "x" + output.getCount());
        }
        if (action.equals("take")) {
            if (output.isEmpty()) {
                return "nothing to take";
            }
            ItemStack taken = furnace.removeItemNoUpdate(2);
            addToBot(ctx, taken);
            return "took " + idOf(taken.getItem()) + " x" + taken.getCount();
        }
        if (action.equals("load")) {
            StringBuilder done = new StringBuilder();
            if (fuel.isEmpty()) {
                Inventory inventory = ctx.bot.getInventory();
                for (int i = 0; i < inventory.items.size(); i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (!stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack)) {
                        furnace.setItem(1, inventory.removeItem(i, Math.min(stack.getCount(), 64)));
                        done.append("fueled ").append(idOf(furnace.getItem(1).getItem()));
                        break;
                    }
                }
                if (done.length() == 0) {
                    return "no fuel in inventory";
                }
            }
            if (input.isEmpty()) {
                Inventory inventory = ctx.bot.getInventory();
                boolean loaded = false;
                for (int i = 0; i < inventory.items.size() && !loaded; i++) {
                    ItemStack stack = inventory.getItem(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    SimpleContainer probe = new SimpleContainer(stack.copy());
                    if (ctx.level.getRecipeManager()
                            .getRecipeFor(RecipeType.SMELTING, probe, ctx.level).isPresent()) {
                        furnace.setItem(0, inventory.removeItem(i, Math.min(stack.getCount(), 64)));
                        if (done.length() > 0) {
                            done.append(" ");
                        }
                        done.append("smelting ").append(idOf(furnace.getItem(0).getItem()));
                        loaded = true;
                    }
                }
                if (!loaded) {
                    return done.length() == 0 ? "nothing smeltable" : done + " (no input found)";
                }
            }
            return done.toString();
        }
        return "action must be status, load, or take";
    }
}
