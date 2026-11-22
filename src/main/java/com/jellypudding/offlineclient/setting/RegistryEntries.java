package com.jellypudding.offlineclient.setting;

import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityKind;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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

    // Boats and minecarts come in a kind for every wood and every load and go by the end of their
    // id.
    private enum Family {
        HOSTILE("hostile", "mob that hunts you", type -> EntityKind.of(type) == EntityKind.HOSTILE),
        NEUTRAL("neutral", "mob that only fights back", type -> EntityKind.of(type) == EntityKind.NEUTRAL),
        PASSIVE("passive", "peaceful land mob", type -> EntityKind.of(type) == EntityKind.PASSIVE),
        WATER("water", "peaceful sea creature", type -> EntityKind.of(type) == EntityKind.WATER),
        BOATS("boats", "boat and raft", type -> idEnds(type, "boat") || idEnds(type, "raft")),
        MINECARTS("minecarts", "kind of minecart", type -> idEnds(type, "minecart"));

        private final String label;
        private final String noun;
        private final Predicate<EntityType<?>> member;

        Family(String label, String noun, Predicate<EntityType<?>> member) {
            this.label = label;
            this.noun = noun;
            this.member = member;
        }
    }

    private RegistryEntries() {
    }

    // Air stands for nothing and is never offered.
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

    // A button for each family that holds some of the offer and not all of it. Only
    // entities come in families.
    static <T> List<RegistryListSetting.Group<T>> groups(Registry<T> registry, Collection<T> offered) {
        List<RegistryListSetting.Group<T>> groups = new ArrayList<>();
        if (registry != BuiltInRegistries.ENTITY_TYPE) {
            return groups;
        }
        for (Family family : Family.values()) {
            List<T> members = offered.stream().filter(entry -> family.member.test((EntityType<?>) entry)).toList();
            if (!members.isEmpty() && members.size() < offered.size()) {
                groups.add(new RegistryListSetting.Group<>(family.label, family.noun, members));
            }
        }
        return groups;
    }

    private static boolean idEnds(EntityType<?> type, String ending) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath().endsWith(ending);
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

    static ItemStack icon(Object entry) {
        return switch (entry) {
            case Block block -> new ItemStack(block);
            case Item item -> item.getDefaultInstance();
            case EntityType<?> type -> entityIcon(type);
            default -> ItemStack.EMPTY;
        };
    }

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
