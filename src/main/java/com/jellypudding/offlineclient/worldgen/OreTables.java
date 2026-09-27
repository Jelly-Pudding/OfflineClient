package com.jellypudding.offlineclient.worldgen;

import com.jellypudding.offlineclient.worldgen.PlacedOre.Attempts;
import com.jellypudding.offlineclient.worldgen.PlacedOre.Height;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.HeightMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// The ore features of the 26.3 data pack in the order the decoration step runs them. The
// numbers come from placed_feature and feature in the game jar. The indices are where
// FeatureSorter puts each feature among every feature of its step on a vanilla server.
public final class OreTables {

    // The ground a dimension builds in and the ores its decoration step places.
    public record Table(int minBuildY, int maxBuildY, boolean nether, List<PlacedOre> ores) {

        public boolean outsideBuildHeight(int y) {
            return y < minBuildY || y >= maxBuildY;
        }
    }

    private static final int UNDERGROUND_ORES = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
    private static final int UNDERGROUND_DECORATION = GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal();

    // Generation bounds that above_bottom and below_top anchors count from.
    private static final int OVERWORLD_BOTTOM = -64;
    private static final int OVERWORLD_TOP = 319;
    private static final int NETHER_BOTTOM = 0;
    private static final int NETHER_TOP = 127;

