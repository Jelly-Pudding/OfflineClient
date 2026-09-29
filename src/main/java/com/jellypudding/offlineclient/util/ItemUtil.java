package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ItemUtil {

    // Head to feet. The order armour is worn in.
    public static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    // What most players want thrown away without being asked. Books and leads and saddles
    // and horns stay off because people keep them.
    public static final List<Item> JUNK = List.of(
        Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
        Items.NETHERRACK, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.WHEAT_SEEDS,
        Items.BEETROOT_SEEDS, Items.MELON_SEEDS, Items.PUMPKIN_SEEDS, Items.BONE, Items.SPIDER_EYE,
        Items.SHORT_GRASS, Items.TALL_GRASS, Items.FERN, Items.DEAD_BUSH, Items.VINE, Items.LILY_PAD,
        Items.SEA_PICKLE, Items.GLOW_LICHEN, Items.SCULK_VEIN, Items.BOWL, Items.RABBIT_HIDE,
        Items.PUFFERFISH, Items.TROPICAL_FISH);

    // Durability points left before a stack counts as about to break.
    private static final int BREAK_MARGIN = 5;

    // A tool that wears out sooner than iron gets less credit for its mining speed. Gold mines
    // fastest of all and breaks after 32 blocks.
    private static final double IRON_DURABILITY = 250;

    // An enchantment written without a level asks for its first.
    private static final int FIRST_LEVEL = 1;

    // More digits than this overflow a whole number and no level or price comes near them.
    private static final int MAX_DIGITS = 9;

    // An enchantment the way a player writes it. The name may keep its namespace and whole
    // numbers may follow it after colons such as a level and then a price.
    public record EnchantmentEntry(Holder<Enchantment> enchantment, List<Integer> numbers) {

        // The first number or level one when none was written.
        public int level() {
            return number(0, FIRST_LEVEL);
        }

        // The number at that place or the fallback when fewer were written.
        public int number(int index, int fallback) {
            return index < numbers.size() ? numbers.get(index) : fallback;
        }
    }

    private ItemUtil() {
    }

    // Every enchantment the world knows. Empty until a world is loaded.
    public static Collection<String> enchantmentIds() {
        if (OfflineClient.MC.level == null) {
            return List.of();
        }
        return OfflineClient.MC.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).keySet()
            .stream().map(Object::toString).sorted().collect(Collectors.toList());
    }

    // Every enchantment the world knows followed by each of its levels above the first written
    // after a colon. Empty until a world is loaded.
    public static Collection<String> enchantmentLevels() {
        if (OfflineClient.MC.level == null) {
            return List.of();
        }
        List<String> choices = new ArrayList<>();
        OfflineClient.MC.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements()
            .sorted(Comparator.comparing(holder -> holder.key().identifier().toString()))
            .forEach(holder -> {
                String id = holder.key().identifier().toString();
                choices.add(id);
                for (int level = FIRST_LEVEL + 1; level <= holder.value().getMaxLevel(); level++) {
                    choices.add(id + ":" + level);
                }
            });
        return choices;
    }

    // Reads one written enchantment. Null outside a world or when the name matches no
    // enchantment the world knows.
    public static EnchantmentEntry enchantmentEntry(String token) {
        if (OfflineClient.MC.level == null) {
            return null;
        }
        String[] parts = token.toLowerCase(Locale.ROOT).split(":");
        int name = parts.length;
        while (name > 1 && isWholeNumber(parts[name - 1])) {
            name--;
        }
        List<Integer> numbers = new ArrayList<>();
        for (int i = name; i < parts.length; i++) {
            numbers.add(Integer.parseInt(parts[i]));
        }
        Identifier id = Identifier.tryParse(String.join(":", List.of(parts).subList(0, name)));
        if (id == null) {
            return null;
        }
        return OfflineClient.MC.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(id)
            .map(holder -> new EnchantmentEntry(holder, List.copyOf(numbers)))
            .orElse(null);
    }

    private static boolean isWholeNumber(String text) {
        return !text.isEmpty() && text.length() <= MAX_DIGITS && text.chars().allMatch(Character::isDigit);
    }

    // Any shulker box whatever its colour.
    public static boolean isShulkerBox(Item item) {
        return Block.byItem(item) instanceof ShulkerBoxBlock;
    }

    // An item that places a whole solid cube such as stone or planks.
    public static boolean isFullBlock(Item item) {
        Block block = Block.byItem(item);
        return block != Blocks.AIR
            && block.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    // How good a piece of gear is. Armour and damage and mining speed add up with every
    // enchantment level and a curse takes its levels away. Wear scales the lot down.
    public static double gearScore(ItemStack stack) {
        EquipmentSlot worn = equipSlot(stack);
        EquipmentSlot slot = worn == null ? EquipmentSlot.MAINHAND : worn;
        double score = 2 * attributeValue(stack, Attributes.ARMOR, slot)
            + attributeValue(stack, Attributes.ARMOR_TOUGHNESS, slot)
            + attributeValue(stack, Attributes.ATTACK_DAMAGE, slot)
            + toolSpeed(stack) + enchantmentLevels(stack);
        if (stack.isDamageableItem()) {
            score *= (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage();
        }
        return score;
    }

    // The fastest a tool mines what it is made for. Nought for anything that is no tool.
    private static double toolSpeed(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        float best = 0;
        if (tool != null) {
            for (Tool.Rule rule : tool.rules()) {
                best = Math.max(best, rule.speed().orElse(0f));
            }
        }
        return best * Math.min(1, stack.getMaxDamage() / IRON_DURABILITY);
    }

    private static int enchantmentLevels(ItemStack stack) {
        int total = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            total += entry.getKey().is(EnchantmentTags.CURSE) ? -entry.getIntValue() : entry.getIntValue();
        }
        return total;
    }

    // Plain or enchanted.
    public static boolean isGoldenApple(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    public static boolean nearlyBroken(ItemStack stack) {
        return nearlyBroken(stack, BREAK_MARGIN);
    }

    // True when this many durability points or fewer remain.
    public static boolean nearlyBroken(ItemStack stack, int margin) {
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= margin;
    }

    // True when a potion or a tipped arrow carries the effect.
    public static boolean carriesEffect(ItemStack stack, MobEffect effect) {
        return carriesEffect(stack, carried -> carried == effect);
    }

    public static boolean carriesEffect(ItemStack stack, Predicate<MobEffect> effect) {
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        if (contents == null) {
            return false;
        }
        for (MobEffectInstance instance : contents.getAllEffects()) {
            if (effect.test(instance.getEffect().value())) {
                return true;
            }
        }
        return false;
    }

    // How much of the durability is left out of a hundred.
    public static double durabilityPercent(ItemStack stack) {
        return 100.0 * (stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage();
    }

    // True when the remaining durability is at or under this share of the whole.
    public static boolean wornBelow(ItemStack stack, double share) {
        if (!stack.isDamageableItem()) {
            return false;
        }
        int max = stack.getMaxDamage();
        return max - stack.getDamageValue() <= max * share;
    }

    // The level of an enchantment on a stack. Zero when absent.
    public static int enchantLevel(ResourceKey<Enchantment> enchantment, ItemStack stack) {
        if (OfflineClient.MC.level == null || stack.isEmpty()) {
            return 0;
        }
        return OfflineClient.MC.level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .get(enchantment)
            .map(entry -> EnchantmentHelper.getItemEnchantmentLevel(entry, stack))
            .orElse(0);
    }

    // Mining speed against a block including the Efficiency enchantment.
    public static float miningSpeed(ItemStack stack, BlockState state) {
        float speed = stack.getDestroySpeed(state);
        if (speed > 1) {
            int level = enchantLevel(Enchantments.EFFICIENCY, stack);
            if (level > 0) {
                speed += level * level + 1;
            }
        }
        return speed;
    }

    // Minus one when nothing in the hotbar beats a bare hand.
    public static int bestToolSlot(BlockState state) {
        return bestToolSlot(state, 1, stack -> true, InventoryUtil.HOTBAR_SIZE);
    }

    // The slot among the first few up to the limit that mines the block fastest. A stack
    // counts only when the filter accepts it and it beats the floor speed.
    public static int bestToolSlot(BlockState state, float floor, Predicate<ItemStack> allowed,
                                   int slots) {
        int bestSlot = -1;
        float bestSpeed = floor;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = OfflineClient.MC.player.getInventory().getItem(i);
            if (!allowed.test(stack)) {
                continue;
            }
            float speed = miningSpeed(stack, state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    // As selectBestTool but a slot the player picked themselves is left alone.
    public static void holdBestTool(BlockState state, InventoryUtil.SlotSwap slots) {
        if (slots.isHolding() && !slots.stillMine()) {
            slots.forget();
            return;
        }
        selectBestTool(state, slots);
    }

    // AutoTool's rules pick the tool whilst it is on and plain speed does otherwise.
    public static void selectBestTool(BlockState state, InventoryUtil.SlotSwap slots) {
        int bestSlot = Modules.toolSlot(state);
        if (bestSlot == -1) {
            return;
        }
        slots.select(bestSlot);
    }

    // True for the right tool for the block and for anything that is no tool at all. A tool of
    // the wrong kind such as a pickaxe on dirt is not.
    public static boolean suits(ItemStack stack, BlockState state) {
        return stack.get(DataComponents.TOOL) == null || stack.isCorrectToolForDrops(state);
    }

    // True when the block drops something broken with this stack. Most blocks drop for anything.
    public static boolean getsDrops(ItemStack stack, BlockState state) {
        return !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
    }

    // True for a tool that loses durability on the blocks it breaks. Anything else mines for free.
    public static boolean wears(ItemStack stack) {
        Tool tool = stack.get(DataComponents.TOOL);
        return tool != null && tool.damagePerBlock() > 0 && stack.isDamageableItem();
    }

    // True when breaking the block costs the stack durability. Shears wear on everything but fire.
    // Any other tool breaks a block with no hardness such as grass for free.
    public static boolean wearsOn(ItemStack stack, BlockState state) {
        if (!wears(stack)) {
            return false;
        }
        if (stack.getItem() instanceof ShearsItem) {
            return !state.is(BlockTags.FIRE);
        }
        return state.getBlock().defaultDestroyTime() != 0;
    }

    // Null for items that cannot be worn.
    public static EquipmentSlot equipSlot(ItemStack stack) {
        Equippable equippable = stack.getItem().components().get(DataComponents.EQUIPPABLE);
        return equippable == null ? null : equippable.slot();
    }

    // Sums a flat attribute the item grants in a slot. Multiplier style
    // modifiers scale a base of zero.
    public static double attributeValue(ItemStack stack, Holder<Attribute> attribute, EquipmentSlot slot) {
        ItemAttributeModifiers modifiers = stack.getItem().components()
            .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double flat = 0;
        double scale = 1;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute() != attribute || !entry.slot().test(slot)) {
                continue;
            }
            double amount = entry.modifier().amount();
            switch (entry.modifier().operation()) {
                case ADD_VALUE -> flat += amount;
                case ADD_MULTIPLIED_BASE -> {
                }
                case ADD_MULTIPLIED_TOTAL -> scale *= 1 + amount;
            }
        }
        return flat * scale;
    }
}
