package com.jellypudding.offlineclient.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ColorCollection;
import net.minecraft.world.level.block.WeatheringCopperCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The groups of blocks BaseFinder counts. The world never puts down any of them by itself and
// PlacedOnly reads every group as one. Each block was checked against every structure template
// and jigsaw block and worldgen feature and generator piece of 26.3 and against weathering and
// lightning and what endermen carry. The colours and copper stages a structure does use are named
// where they are left out.
public final class BaseBlocks {

    // Valuables and trophies. One of them already shows a player.
    public static final List<Block> RARE = List.of(Blocks.NETHERITE_BLOCK, Blocks.EMERALD_BLOCK,
        Blocks.IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.HEAVY_CORE, Blocks.SPONGE, Blocks.OCHRE_FROGLIGHT,
        Blocks.VERDANT_FROGLIGHT, Blocks.PEARLESCENT_FROGLIGHT, Blocks.DRAGON_HEAD, Blocks.PLAYER_HEAD,
        Blocks.PLAYER_WALL_HEAD, Blocks.CREEPER_HEAD, Blocks.CREEPER_WALL_HEAD, Blocks.ZOMBIE_HEAD,
        Blocks.ZOMBIE_WALL_HEAD, Blocks.PIGLIN_HEAD, Blocks.PIGLIN_WALL_HEAD, Blocks.WITHER_SKELETON_SKULL,
        Blocks.WITHER_SKELETON_WALL_SKULL, Blocks.SKELETON_WALL_SKULL,
        // A sniffer egg is only ever an item until a player puts it down.
        Blocks.SNIFFER_EGG);

    // Blocks players stop at to use. Trees grow bee nests and never beehives.
    public static final List<Block> WORKSTATIONS = List.of(Blocks.ENCHANTING_TABLE, Blocks.ANVIL,
        Blocks.CHIPPED_ANVIL, Blocks.BEACON, Blocks.CONDUIT, Blocks.LODESTONE, Blocks.RESPAWN_ANCHOR,
        Blocks.JUKEBOX, Blocks.BEEHIVE);

    public static final List<Block> STORAGE = storage();

    public static final List<Block> FURNISHING = furnishing();

    public static final List<Block> REDSTONE = redstone();

    public static final List<Block> BUILDING = building();

    public static final List<List<Block>> GROUPS = List.of(RARE, WORKSTATIONS, STORAGE, FURNISHING, REDSTONE,
        BUILDING);

    // What a structure with a spawner always leaves around it. Dungeons are cobblestone.
    // Mineshafts hold cobwebs and rails and their supports. The stronghold portal room is stone
    // bricks and bars and the mansion room birch planks. Fortresses are nether bricks and the
    // bastion treasure room blackstone. The End has no spawners of its own.
    private static final Map<ResourceKey<Level>, Set<Block>> COMPANIONS = Map.of(
        Level.OVERWORLD, Set.of(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBWEB, Blocks.RAIL,
            Blocks.IRON_CHAIN, Blocks.OAK_PLANKS, Blocks.OAK_FENCE, Blocks.OAK_LOG, Blocks.DARK_OAK_PLANKS,
            Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_LOG, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
            Blocks.CRACKED_STONE_BRICKS, Blocks.INFESTED_STONE_BRICKS, Blocks.STONE_BRICK_STAIRS,
            Blocks.IRON_BARS, Blocks.END_PORTAL_FRAME, Blocks.BIRCH_PLANKS),
        Level.NETHER, Set.of(Blocks.NETHER_BRICKS, Blocks.NETHER_BRICK_FENCE, Blocks.NETHER_BRICK_STAIRS,
            Blocks.BLACKSTONE, Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_BRICKS,
            Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.GILDED_BLACKSTONE, Blocks.IRON_CHAIN));

    private BaseBlocks() {
    }

    // The blocks that show a spawner stands where its structure put it. Empty where no
    // structure makes spawners.
    public static Set<Block> spawnerCompanions(ResourceKey<Level> dimension) {
        return COMPANIONS.getOrDefault(dimension, Set.of());
    }