    // Tuff takes the stone form of an ore from height nought up and the deepslate form
    // up to height eight.
    private static final RuleTest TUFF = new TagMatchTest(BlockTags.HEIGHT_SPECIFIC_ORE_REPLACEABLES);
    private static final RuleTest STONE_FORM = RuleTest.anyOf(
        RuleTest.allOf(TUFF, new HeightMatchTest(0, 2031)),
        RuleTest.allOf(RuleTest.not(TUFF), new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES)));
    private static final RuleTest DEEPSLATE_FORM = RuleTest.anyOf(
        RuleTest.allOf(TUFF, new HeightMatchTest(-2032, 8)),
        RuleTest.allOf(RuleTest.not(TUFF), new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES)));
    private static final RuleTest NETHERRACK = new BlockMatchTest(Blocks.NETHERRACK);
    private static final RuleTest NETHER_STONE = new TagMatchTest(BlockTags.BASE_STONE_NETHER);

    private static final Predicate<ResourceKey<Biome>> EVERY_BIOME = biome -> true;
    private static final Predicate<ResourceKey<Biome>> MOUNTAINS = Set.of(Biomes.CHERRY_GROVE,
        Biomes.FROZEN_PEAKS, Biomes.GROVE, Biomes.JAGGED_PEAKS, Biomes.MEADOW, Biomes.SNOWY_SLOPES,
        Biomes.STONY_PEAKS, Biomes.WINDSWEPT_FOREST, Biomes.WINDSWEPT_GRAVELLY_HILLS,
        Biomes.WINDSWEPT_HILLS)::contains;
    private static final Predicate<ResourceKey<Biome>> BADLANDS = Set.of(Biomes.BADLANDS,
        Biomes.ERODED_BADLANDS, Biomes.WOODED_BADLANDS)::contains;
    private static final Predicate<ResourceKey<Biome>> DRIPSTONE_CAVES = Biomes.DRIPSTONE_CAVES::equals;
    private static final Predicate<ResourceKey<Biome>> BASALT_DELTAS = Biomes.BASALT_DELTAS::equals;

    public static final Table OVERWORLD = new Table(-64, 320, false, List.of(
        overworld("ore_coal_upper", 9, count(30), Height.uniform(136, OVERWORLD_TOP),
            blob(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 17, 0), EVERY_BIOME, OreKind.COAL),
        overworld("ore_coal_lower", 10, count(20), Height.trapezoid(0, 192),
            blob(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 17, 0.5F), EVERY_BIOME, OreKind.COAL),
        overworld("ore_iron_upper", 11, count(90), Height.trapezoid(80, 384),
            blob(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 9, 0), EVERY_BIOME, OreKind.IRON),
        overworld("ore_iron_middle", 12, count(10), Height.trapezoid(-24, 56),
            blob(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 9, 0), EVERY_BIOME, OreKind.IRON),
        overworld("ore_iron_small", 13, count(10), Height.uniform(OVERWORLD_BOTTOM, 72),
            blob(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 4, 0), EVERY_BIOME, OreKind.IRON),
        overworld("ore_gold", 14, count(4), Height.trapezoid(-64, 32),
            blob(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 9, 0.5F), EVERY_BIOME, OreKind.GOLD),
        overworld("ore_gold_lower", 15, Attempts.count(UniformInt.of(0, 1)), Height.uniform(-64, -48),
            blob(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 9, 0.5F), EVERY_BIOME, OreKind.GOLD),
        overworld("ore_redstone", 16, count(4), Height.uniform(OVERWORLD_BOTTOM, 15),
            blob(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 0), EVERY_BIOME, OreKind.REDSTONE),
        overworld("ore_redstone_lower", 17, count(8),
            Height.trapezoid(OVERWORLD_BOTTOM - 32, OVERWORLD_BOTTOM + 32),
            blob(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 0), EVERY_BIOME, OreKind.REDSTONE),
        overworld("ore_diamond", 18, count(7), Height.trapezoid(OVERWORLD_BOTTOM - 80, OVERWORLD_BOTTOM + 80),
            blob(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 4, 0.5F), EVERY_BIOME, OreKind.DIAMOND),
        overworld("ore_diamond_medium", 19, count(2), Height.uniform(-64, -4),
            blob(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 8, 0.5F), EVERY_BIOME, OreKind.DIAMOND),
        overworld("ore_diamond_large", 20, Attempts.rarity(9),
            Height.trapezoid(OVERWORLD_BOTTOM - 80, OVERWORLD_BOTTOM + 80),
            blob(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 12, 0.7F), EVERY_BIOME, OreKind.DIAMOND),
        overworld("ore_diamond_buried", 21, count(4),
            Height.trapezoid(OVERWORLD_BOTTOM - 80, OVERWORLD_BOTTOM + 80),
            blob(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 8, 1), EVERY_BIOME, OreKind.DIAMOND),
        overworld("ore_lapis", 22, count(2), Height.trapezoid(-32, 32),
            blob(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 7, 0), EVERY_BIOME, OreKind.LAPIS),
        overworld("ore_lapis_buried", 23, count(4), Height.uniform(OVERWORLD_BOTTOM, 64),
            blob(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 7, 1), EVERY_BIOME, OreKind.LAPIS),
        overworld("ore_copper_large", 24, count(16), Height.trapezoid(-16, 112),
            blob(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 20, 0), DRIPSTONE_CAVES, OreKind.COPPER),
        overworld("ore_copper", 25, count(16), Height.trapezoid(-16, 112),
            blob(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 10, 0), DRIPSTONE_CAVES.negate(), OreKind.COPPER),
        overworld("ore_gold_extra", 28, count(50), Height.uniform(32, 256),
            blob(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 9, 0), BADLANDS, OreKind.GOLD),
        overworld("ore_emerald", 33, count(100), Height.trapezoid(-16, 480),
            blob(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 3, 0), MOUNTAINS, OreKind.EMERALD)));

    // Magma is hidden by anti xray like an ore and it takes netherrack the later ores want.
    public static final Table NETHER = new Table(0, 256, true, List.of(
        nether("ore_magma", 11, count(4), Height.uniform(27, 36),
            netherBlob(Blocks.MAGMA_BLOCK, 33), EVERY_BIOME, null),
        nether("ore_gold_deltas", 13, count(20), Height.uniform(NETHER_BOTTOM + 10, NETHER_TOP - 10),
            netherBlob(Blocks.NETHER_GOLD_ORE, 10), BASALT_DELTAS, OreKind.NETHER_GOLD),
        nether("ore_quartz_deltas", 14, count(32), Height.uniform(NETHER_BOTTOM + 10, NETHER_TOP - 10),
            netherBlob(Blocks.NETHER_QUARTZ_ORE, 14), BASALT_DELTAS, OreKind.QUARTZ),
        nether("ore_gold_nether", 19, count(10), Height.uniform(NETHER_BOTTOM + 10, NETHER_TOP - 10),
            netherBlob(Blocks.NETHER_GOLD_ORE, 10), BASALT_DELTAS.negate(), OreKind.NETHER_GOLD),
        nether("ore_quartz_nether", 20, count(16), Height.uniform(NETHER_BOTTOM + 10, NETHER_TOP - 10),
            netherBlob(Blocks.NETHER_QUARTZ_ORE, 14), BASALT_DELTAS.negate(), OreKind.QUARTZ),
        nether("ore_ancient_debris_large", 21, Attempts.ONCE, Height.trapezoid(8, 24),
            debris(3), EVERY_BIOME, OreKind.ANCIENT_DEBRIS),
        nether("ore_debris_small", 22, Attempts.ONCE, Height.uniform(NETHER_BOTTOM + 8, NETHER_TOP - 8),
            debris(2), EVERY_BIOME, OreKind.ANCIENT_DEBRIS)));

    private static final Map<Block, OreKind> KINDS = kinds();

    private OreTables() {
    }

    // The ore kind a block is. Null for any other block.
    public static OreKind kindOf(Block block) {
        return KINDS.get(block);
    }

    private static Map<Block, OreKind> kinds() {
        Map<Block, OreKind> kinds = new HashMap<>();
        for (Table table : List.of(OVERWORLD, NETHER)) {
            for (PlacedOre ore : table.ores()) {
                if (ore.kind() == null) {
                    continue;
                }
                for (BlockReplacement target : ore.feature().targetStates()) {
                    kinds.put(target.state().getBlock(), ore.kind());
                }
            }
        }
        return Map.copyOf(kinds);
    }

    // Null in a dimension with no ores such as the End.
    public static Table of(ResourceKey<Level> dimension) {
        if (dimension == Level.OVERWORLD) {
            return OVERWORLD;
        }
        return dimension == Level.NETHER ? NETHER : null;
    }

    private static PlacedOre overworld(String name, int index, Attempts attempts, Height height,
                                       OreFeature feature, Predicate<ResourceKey<Biome>> biomes, OreKind kind) {
        return new PlacedOre(name, UNDERGROUND_ORES, index, attempts, height, feature, biomes, kind);
    }

    private static PlacedOre nether(String name, int index, Attempts attempts, Height height,
                                    AbstractOreFeature feature,
                                    Predicate<ResourceKey<Biome>> biomes, OreKind kind) {
        return new PlacedOre(name, UNDERGROUND_DECORATION, index, attempts, height, feature, biomes, kind);
    }

    private static Attempts count(int count) {
        return Attempts.count(ConstantInt.of(count));
    }

    private static OreFeature blob(Block stoneForm, Block deepslateForm, int size, float discardNextToAir) {
        return new OreFeature(List.of(
            new BlockReplacement(STONE_FORM, stoneForm.defaultBlockState()),
            new BlockReplacement(DEEPSLATE_FORM, deepslateForm.defaultBlockState())), size, discardNextToAir);
    }

    private static OreFeature netherBlob(Block ore, int size) {
        return new OreFeature(List.of(new BlockReplacement(NETHERRACK, ore.defaultBlockState())), size, 0);
    }

    // Ancient debris never touches air.
    private static ScatteredOreFeature debris(int size) {
        return new ScatteredOreFeature(List.of(
            new BlockReplacement(NETHER_STONE, Blocks.ANCIENT_DEBRIS.defaultBlockState())), size, 1);
    }
}
