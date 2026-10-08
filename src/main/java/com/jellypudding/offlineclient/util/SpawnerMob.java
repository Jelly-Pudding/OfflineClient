package com.jellypudding.offlineclient.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;


// The mob a spawner makes as the server sent it with the spawner's block entity.
public final class SpawnerMob {

    private SpawnerMob() {
    }

    // Null for any other block entity and for a spawner that names no mob.
    public static EntityType<?> of(BlockEntity blockEntity) {
        return blockEntity instanceof SpawnerBlockEntity spawner ? of(spawner.getSpawner().nextSpawnData) : null;
    }

    // The mob the spawn data names. Null for none and for a mob the game does not know.
    public static EntityType<?> of(SpawnData data) {
        if (data == null) {
            return null;
        }
        Identifier id = Identifier.tryParse(data.entityToSpawn().getStringOr("id", ""));
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    // Such as zombie or cave spider. Empty for no mob.
    public static String name(EntityType<?> mob) {
        return mob == null ? "" : ChatUtil.words(mob);
    }
}
