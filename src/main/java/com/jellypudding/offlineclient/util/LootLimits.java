package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryCountSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

// How many of each kind ChestStealer takes. A stack falls under the first limit that covers it.
// Its own item comes first then its kind of gear then wood then food then any full block.
public final class LootLimits {

    // Stacks under one rule share its count. The key tells the rules apart.
    public record Rule(Object key, int limit) {
    }

    // Kinds counted together whatever the item.
    private enum Group { WOOD, FOOD, BLOCKS }

    private static final int STACK = 64;
    // The slider for a shared count reaches a whole inventory of stacks.
    private static final int MOST_STACKS = InventoryUtil.WHOLE_INVENTORY * STACK;

    private final BoolSetting limits = new BoolSetting("Limits",
        "Takes only what a limit below covers and only until you hold that many. Nought takes none.", false)
        .startFolded();
    // A new pick starts at one full stack.
    private final RegistryCountSetting<Item> items = new RegistryCountSetting<>("Item limits",
        "How many of each item you take. Click to pick them and click a number to change it.",
        BuiltInRegistries.ITEM, defaultItems(), Item::getDefaultMaxStackSize, 0)
        .under(limits);
    private final BoolSetting gear = new BoolSetting("Gear",
        "Takes armour and weapons and tools and shields and shulker boxes up to the counts below.", true)
        .under(limits);
    private final Map<GearKind, NumberSetting> gearCounts = new EnumMap<>(GearKind.class);
    private final NumberSetting wood = new NumberSetting("Wood",
        "How many logs and planks you keep together.", STACK, 0, MOST_STACKS, STACK)
        .under(limits);
    private final NumberSetting food = new NumberSetting("Food",
        "How many of the foods below you keep together.", STACK, 0, MOST_STACKS, STACK)
        .under(limits);
    private final RegistryListSetting<Item> foods = new RegistryListSetting<>("Foods",
        "The foods the food count covers. Click to pick them.", BuiltInRegistries.ITEM, List.of(
            Items.COOKED_PORKCHOP, Items.COOKED_BEEF, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
            Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.GOLDEN_CARROT,
            Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.BAKED_POTATO, Items.BREAD,
            Items.PUMPKIN_PIE))
        .only(item -> item.components().has(DataComponents.FOOD))
        .under(limits);
    private final NumberSetting blocks = new NumberSetting("Blocks",
        "How many other full blocks you keep together.", 6 * STACK, 0, MOST_STACKS, STACK)
        .under(limits);
    private final BoolSetting upgrade = new BoolSetting("Upgrade",
        "Once nothing more can be taken trades your worse gear and small stacks for better ones in the"
            + " container.", true)
        .under(limits);
    private final BoolSetting extras = new BoolSetting("Store extras",
        "Puts what you hold past a limit back into the container.", true)
        .under(limits);

    public LootLimits() {
        for (GearKind kind : GearKind.values()) {
            String label = EnumSetting.label(kind);
            gearCounts.put(kind, new NumberSetting(label, "How many " + label.toLowerCase(Locale.ROOT)
                + " you keep.", startingCount(kind), 0, 9, 1).under(gear));
        }
    }

    // One of each kind except three pickaxes since they wear out fastest and two boxes for the loot.
    private static int startingCount(GearKind kind) {
        return switch (kind) {
            case PICKAXES -> 3;
            case SHULKER_BOXES -> 2;
            default -> 1;
        };
    }

    private static Map<Item, Integer> defaultItems() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        counts.put(Items.TOTEM_OF_UNDYING, 8);
        counts.put(Items.END_CRYSTAL, STACK);
        counts.put(Items.ENDER_PEARL, 32);
        counts.put(Items.EXPERIENCE_BOTTLE, STACK);
        counts.put(Items.ELYTRA, 1);
        counts.put(Items.FIREWORK_ROCKET, STACK);
        counts.put(Items.OBSIDIAN, STACK);
        counts.put(Items.BUCKET, 12);
        counts.put(Items.WATER_BUCKET, 2);
        counts.put(Items.LAVA_BUCKET, 2);
        counts.put(Items.FLINT_AND_STEEL, 3);
        counts.put(Items.TNT, 4 * STACK);
        counts.put(Items.GUNPOWDER, STACK);
        counts.put(Items.SAND, STACK);
        counts.put(Items.IRON_INGOT, STACK);
        counts.put(Items.IRON_BLOCK, STACK);
        counts.put(Items.DIAMOND, STACK);
        counts.put(Items.DIAMOND_BLOCK, STACK);
        counts.put(Items.BEACON, STACK);
        counts.put(Items.WITHER_SKELETON_SKULL, STACK);
        counts.put(Items.SOUL_SAND, STACK);
        counts.put(Items.NAME_TAG, STACK);
        counts.put(Items.FLINT, STACK);
        counts.put(Items.STICK, STACK);
        counts.put(Items.CRAFTING_TABLE, STACK);
        return counts;
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(List.of(limits, items, gear));
        all.addAll(gearCounts.values());
        all.addAll(List.of(wood, food, foods, blocks, upgrade, extras));
        return all.toArray(new Setting<?>[0]);
    }

    public boolean isOn() {
        return limits.isOn();
    }

    // Hides the whole group whilst the answer is false.
    public void visibleWhen(Supplier<Boolean> shown) {
        limits.visibleWhen(shown);
    }

    public boolean upgrades() {
        return upgrade.isOn();
    }

    public boolean storesExtras() {
        return extras.isOn();
    }

    // Null when no limit covers the stack.
    public Rule ruleFor(ItemStack stack) {
        int own = items.countOf(stack.getItem());
        if (own >= 0) {
            return new Rule(stack.getItem(), own);
        }
        GearKind kind = gear.isOn() ? GearKind.of(stack) : null;
        if (kind != null) {
            return new Rule(kind, gearCounts.get(kind).getInt());
        }
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)) {
            return new Rule(Group.WOOD, wood.getInt());
        }
        if (foods.contains(stack.getItem())) {
            return new Rule(Group.FOOD, food.getInt());
        }
        return ItemUtil.isFullBlock(stack.getItem()) ? new Rule(Group.BLOCKS, blocks.getInt()) : null;
    }

    // How many the player holds under each rule. Worn armour and the offhand count.
    public Map<Rule, Integer> holdings() {
        Map<Rule, Integer> held = new HashMap<>();
        Inventory inventory = OfflineClient.MC.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            Rule rule = stack.isEmpty() ? null : ruleFor(stack);
            if (rule != null) {
                held.merge(rule, stack.getCount(), Integer::sum);
            }
        }
        return held;
    }

    // The best score worn or held in the offhand under each rule.
    public Map<Rule, Double> bestWorn() {
        Map<Rule, Double> best = new HashMap<>();
        List<ItemStack> worn = new ArrayList<>();
        for (EquipmentSlot slot : ItemUtil.ARMOR_SLOTS) {
            worn.add(OfflineClient.MC.player.getItemBySlot(slot));
        }
        worn.add(OfflineClient.MC.player.getOffhandItem());
        for (ItemStack stack : worn) {
            Rule rule = stack.isEmpty() ? null : ruleFor(stack);
            if (rule != null) {
                best.merge(rule, ItemUtil.gearScore(stack), Math::max);
            }
        }
        return best;
    }
}
