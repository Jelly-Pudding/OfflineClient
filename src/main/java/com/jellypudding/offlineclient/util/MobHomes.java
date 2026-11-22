package com.jellypudding.offlineclient.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.Set;

// The dimensions each mob turns up in with no player bringing it. They were worked out from the
// 26.3 game data and code. That takes in the natural spawns of every biome a dimension makes and
// the spawn overrides of their structures and the mobs and spawners in the structure files and the
// trial spawners. It also takes in what structures spawn in code and the phantoms and patrols and
// cats and traders the overworld alone spawns and the jockeys mobs spawn with and the mobs that
// lightning and drowning make. A baby zombified piglin can bring a chicken into the nether. Mobs
// only players make such as snow golems and withers and mules have no home and are never judged.
public final class MobHomes {

    private static final Set<EntityType<?>> OVERWORLD = Set.of(EntityTypes.ALLAY, EntityTypes.ARMADILLO,
        EntityTypes.AXOLOTL, EntityTypes.BAT, EntityTypes.BEE, EntityTypes.BOGGED, EntityTypes.BREEZE,
        EntityTypes.CAMEL, EntityTypes.CAMEL_HUSK, EntityTypes.CAT, EntityTypes.CAVE_SPIDER, EntityTypes.CHICKEN,
        EntityTypes.COD, EntityTypes.COW, EntityTypes.CREAKING, EntityTypes.CREEPER, EntityTypes.DOLPHIN,
        EntityTypes.DONKEY, EntityTypes.DROWNED, EntityTypes.ELDER_GUARDIAN, EntityTypes.ENDERMAN, EntityTypes.EVOKER,
        EntityTypes.FOX, EntityTypes.FROG, EntityTypes.GLOW_SQUID, EntityTypes.GOAT, EntityTypes.GUARDIAN,
        EntityTypes.HORSE, EntityTypes.HUSK, EntityTypes.IRON_GOLEM, EntityTypes.LLAMA, EntityTypes.MOOSHROOM,
        EntityTypes.NAUTILUS, EntityTypes.OCELOT, EntityTypes.PANDA, EntityTypes.PARCHED, EntityTypes.PARROT,
        EntityTypes.PHANTOM, EntityTypes.PIG, EntityTypes.PILLAGER, EntityTypes.POLAR_BEAR, EntityTypes.PUFFERFISH,
        EntityTypes.RABBIT, EntityTypes.SALMON, EntityTypes.SHEEP, EntityTypes.SILVERFISH, EntityTypes.SKELETON,
        EntityTypes.SKELETON_HORSE, EntityTypes.SLIME, EntityTypes.SPIDER, EntityTypes.SQUID, EntityTypes.STRAY,
        EntityTypes.SULFUR_CUBE, EntityTypes.TRADER_LLAMA, EntityTypes.TROPICAL_FISH, EntityTypes.TURTLE,
        EntityTypes.VEX, EntityTypes.VILLAGER, EntityTypes.VINDICATOR, EntityTypes.WANDERING_TRADER,
        EntityTypes.WARDEN, EntityTypes.WITCH, EntityTypes.WOLF, EntityTypes.ZOMBIE, EntityTypes.ZOMBIE_HORSE,
        EntityTypes.ZOMBIE_NAUTILUS, EntityTypes.ZOMBIE_VILLAGER, EntityTypes.ZOMBIFIED_PIGLIN);
    private static final Set<EntityType<?>> NETHER = Set.of(EntityTypes.BLAZE, EntityTypes.CHICKEN,
        EntityTypes.ENDERMAN, EntityTypes.GHAST, EntityTypes.HOGLIN, EntityTypes.MAGMA_CUBE, EntityTypes.PIGLIN,
        EntityTypes.PIGLIN_BRUTE, EntityTypes.SKELETON, EntityTypes.STRIDER, EntityTypes.WITHER_SKELETON,
        EntityTypes.ZOMBIFIED_PIGLIN);
    private static final Set<EntityType<?>> END = Set.of(EntityTypes.ENDER_DRAGON, EntityTypes.ENDERMAN,
        EntityTypes.SHULKER);
    private static final Map<ResourceKey<Level>, Set<EntityType<?>>> HOMES = Map.of(Level.OVERWORLD, OVERWORLD,
        Level.NETHER, NETHER, Level.END, END);

    private MobHomes() {
    }

    // A dimension the plain game does not have is never judged.
    public static boolean lost(EntityType<?> type, ResourceKey<Level> dimension) {
        Set<EntityType<?>> here = HOMES.get(dimension);
        return here != null && !here.contains(type)
            && (OVERWORLD.contains(type) || NETHER.contains(type) || END.contains(type));
    }
}