    private static List<Block> storage() {
        List<Block> blocks = new ArrayList<>(List.of(Blocks.SHULKER_BOX, Blocks.CHISELED_BOOKSHELF,
            Blocks.OAK_SHELF, Blocks.SPRUCE_SHELF, Blocks.BIRCH_SHELF, Blocks.JUNGLE_SHELF, Blocks.ACACIA_SHELF,
            Blocks.DARK_OAK_SHELF, Blocks.MANGROVE_SHELF, Blocks.CHERRY_SHELF, Blocks.PALE_OAK_SHELF,
            Blocks.POPLAR_SHELF, Blocks.BAMBOO_SHELF, Blocks.CRIMSON_SHELF, Blocks.WARPED_SHELF));
        blocks.addAll(Blocks.DYED_SHULKER_BOX.asList());
        // Abandoned camps leave oxidized copper chests and lightning scrubs them back.
        Blocks.COPPER_CHEST.waxed().forEach(blocks::add);
        return List.copyOf(blocks);
    }

    private static List<Block> furnishing() {
        List<Block> blocks = new ArrayList<>(List.of(Blocks.OAK_SIGN, Blocks.SPRUCE_SIGN, Blocks.BIRCH_SIGN,
            Blocks.JUNGLE_SIGN, Blocks.ACACIA_SIGN, Blocks.DARK_OAK_SIGN, Blocks.MANGROVE_SIGN, Blocks.CHERRY_SIGN,
            Blocks.PALE_OAK_SIGN, Blocks.POPLAR_SIGN, Blocks.BAMBOO_SIGN, Blocks.CRIMSON_SIGN, Blocks.WARPED_SIGN,
            // Igloos hang an oak sign and taiga villages a spruce one.
            Blocks.BIRCH_WALL_SIGN, Blocks.JUNGLE_WALL_SIGN, Blocks.ACACIA_WALL_SIGN, Blocks.DARK_OAK_WALL_SIGN,
            Blocks.MANGROVE_WALL_SIGN, Blocks.CHERRY_WALL_SIGN, Blocks.PALE_OAK_WALL_SIGN,
            Blocks.POPLAR_WALL_SIGN, Blocks.BAMBOO_WALL_SIGN, Blocks.CRIMSON_WALL_SIGN, Blocks.WARPED_WALL_SIGN,
            Blocks.OAK_HANGING_SIGN, Blocks.SPRUCE_HANGING_SIGN, Blocks.BIRCH_HANGING_SIGN,
            Blocks.JUNGLE_HANGING_SIGN, Blocks.ACACIA_HANGING_SIGN, Blocks.DARK_OAK_HANGING_SIGN,
            Blocks.MANGROVE_HANGING_SIGN, Blocks.CHERRY_HANGING_SIGN, Blocks.PALE_OAK_HANGING_SIGN,
            Blocks.POPLAR_HANGING_SIGN, Blocks.BAMBOO_HANGING_SIGN, Blocks.CRIMSON_HANGING_SIGN,
            Blocks.WARPED_HANGING_SIGN, Blocks.OAK_WALL_HANGING_SIGN, Blocks.SPRUCE_WALL_HANGING_SIGN,
            Blocks.BIRCH_WALL_HANGING_SIGN, Blocks.JUNGLE_WALL_HANGING_SIGN, Blocks.ACACIA_WALL_HANGING_SIGN,
            Blocks.DARK_OAK_WALL_HANGING_SIGN, Blocks.MANGROVE_WALL_HANGING_SIGN, Blocks.CHERRY_WALL_HANGING_SIGN,
            Blocks.PALE_OAK_WALL_HANGING_SIGN, Blocks.POPLAR_WALL_HANGING_SIGN, Blocks.BAMBOO_WALL_HANGING_SIGN,
            Blocks.CRIMSON_WALL_HANGING_SIGN, Blocks.WARPED_WALL_HANGING_SIGN,
            Blocks.CAKE, Blocks.CANDLE_CAKE, Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH, Blocks.SOUL_CAMPFIRE,
            Blocks.COPPER_TORCH, Blocks.COPPER_WALL_TORCH, Blocks.RESIN_CLUMP,
            Blocks.POTTED_OAK_SAPLING, Blocks.POTTED_JUNGLE_SAPLING, Blocks.POTTED_ACACIA_SAPLING,
            Blocks.POTTED_DARK_OAK_SAPLING, Blocks.POTTED_CHERRY_SAPLING, Blocks.POTTED_PALE_OAK_SAPLING,
            Blocks.POTTED_POPLAR_SAPLING, Blocks.POTTED_MANGROVE_PROPAGULE, Blocks.POTTED_FERN,
            Blocks.POTTED_GOLDEN_DANDELION, Blocks.POTTED_ORANGE_TULIP, Blocks.POTTED_PINK_TULIP,
            Blocks.POTTED_CORNFLOWER, Blocks.POTTED_LILY_OF_THE_VALLEY, Blocks.POTTED_WITHER_ROSE,
            Blocks.POTTED_TORCHFLOWER, Blocks.POTTED_OPEN_EYEBLOSSOM, Blocks.POTTED_CLOSED_EYEBLOSSOM,
            Blocks.POTTED_BROWN_MUSHROOM, Blocks.POTTED_CRIMSON_FUNGUS, Blocks.POTTED_WARPED_FUNGUS,
            Blocks.POTTED_CRIMSON_ROOTS, Blocks.POTTED_WARPED_ROOTS, Blocks.POTTED_AZALEA,
            Blocks.POTTED_FLOWERING_AZALEA, Blocks.POTTED_BAMBOO,
            // Camps and villages and mansions plant jungle and acacia and cherry and dark oak saplings.
            Blocks.OAK_SAPLING, Blocks.SPRUCE_SAPLING, Blocks.BIRCH_SAPLING, Blocks.PALE_OAK_SAPLING,
            Blocks.POPLAR_SAPLING, Blocks.BAMBOO_SAPLING,
            // These only come from the seeds a sniffer digs up.
            Blocks.TORCHFLOWER, Blocks.TORCHFLOWER_CROP, Blocks.PITCHER_PLANT, Blocks.PITCHER_CROP,
            // Only a wither's kills leave wither roses and only a player builds a wither.
            Blocks.WITHER_ROSE));
        blocks.addAll(Blocks.BANNER.asList());
        // Outposts and villages and mansions and end cities hang these banner colours.
        blocks.addAll(allBut(Blocks.WALL_BANNER, Blocks.WALL_BANNER.white(), Blocks.WALL_BANNER.black(),
            Blocks.WALL_BANNER.gray(), Blocks.WALL_BANNER.lightGray(), Blocks.WALL_BANNER.brown(),
            Blocks.WALL_BANNER.magenta()));
        // Ancient cities light white candles and trial chambers red ones.
        blocks.addAll(allBut(Blocks.DYED_CANDLE, Blocks.DYED_CANDLE.white(), Blocks.DYED_CANDLE.red()));
        blocks.addAll(Blocks.DYED_CANDLE_CAKE.asList());
        blocks.addAll(Blocks.COPPER_BARS.asList());
        blocks.addAll(Blocks.COPPER_CHAIN.asList());
        // Abandoned camps leave oxidized lanterns and statues and lightning scrubs them back.
        Blocks.COPPER_LANTERN.waxed().forEach(blocks::add);
        Blocks.COPPER_GOLEM_STATUE.waxed().forEach(blocks::add);
        return List.copyOf(blocks);
    }

