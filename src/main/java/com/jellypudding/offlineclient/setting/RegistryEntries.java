package com.jellypudding.offlineclient.setting;

import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

// How the picker offers and shows the entries of a registry. Every list setting that picks from
// a registry reads them from here.
final class RegistryEntries {

    // Entities with no egg and no item of their own name.
    private static final Map<EntityType<?>, Item> STAND_INS = Map.ofEntries(
        Map.entry(EntityTypes.PLAYER, Items.PLAYER_HEAD),
        Map.entry(EntityTypes.ITEM, Items.BUNDLE),
        Map.entry(EntityTypes.EXPERIENCE_ORB, Items.EXPERIENCE_BOTTLE),
        Map.entry(EntityTypes.FISHING_BOBBER, Items.FISHING_ROD),
        Map.entry(EntityTypes.EYE_OF_ENDER, Items.ENDER_EYE),
        Map.entry(EntityTypes.LEASH_KNOT, Items.LEAD),
        Map.entry(EntityTypes.LIGHTNING_BOLT, Items.LIGHTNING_ROD.asList().getFirst()),
        Map.entry(EntityTypes.AREA_EFFECT_CLOUD, Items.LINGERING_POTION),
        Map.entry(EntityTypes.EVOKER_FANGS, Items.TOTEM_OF_UNDYING),
        Map.entry(EntityTypes.LLAMA_SPIT, Items.SNOWBALL),
        Map.entry(EntityTypes.SHULKER_BULLET, Items.SHULKER_SHELL),
        Map.entry(EntityTypes.FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.SMALL_FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.DRAGON_FIREBALL, Items.FIRE_CHARGE),
        Map.entry(EntityTypes.BREEZE_WIND_CHARGE, Items.BREEZE_ROD),
        Map.entry(EntityTypes.WITHER_SKULL, Items.WITHER_SKELETON_SKULL),
        Map.entry(EntityTypes.ENDER_DRAGON, Items.DRAGON_HEAD),
        Map.entry(EntityTypes.FALLING_BLOCK, Items.SAND),
        Map.entry(EntityTypes.OMINOUS_ITEM_SPAWNER, Items.TRIAL_KEY),
        Map.entry(EntityTypes.BLOCK_DISPLAY, Items.STRUCTURE_BLOCK),
        Map.entry(EntityTypes.ITEM_DISPLAY, Items.STRUCTURE_BLOCK),
        Map.entry(EntityTypes.TEXT_DISPLAY, Items.STRUCTURE_BLOCK));

    private static final Identifier AIR = Identifier.withDefaultNamespace("air");

    private RegistryEntries() {
    }

    // Every entry the test passes. Air stands for nothing and is never offered.
    static <T> List<T> offered(Registry<T> registry, Predicate<T> test) {
        List<T> all = new ArrayList<>();
        registry.stream().forEach(entry -> {
            Identifier id = registry.getKey(entry);
            if (id != null && !id.equals(AIR) && test.test(entry)) {
                all.add(entry);
            }
        });
        return all;
    }

    static <T> String idOf(Registry<T> registry, T entry) {
        Identifier id = registry.getKey(entry);
        return id == null ? "unknown" : id.toString();
    }

    static <T> String displayName(Registry<T> registry, T entry) {
        return switch (entry) {
            case Block block -> block.getName().getString();
            case Item item -> item.getName(item.getDefaultInstance()).getString();
            case EntityType<?> type -> type.getDescription().getString();
            case MobEffect effect -> effect.getDisplayName().getString();
            default -> ChatUtil.words(registry.getKey(entry));
        };
    }

    // Empty when the entry has no item form.
    static ItemStack icon(Object entry) {
        return switch (entry) {
            case Block block -> new ItemStack(block);
            case Item item -> item.getDefaultInstance();
            case EntityType<?> type -> entityIcon(type);
            default -> ItemStack.EMPTY;
        };
    }

    // The spawn egg where there is one and otherwise the item of the same name such as a boat.
    // The rest fall back to an item or a barrier.
    private static ItemStack entityIcon(EntityType<?> type) {
        Optional<Holder<Item>> egg = SpawnEggItem.byId(type);
        if (egg.isPresent()) {
            return new ItemStack(egg.get());
        }
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(id));
        }
        return new ItemStack(STAND_INS.getOrDefault(type, Items.BARRIER));
    }
}
