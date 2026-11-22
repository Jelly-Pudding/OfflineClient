package com.jellypudding.offlineclient.worldgen;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

// One dimension of the world the plain game makes from a seed. The game's own generator answers
// with no server behind it. A server that runs a data pack or a generator of its own makes a
// different world and nothing here can tell.
public final class SeedWorld {

    private final RegistryAccess registries;
    private final ChunkGenerator generator;
    private final NoiseGeneratorSettings settings;
    private final RandomState random;
    private final ChunkGeneratorStructureState structures;
    private final LevelHeightAccessor height;
    private final long seed;

    // Every dimension of the plain game is made from noise.
    SeedWorld(RegistryAccess registries, LevelStem stem, long seed) {
        this.registries = registries;
        this.generator = stem.generator();
        this.settings = ((NoiseBasedChunkGenerator) generator).generatorSettings().value();
        this.random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
        this.structures = generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), random, seed);
        DimensionType type = stem.type().value();
        this.height = LevelHeightAccessor.create(type.minY(), type.height());
        this.seed = seed;
    }

    public long seed() {
        return seed;
    }

    public RegistryAccess registries() {
        return registries;
    }

    public ChunkGenerator generator() {
        return generator;
    }

    public NoiseGeneratorSettings settings() {
        return settings;
    }

    public RandomState random() {
        return random;
    }

    // The stronghold rings are worked out on the first ask.
    public ChunkGeneratorStructureState structures() {
        return structures;
    }

    public LevelHeightAccessor height() {
        return height;
    }

    // A reader keeps what it sampled last and belongs to the thread that asked for it.
    public BiomeResolver biomes() {
        return generator.getBiomeSource().createCachingResolver(random);
    }

    // Belongs to one thread like a biome reader.
    public Climate.Sampler climate() {
        return random.createClimateSampler(samplerContext());
    }

    // One thread at a time.
    public DensitySamplerSet samplers() {
        return random.samplersWithContext(samplerContext());
    }

    // One above the highest block of the bare terrain at a column.
    public int surface(int x, int z) {
        return generator.getFirstFreeHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
    }

    private static SamplerContext samplerContext() {
        return SamplerContext.builder().enableCaches().build();
    }
}