    private static List<Block> redstone() {
        List<Block> blocks = new ArrayList<>(List.of(Blocks.OBSERVER, Blocks.PISTON, Blocks.DROPPER,
            Blocks.CRAFTER, Blocks.DAYLIGHT_DETECTOR, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.POWERED_RAIL,
            Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL, Blocks.HONEY_BLOCK, Blocks.SLIME_BLOCK,
            // Structures use oak and jungle and stone buttons and oak and spruce and acacia and
            // stone plates.
            Blocks.SPRUCE_BUTTON, Blocks.BIRCH_BUTTON, Blocks.ACACIA_BUTTON, Blocks.DARK_OAK_BUTTON,
            Blocks.MANGROVE_BUTTON, Blocks.CHERRY_BUTTON, Blocks.PALE_OAK_BUTTON, Blocks.POPLAR_BUTTON,
            Blocks.BAMBOO_BUTTON, Blocks.CRIMSON_BUTTON, Blocks.WARPED_BUTTON, Blocks.POLISHED_BLACKSTONE_BUTTON,
            Blocks.BIRCH_PRESSURE_PLATE, Blocks.JUNGLE_PRESSURE_PLATE, Blocks.DARK_OAK_PRESSURE_PLATE,
            Blocks.MANGROVE_PRESSURE_PLATE, Blocks.CHERRY_PRESSURE_PLATE, Blocks.PALE_OAK_PRESSURE_PLATE,
            Blocks.POPLAR_PRESSURE_PLATE, Blocks.BAMBOO_PRESSURE_PLATE, Blocks.CRIMSON_PRESSURE_PLATE,
            Blocks.WARPED_PRESSURE_PLATE, Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE));
        blocks.addAll(Blocks.LIGHTNING_ROD.asList());
        // Trial chambers use every waxed bulb.
        Blocks.COPPER_BULB.weathering().forEach(blocks::add);
        return List.copyOf(blocks);
    }

