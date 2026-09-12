package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

// Anything that is not part of the natural world was put there by a player.
// The area around you is swept a slice at a time and every such block lit up.
public final class BaseFinder extends Module {

    // The sweep covers the whole height in this many ticks.
    private static final int SWEEP_TICKS = 64;
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
        "How far out from you the sweep reaches.", 64, 16, 128, 8, " blocks").min(8).max(256);
    private final NumberSetting limit = new NumberSetting("Limit",
        "The most blocks lit up at once.", 2000, 100, 10000, 100).min(1);
    private final ColorSetting color = new ColorSetting("Colour",
        "Colour of the blocks lit up.", 0, false);

    // The blocks found in the sweep under way and in the last full one.
    private final List<BlockPos> sweeping = new ArrayList<>();
    private List<BlockPos> found = List.of();
    private int slice;

    public BaseFinder() {
        super("BaseFinder", "Lights up every block a player put down near you.", Category.RENDER);
        addSettings(natural, range, limit, color);
        searchTags("base finder", "man made", "factions", "player blocks");
    }

    @Override
    public String getSuffix() {
        return found.isEmpty() ? null : count(found.size());
    }

    @Override
    protected void onEnable() {
        sweeping.clear();
        found = List.of();
        slice = 0;
    }

    // One slab of the height a tick. A full sweep takes about three seconds.
    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        int minY = mc.level.getMinY();
        int step = Math.max(1, mc.level.getHeight() / SWEEP_TICKS);
        int top = mc.level.getMaxY() - slice * step;
        int bottom = Math.max(minY, top - step);
        int reach = range.getInt();
        BlockPos feet = mc.player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top; y > bottom && sweeping.size() < limit.getInt(); y--) {
            for (int x = -reach; x <= reach; x++) {
                for (int z = -reach; z <= reach; z++) {
                    cursor.set(feet.getX() + x, y, feet.getZ() + z);
                    if (!natural.contains(mc.level.getBlockState(cursor).getBlock())) {
                        sweeping.add(cursor.immutable());
                    }
                }
            }
        }
        if (++slice * step >= mc.level.getHeight() || bottom <= minY) {
            found = List.copyOf(sweeping);
            sweeping.clear();
            slice = 0;
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        int fill = ColorUtil.withAlpha(color.getColor(), FILL_ALPHA);
        for (BlockPos pos : found) {
            batch.solidBox(DrawBatch.blockBox(pos), fill, true);
        }
    }
}
