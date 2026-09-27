package com.jellypudding.offlineclient.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;

import java.util.function.Predicate;

// One placed ore feature of the data pack. The feature index inside its decoration step
// feeds the random seed the feature is placed with. Kind is null for a feature that only
// shapes the ground for the ores after it.
public record PlacedOre(String name, int step, int index, Attempts attempts, Height height,
                        AbstractOreFeature feature, Predicate<ResourceKey<Biome>> biomes, OreKind kind) {

    // The first placement modifier. It says how many spots the feature tries in a chunk.
    @FunctionalInterface
    public interface Attempts {

        Attempts ONCE = random -> 1;

        int roll(RandomSource random);

        static Attempts count(IntProvider count) {
            return count::sample;
        }

        // RarityFilter keeps its one spot in one chunk out of every chance chunks.
        static Attempts rarity(int chance) {
            return random -> random.nextFloat() < 1.0F / chance ? 1 : 0;
        }
    }

    // The height range modifier with its anchors already resolved.
    @FunctionalInterface
    public interface Height {

        // TrapezoidHeight in the data pack never sets a flat top.
        int PLATEAU = 0;

        int sample(RandomSource random);

        static Height uniform(int min, int max) {
            return random -> min > max ? min : Mth.randomBetweenInclusive(random, min, max);
        }

        static Height trapezoid(int min, int max) {
            return random -> {
                if (min > max) {
                    return min;
                }
                int range = max - min;
                if (PLATEAU >= range) {
                    return Mth.randomBetweenInclusive(random, min, max);
                }
                int low = (range - PLATEAU) / 2;
                int high = range - low;
                return min + Mth.randomBetweenInclusive(random, 0, high) + Mth.randomBetweenInclusive(random, 0, low);
            };
        }
    }

    public boolean scattered() {
        return feature instanceof ScatteredOreFeature;
    }
}
