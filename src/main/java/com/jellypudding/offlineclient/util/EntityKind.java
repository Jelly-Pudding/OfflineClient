package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.rabbit.Rabbit;
import net.minecraft.world.entity.monster.cubemob.Slime;

import java.util.Set;

// The broad families the filters and the colours work with. Hostile hunts the player and
// neutral only fights back. Water is peaceful sea life and ambient is bats. Passive is
// every other mob and other is whatever is not alive.
public enum EntityKind {
    PLAYER, HOSTILE, NEUTRAL, PASSIVE, WATER, AMBIENT, ITEM, OTHER;

    private static final Set<EntityType<?>> NEUTRAL_MOBS = Set.of(EntityTypes.BEE, EntityTypes.ENDERMAN,
        EntityTypes.GOAT, EntityTypes.IRON_GOLEM, EntityTypes.NAUTILUS, EntityTypes.PIGLIN,
        EntityTypes.POLAR_BEAR, EntityTypes.PUFFERFISH, EntityTypes.WOLF, EntityTypes.ZOMBIE_NAUTILUS,
        EntityTypes.ZOMBIFIED_PIGLIN);

    // The game spawns these with the monsters yet they never attack.
    private static final Set<EntityType<?>> HARMLESS_MONSTERS = Set.of(EntityTypes.CAMEL_HUSK,
        EntityTypes.SULFUR_CUBE, EntityTypes.ZOMBIE_HORSE);

    // Sea life the game spawns with the land animals.
    private static final Set<EntityType<?>> LAND_SPAWNED_SEA_LIFE = Set.of(EntityTypes.TURTLE,
        EntityTypes.TADPOLE);

    // A golem a player built and a tiny slime never hurt a player. The killer rabbit always does.
    public static EntityKind of(Entity entity) {
        return switch (entity) {
            case IronGolem golem when golem.isPlayerCreated() -> PASSIVE;
            case Rabbit rabbit when rabbit.getVariant() == Rabbit.Variant.EVIL -> HOSTILE;
            case Slime slime when slime.isTiny() -> PASSIVE;
            default -> of(entity.getType());
        };
    }

    // A mannequin stands in for a player.
    public static EntityKind of(EntityType<?> type) {
        if (type == EntityTypes.PLAYER || type == EntityTypes.MANNEQUIN) {
            return PLAYER;
        }
        if (type == EntityTypes.ITEM) {
            return ITEM;
        }
        if (NEUTRAL_MOBS.contains(type)) {
            return NEUTRAL;
        }
        return switch (type.getCategory()) {
            case MONSTER -> HARMLESS_MONSTERS.contains(type) ? PASSIVE : HOSTILE;
            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS -> WATER;
            case AMBIENT -> AMBIENT;
            case CREATURE -> LAND_SPAWNED_SEA_LIFE.contains(type) ? WATER : PASSIVE;
            // Villagers and golems sit here beside boats and arrows. Only a mob has attributes.
            case MISC -> DefaultAttributes.hasSupplier(type) && type != EntityTypes.ARMOR_STAND
                ? PASSIVE : OTHER;
        };
    }
}