    private static List<Block> building() {
        List<Block> blocks = new ArrayList<>(List.of(Blocks.TINTED_GLASS, Blocks.HONEYCOMB_BLOCK,
            Blocks.DRIED_KELP_BLOCK, Blocks.SCAFFOLDING, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_PILLAR,
            Blocks.CHISELED_QUARTZ_BLOCK, Blocks.QUARTZ_SLAB, Blocks.QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_STAIRS,
            Blocks.CUT_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_SLAB, Blocks.RED_SANDSTONE_STAIRS,
            Blocks.RED_SANDSTONE_WALL, Blocks.SMOOTH_RED_SANDSTONE, Blocks.SMOOTH_RED_SANDSTONE_SLAB,
            Blocks.SMOOTH_RED_SANDSTONE_STAIRS, Blocks.CUT_RED_SANDSTONE, Blocks.CUT_RED_SANDSTONE_SLAB,
            Blocks.CHISELED_RED_SANDSTONE, Blocks.ANDESITE_SLAB, Blocks.ANDESITE_STAIRS, Blocks.ANDESITE_WALL,
            Blocks.GRANITE_SLAB, Blocks.POLISHED_ANDESITE_SLAB, Blocks.POLISHED_ANDESITE_STAIRS,
            Blocks.POLISHED_DIORITE_SLAB, Blocks.POLISHED_DIORITE_STAIRS, Blocks.POLISHED_GRANITE_SLAB,
            Blocks.POLISHED_GRANITE_STAIRS, Blocks.TUFF_SLAB, Blocks.TUFF_STAIRS, Blocks.TUFF_WALL,
            Blocks.TUFF_BRICK_SLAB, Blocks.TUFF_BRICK_STAIRS, Blocks.TUFF_BRICK_WALL, Blocks.POLISHED_TUFF_STAIRS,
            Blocks.POLISHED_TUFF_WALL, Blocks.NETHER_BRICK_SLAB, Blocks.NETHER_BRICK_WALL,
            Blocks.CRACKED_NETHER_BRICKS, Blocks.CHISELED_NETHER_BRICKS, Blocks.RED_NETHER_BRICKS,
            Blocks.RED_NETHER_BRICK_SLAB, Blocks.RED_NETHER_BRICK_STAIRS, Blocks.RED_NETHER_BRICK_WALL,
            Blocks.POLISHED_BLACKSTONE_WALL, Blocks.END_STONE_BRICK_SLAB, Blocks.END_STONE_BRICK_STAIRS,
            Blocks.END_STONE_BRICK_WALL, Blocks.PRISMARINE_SLAB, Blocks.PRISMARINE_STAIRS, Blocks.PRISMARINE_WALL,
            Blocks.PRISMARINE_BRICK_SLAB, Blocks.PRISMARINE_BRICK_STAIRS, Blocks.DARK_PRISMARINE_SLAB,
            Blocks.DARK_PRISMARINE_STAIRS, Blocks.RESIN_BRICKS, Blocks.RESIN_BRICK_SLAB, Blocks.RESIN_BRICK_STAIRS,
            Blocks.RESIN_BRICK_WALL, Blocks.CHISELED_RESIN_BRICKS,
            // Sulfur caves make plain sulfur and cinnabar but never their cut forms.
            Blocks.SULFUR_SLAB, Blocks.SULFUR_STAIRS, Blocks.SULFUR_WALL, Blocks.POLISHED_SULFUR,
            Blocks.POLISHED_SULFUR_SLAB, Blocks.POLISHED_SULFUR_STAIRS, Blocks.POLISHED_SULFUR_WALL,
            Blocks.SULFUR_BRICKS, Blocks.SULFUR_BRICK_SLAB, Blocks.SULFUR_BRICK_STAIRS, Blocks.SULFUR_BRICK_WALL,
            Blocks.CHISELED_SULFUR, Blocks.CINNABAR_SLAB, Blocks.CINNABAR_STAIRS, Blocks.CINNABAR_WALL,
            Blocks.POLISHED_CINNABAR, Blocks.POLISHED_CINNABAR_SLAB, Blocks.POLISHED_CINNABAR_STAIRS,
            Blocks.POLISHED_CINNABAR_WALL, Blocks.CINNABAR_BRICKS, Blocks.CINNABAR_BRICK_SLAB,
            Blocks.CINNABAR_BRICK_STAIRS, Blocks.CINNABAR_BRICK_WALL, Blocks.CHISELED_CINNABAR));
        blocks.addAll(woods());
        // Trial chamber reliefs are white and red concrete.
        blocks.addAll(allBut(Blocks.CONCRETE, Blocks.CONCRETE.white(), Blocks.CONCRETE.red()));
        blocks.addAll(Blocks.CONCRETE_POWDER.asList());
        blocks.addAll(Blocks.CONCRETE_SLAB.asList());
        blocks.addAll(Blocks.CONCRETE_STAIRS.asList());
        blocks.addAll(Blocks.WOOL_SLAB.asList());
        // Abandoned camps build white wool stairs.
        blocks.addAll(allBut(Blocks.WOOL_STAIRS, Blocks.WOOL_STAIRS.white()));
        blocks.addAll(List.of(Blocks.WOOL.magenta(), Blocks.WOOL.pink(), Blocks.WOOL.purple()));
        // End cities and trial chambers and trail ruins use these glass colours.
        blocks.addAll(allBut(Blocks.STAINED_GLASS, Blocks.STAINED_GLASS.white(), Blocks.STAINED_GLASS.black(),
            Blocks.STAINED_GLASS.brown(), Blocks.STAINED_GLASS.lightGray(), Blocks.STAINED_GLASS.magenta()));
        blocks.addAll(allBut(Blocks.STAINED_GLASS_PANE, Blocks.STAINED_GLASS_PANE.white(),
            Blocks.STAINED_GLASS_PANE.orange(), Blocks.STAINED_GLASS_PANE.yellow(),
            Blocks.STAINED_GLASS_PANE.brown()));
        blocks.addAll(List.of(Blocks.GLAZED_TERRACOTTA.blue(), Blocks.GLAZED_TERRACOTTA.brown(),
            Blocks.GLAZED_TERRACOTTA.gray(), Blocks.GLAZED_TERRACOTTA.green(), Blocks.GLAZED_TERRACOTTA.magenta(),
            Blocks.GLAZED_TERRACOTTA.pink()));
        // Badlands and desert pyramids and trail ruins and villages and ocean ruins make the other
        // terracotta colours.
        blocks.addAll(List.of(Blocks.DYED_TERRACOTTA.black(), Blocks.DYED_TERRACOTTA.green(),
            Blocks.DYED_TERRACOTTA.magenta(), Blocks.DYED_TERRACOTTA.pink(), Blocks.DYED_TERRACOTTA.purple()));
        blocks.addAll(copper());
        return List.copyOf(blocks);
    }

