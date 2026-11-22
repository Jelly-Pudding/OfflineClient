package com.jellypudding.offlineclient.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

// The structure that made a spawner and what that structure makes round it. A spawner's mob and
// dimension tell which structure made it. Placed stands for a mob no structure spawns in that
// dimension. A player set that spawner down or changed its mob with an egg.
public enum SpawnerPlace {
    DUNGEON("Dungeon", "in a dungeon", Level.OVERWORLD),
    MINESHAFT("Mineshaft", "in a mineshaft", Level.OVERWORLD),
    STRONGHOLD("Stronghold", "in a stronghold", Level.OVERWORLD),
    FORTRESS("Fortress", "in a fortress", Level.NETHER),
    BASTION("Bastion", "in a bastion", Level.NETHER),
    MANSION("Mansion", "in a mansion", Level.OVERWORLD),
    TRIAL_CHAMBER("Trial chamber", "in a trial chamber", Level.OVERWORLD),
    PLACED("Placed or changed", "a player placed or changed", null);

    // BaseSpawner starts every spawner at twenty. The templates save theirs at nought.
    private static final int FRESH_COUNT = 20;
    private static final int TEMPLATE_COUNT = 0;

    // Containers that many Overworld structures make. They say nothing about a player near a
    // spawner. Trial chambers make barrels dispensers pots and vaults. Villages camps ancient
    // cities igloos trail ruins and jungle temples make the rest.
    private static final Set<BlockEntityType<?>> OVERWORLD_MADE = Set.of(BlockEntityTypes.BARREL,
        BlockEntityTypes.DISPENSER, BlockEntityTypes.DECORATED_POT, BlockEntityTypes.VAULT,
        BlockEntityTypes.FURNACE, BlockEntityTypes.SMOKER, BlockEntityTypes.BLAST_FURNACE,
        BlockEntityTypes.BREWING_STAND, BlockEntityTypes.CAMPFIRE, BlockEntityTypes.LECTERN);

    // Lights the caves grow or leave by themselves. Buried ruined portals keep crying obsidian.
    private static final Set<Block> CAVE_LIGHTS = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE,
        Blocks.MAGMA_BLOCK, Blocks.GLOW_LICHEN, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT,
        Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD,
        Blocks.SMALL_AMETHYST_BUD, Blocks.SCULK_SENSOR, Blocks.SCULK_CATALYST, Blocks.REDSTONE_ORE,
        Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.BROWN_MUSHROOM, Blocks.SEA_PICKLE, Blocks.CRYING_OBSIDIAN);

    // What the wall torches of mineshafts and strongholds hang on. Mineshaft beams are oak or
    // dark oak planks. Stronghold walls are stone bricks of every kind and one crossing hangs
    // its torch on a double smooth stone slab. The library and the storeroom use oak planks.
    private static final Set<Block> TORCH_SUPPORTS = Set.of(Blocks.OAK_PLANKS, Blocks.DARK_OAK_PLANKS,
        Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS,
        Blocks.SMOOTH_STONE_SLAB);

    // Light keeps these away from a spawner. Blazes silverfish and magma cubes spawn in any light.
    private static final Set<EntityType<?>> LIGHT_SHY = Set.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON,
        EntityTypes.SPIDER, EntityTypes.CAVE_SPIDER);

    private final String label;
    private final String where;
    private final ResourceKey<Level> home;

    SpawnerPlace(String label, String where, ResourceKey<Level> home) {
        this.label = label;
        this.where = where;
        this.home = home;
    }

    public String label() {
        return label;
    }

    // The words after a spawner such as in a dungeon.
    public String where() {
        return where;
    }

    public static SpawnerPlace of(EntityType<?> mob, ResourceKey<Level> dimension, BlockState below) {
        SpawnerPlace place = byMob(mob, below);
        return place.belongsIn(dimension) ? place : PLACED;
    }

    // The mansion spider spawner stands on birch planks. A dungeon floor is cobblestone and a
    // stronghold library that cuts through one is oak.
    private static SpawnerPlace byMob(EntityType<?> mob, BlockState below) {
        if (mob == EntityTypes.ZOMBIE || mob == EntityTypes.SKELETON) {
            return DUNGEON;
        }
        if (mob == EntityTypes.SPIDER) {
            return below.is(Blocks.BIRCH_PLANKS) ? MANSION : DUNGEON;
        }
        if (mob == EntityTypes.CAVE_SPIDER) {
            return MINESHAFT;
        }
        if (mob == EntityTypes.SILVERFISH) {
            return STRONGHOLD;
        }
        if (mob == EntityTypes.BLAZE) {
            return FORTRESS;
        }
        return mob == EntityTypes.MAGMA_CUBE ? BASTION : PLACED;
    }

    // A vanilla dimension only holds its own structures. Another dimension is taken on trust.
    private boolean belongsIn(ResourceKey<Level> dimension) {
        boolean vanilla = dimension.equals(Level.OVERWORLD) || dimension.equals(Level.NETHER)
            || dimension.equals(Level.END);
        return home == null || !vanilla || dimension.equals(home);
    }

    public boolean hasRoom() {
        return this == DUNGEON || this == MINESHAFT || this == STRONGHOLD;
    }

    public int untouchedCount() {
        return this == BASTION || this == MANSION ? TEMPLATE_COUNT : FRESH_COUNT;
    }

    // True for a container this place or a structure that often meets it makes by itself.
    // Every structure with loot makes single chests. Double chests stand in the bastion and
    // mansion templates and in the tents of abandoned camps. A camp is built on the surface
    // and seldom lies within reach of a spawner. Its chests are counted. The Nether makes no
    // other container. Trapped chests stand only in mansions and hoppers only in trial
    // chambers.
    public boolean makes(BlockEntityType<?> type, boolean doubleChest) {
        if (type == BlockEntityTypes.CHEST) {
            return !doubleChest || this == BASTION || this == MANSION;
        }
        if (home == Level.NETHER) {
            return false;
        }
        if (type == BlockEntityTypes.TRAPPED_CHEST) {
            return this == MANSION;
        }
        if (type == BlockEntityTypes.HOPPER) {
            return this == TRIAL_CHAMBER;
        }
        return OVERWORLD_MADE.contains(type);
    }

    // Mineshafts cross every other place underground and carry chest minecarts.
    public boolean makesCart(EntityType<?> type) {
        return type == EntityTypes.CHEST_MINECART && home != Level.NETHER;
    }

    // Mineshaft beams and stronghold walls carry wall torches of their own.
    public boolean makesLight(SeenBlocks world, BlockPos pos, BlockState state) {
        if (CAVE_LIGHTS.contains(state.getBlock())) {
            return true;
        }
        if (home != Level.OVERWORLD || !state.is(Blocks.WALL_TORCH)) {
            return false;
        }
        BlockPos support = pos.relative(state.getValue(WallTorchBlock.FACING).getOpposite());
        return TORCH_SUPPORTS.contains(world.at(support.getX(), support.getY(), support.getZ()).getBlock());
    }

    public static boolean lightShy(EntityType<?> mob) {
        return LIGHT_SHY.contains(mob);
    }
}
