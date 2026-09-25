package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.NearestCut;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Set;

// Anything that is not part of the natural world was put there by a player.
public final class BaseFinder extends Module {

    private static final int FILL_ALPHA = 64;

    private static final List<Block> NATURAL = List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR,
        Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANDESITE, Blocks.DIORITE, Blocks.GRANITE,
        Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE, Blocks.SMOOTH_BASALT,
        Blocks.BEDROCK, Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
        Blocks.PODZOL, Blocks.MYCELIUM, Blocks.GRAVEL, Blocks.SAND, Blocks.RED_SAND, Blocks.SANDSTONE,
        Blocks.RED_SANDSTONE, Blocks.CLAY, Blocks.SNOW, Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.PACKED_ICE,
        Blocks.BLUE_ICE, Blocks.WATER, Blocks.LAVA, Blocks.BUBBLE_COLUMN, Blocks.MOSS_BLOCK,
        Blocks.MOSS_CARPET, Blocks.MOSSY_COBBLESTONE, Blocks.INFESTED_STONE, Blocks.SPAWNER,
        Blocks.OBSIDIAN, Blocks.MAGMA_BLOCK, Blocks.TERRACOTTA,
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.NETHER_QUARTZ_ORE, Blocks.NETHER_GOLD_ORE, Blocks.ANCIENT_DEBRIS,
        Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER,
        Blocks.LARGE_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
        Blocks.OAK_LOG, Blocks.OAK_LEAVES, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LOG,
        Blocks.SPRUCE_LEAVES, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LOG,
        Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LOG,
        Blocks.MANGROVE_LEAVES, Blocks.MANGROVE_ROOTS, Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES,
        Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_LEAVES, Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
        Blocks.MUSHROOM_STEM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK,
        Blocks.BROWN_MUSHROOM, Blocks.RED_MUSHROOM, Blocks.VINE, Blocks.GLOW_LICHEN,
        Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN, Blocks.DEAD_BUSH,
        Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT, Blocks.LILY_PAD,
        Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO, Blocks.SWEET_BERRY_BUSH,
        Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM, Blocks.AZURE_BLUET,
        Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP,
        Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.SUNFLOWER,
        Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY, Blocks.COBWEB,
        Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, Blocks.BLACKSTONE,
        Blocks.GLOWSTONE, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.CRIMSON_STEM,
        Blocks.WARPED_STEM, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT,
        Blocks.END_STONE, Blocks.CHORUS_PLANT, Blocks.CHORUS_FLOWER);

    private final RegistryListSetting<Block> natural = new RegistryListSetting<>("Natural blocks",
        "Blocks the world puts down on its own. Anything else counts as a base. Click to pick them.",
        BuiltInRegistries.BLOCK, NATURAL);
    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 4, 1, 12, 1, " chunks").max(32);
    private final NumberSetting limit = new NumberSetting("Limit",
        "The most blocks lit up at once with the nearest first.", 2000, 100, 10000, 100).min(1);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the blocks lit up.", 0, false);

    private final ChunkScanner<BlockPos> scanner = new ChunkScanner<>();
    private final NearestCut<BlockPos> found = new NearestCut<>(BlockPos::distToCenterSqr);
    // Read by the scanner thread. Replaced whole when the list changes.
    private volatile Set<Block> naturalBlocks = Set.of();

    public BaseFinder() {
        super("BaseFinder", "Lights up every block a player put down near you.", Category.RENDER);
        addSettings(natural, range, limit, color);
        searchTags("base finder", "man made", "factions", "player blocks");
    }

    @Override
    public String getSuffix() {
        return count(found.result().size());
    }

    @Override
    protected void onEnable() {
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        scanner.reset();
        found.clear();
        naturalBlocks = Set.of();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        Set<Block> chosen = Set.copyOf(natural.resolved());
        if (!chosen.equals(naturalBlocks)) {
            naturalBlocks = chosen;
            scanner.reset();
        }
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(
            state -> !chosen.contains(state.getBlock()), (x, y, z, state) -> out.add(new BlockPos(x, y, z))));
        found.update(scanner.results(), limit.getInt());
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        int fill = ColorUtil.withAlpha(color.getColor(), FILL_ALPHA);
        for (BlockPos pos : found.result()) {
            batch.solidBox(DrawBatch.blockBox(pos), fill, true);
        }
    }
}