    // The wood of trees no structure builds with and the shapes no structure uses.
    private static List<Block> woods() {
        return List.of(Blocks.OAK_WOOD, Blocks.BIRCH_WOOD, Blocks.JUNGLE_WOOD, Blocks.DARK_OAK_WOOD,
            Blocks.CHERRY_WOOD, Blocks.PALE_OAK_WOOD, Blocks.POPLAR_WOOD, Blocks.CRIMSON_HYPHAE,
            Blocks.WARPED_HYPHAE, Blocks.STRIPPED_BIRCH_LOG, Blocks.STRIPPED_BIRCH_WOOD, Blocks.STRIPPED_JUNGLE_LOG,
            Blocks.STRIPPED_JUNGLE_WOOD, Blocks.STRIPPED_ACACIA_WOOD, Blocks.STRIPPED_DARK_OAK_LOG,
            Blocks.STRIPPED_DARK_OAK_WOOD, Blocks.STRIPPED_MANGROVE_LOG, Blocks.STRIPPED_MANGROVE_WOOD,
            Blocks.STRIPPED_CHERRY_LOG, Blocks.STRIPPED_CHERRY_WOOD, Blocks.STRIPPED_PALE_OAK_LOG,
            Blocks.STRIPPED_PALE_OAK_WOOD, Blocks.STRIPPED_POPLAR_LOG, Blocks.STRIPPED_POPLAR_WOOD,
            Blocks.STRIPPED_CRIMSON_STEM, Blocks.STRIPPED_CRIMSON_HYPHAE, Blocks.STRIPPED_WARPED_STEM,
            Blocks.STRIPPED_WARPED_HYPHAE, Blocks.STRIPPED_BAMBOO_BLOCK, Blocks.BAMBOO_BLOCK, Blocks.BAMBOO_PLANKS,
            Blocks.BAMBOO_MOSAIC, Blocks.BAMBOO_SLAB, Blocks.BAMBOO_STAIRS, Blocks.BAMBOO_MOSAIC_SLAB,
            Blocks.BAMBOO_MOSAIC_STAIRS, Blocks.BAMBOO_DOOR, Blocks.BAMBOO_TRAPDOOR, Blocks.BAMBOO_FENCE_GATE,
            Blocks.CHERRY_PLANKS, Blocks.CHERRY_SLAB, Blocks.CHERRY_STAIRS, Blocks.CHERRY_DOOR,
            Blocks.CHERRY_TRAPDOOR, Blocks.CHERRY_FENCE_GATE, Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_SLAB,
            Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_DOOR, Blocks.MANGROVE_TRAPDOOR, Blocks.MANGROVE_FENCE,
            Blocks.MANGROVE_FENCE_GATE, Blocks.PALE_OAK_PLANKS, Blocks.PALE_OAK_SLAB, Blocks.PALE_OAK_STAIRS,
            Blocks.PALE_OAK_DOOR, Blocks.PALE_OAK_TRAPDOOR, Blocks.PALE_OAK_FENCE_GATE, Blocks.POPLAR_PLANKS,
            Blocks.POPLAR_SLAB, Blocks.POPLAR_STAIRS, Blocks.POPLAR_DOOR, Blocks.POPLAR_TRAPDOOR,
            Blocks.POPLAR_FENCE_GATE, Blocks.CRIMSON_PLANKS, Blocks.CRIMSON_SLAB, Blocks.CRIMSON_STAIRS,
            Blocks.CRIMSON_DOOR, Blocks.CRIMSON_TRAPDOOR, Blocks.CRIMSON_FENCE, Blocks.CRIMSON_FENCE_GATE,
            Blocks.WARPED_PLANKS, Blocks.WARPED_SLAB, Blocks.WARPED_STAIRS, Blocks.WARPED_DOOR,
            Blocks.WARPED_TRAPDOOR, Blocks.WARPED_FENCE, Blocks.WARPED_FENCE_GATE, Blocks.BIRCH_DOOR,
            Blocks.BIRCH_TRAPDOOR, Blocks.BIRCH_FENCE_GATE, Blocks.ACACIA_TRAPDOOR);
    }

