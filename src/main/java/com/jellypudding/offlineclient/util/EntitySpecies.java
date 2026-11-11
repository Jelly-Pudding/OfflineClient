package com.jellypudding.offlineclient.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.fish.Pufferfish;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

// The narrow families the per species switches work with. A shulker is a golem
// to the game and is picked out first.
public enum EntitySpecies {
    WATER, BAT, SLIME, SHULKER, VILLAGER, ZOMBIE_VILLAGER, GOLEM, ALLAY, OTHER;

    public static EntitySpecies of(Entity entity) {
        if (entity instanceof ZombieVillager) {
            return ZOMBIE_VILLAGER;
        }
        if (entity instanceof AbstractVillager) {
            return VILLAGER;
        }
        if (entity instanceof Slime || entity instanceof MagmaCube) {
            return SLIME;
        }
        if (entity instanceof Shulker) {
            return SHULKER;
        }
        if (entity instanceof AbstractGolem) {
            return GOLEM;
        }
        if (entity instanceof Allay) {
            return ALLAY;
        }
        // A fish to the water switch whatever its temper.
        if (entity instanceof Pufferfish) {
            return WATER;
        }
        return switch (EntityKind.of(entity)) {
            case WATER -> WATER;
            case AMBIENT -> BAT;
            default -> OTHER;
        };
    }
}