    // Trial chambers use plain and oxidized waxed copper. Their unwaxed copper blocks and cut copper
    // and trapdoors weather into every unwaxed stage. The world never makes the rest.
    private static List<Block> copper() {
        List<Block> blocks = new ArrayList<>();
        for (WeatheringCopperCollection<Block> family : List.of(Blocks.CHISELED_COPPER, Blocks.CUT_COPPER_SLAB,
            Blocks.CUT_COPPER_STAIRS, Blocks.COPPER_GRATE, Blocks.COPPER_DOOR)) {
            family.weathering().forEach(blocks::add);
            blocks.add(family.waxed().exposed());
            blocks.add(family.waxed().weathered());
        }
        for (WeatheringCopperCollection<Block> family : List.of(Blocks.COPPER_BLOCK, Blocks.CUT_COPPER)) {
            blocks.add(family.waxed().exposed());
            blocks.add(family.waxed().weathered());
        }
        blocks.add(Blocks.COPPER_TRAPDOOR.waxed().unaffected());
        blocks.add(Blocks.COPPER_TRAPDOOR.waxed().exposed());
        blocks.add(Blocks.COPPER_TRAPDOOR.waxed().weathered());
        return blocks;
    }

    // Every block of a colour set except the colours a structure uses.
    private static List<Block> allBut(ColorCollection<Block> family, Block... generated) {
        List<Block> skipped = List.of(generated);
        return family.asList().stream().filter(block -> !skipped.contains(block)).toList();
    }
}
