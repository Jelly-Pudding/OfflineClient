package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hides every block that is not on the list so ores show through the
 * ground. The chunk mesher asks this module which blocks to keep and
 * chunks are rebuilt whenever a setting changes.
 */
public final class XRay extends Module {

    /** The chunk mesher reads this once per block. */
    private static volatile XRay instance;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks that stay visible. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.ANCIENT_DEBRIS, Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE));
    private final BoolSetting lava = new BoolSetting("Lava",
        "Show lava so you do not dig into it.", true);
    private final BoolSetting water = new BoolSetting("Water",
        "Show water.", false);
    private final BoolSetting exposedOnly = new BoolSetting("Exposed only",
        "Only show ores that touch air or a cave. Helps against anti xray plugins.", false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How visible the hidden blocks stay. Zero removes them completely.",
        0, 0, 100, 5, "%").max(100);

    /**
     * Alpha of the block being meshed on the current worker thread. The
     * block renderer sets it and the section compiler reads it to pick the
     * translucent layer. -1 means the block is untouched.
     */
    private static final ThreadLocal<Integer> MESH_ALPHA = ThreadLocal.withInitial(() -> -1);

    /** Snapshot of the settings. Read from worker threads and replaced whole. */
    private volatile Set<Block> visible = Set.of();
    private volatile boolean exposed;
    private volatile int alpha;

    public XRay() {
        super("XRay", "See ores through the ground.", Category.RENDER);
        addSettings(blocks, lava, water, exposedOnly, opacity);
        searchTags("ore", "wallhack");
        // A picker change refreshes the chunks right away.
        blocks.onChange(() -> {
            if (isEnabled() && snapshot()) {
                rebuildChunks();
            }
        });
        instance = this;
    }

    /** The registered module or null before the client has started. */
    public static XRay get() {
        return instance;
    }

    /** Remembers the alpha of the block being meshed on this thread. */
    public static void setMeshAlpha(int alpha) {
        MESH_ALPHA.set(alpha);
    }

    /** Alpha of the block being meshed on this thread. -1 when untouched. */
    public static int meshAlpha() {
        return MESH_ALPHA.get();
    }

    /** True if the current block should be drawn see through. */
    public static boolean meshingTranslucent() {
        int alpha = MESH_ALPHA.get();
        return alpha > 0 && alpha < 255;
    }

    @Override
    public String getSuffix() {
        int a = alpha;
        return a > 0 ? opacity.getValueString() : null;
    }

    @Override
    protected void onEnable() {
        snapshot();
        rebuildChunks();
    }

    @Override
    protected void onDisable() {
        rebuildChunks();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (snapshot()) {
            rebuildChunks();
        }
    }

    /** Copies the settings into the fields the mixins read. True if anything changed. */
    private boolean snapshot() {
        Set<Block> next = new HashSet<>(blocks.resolved());
        if (lava.isOn()) {
            next.add(Blocks.LAVA);
        }
        if (water.isOn()) {
            next.add(Blocks.WATER);
        }
        int nextAlpha = (int) Math.round(opacity.getValue() / 100.0 * 255.0);
        boolean nextExposed = exposedOnly.isOn();

        boolean changed = !next.equals(visible) || nextAlpha != alpha || nextExposed != exposed;
        visible = Set.copyOf(next);
        alpha = nextAlpha;
        exposed = nextExposed;
        return changed;
    }

    private void rebuildChunks() {
        if (mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    /** True while hidden blocks are drawn see through instead of removed. */
    public boolean isOpacityMode() {
        return isEnabled() && alpha > 0 && alpha < 255;
    }

    /**
     * True if the block is one we want to see. With a position the exposed
     * only check also applies. Pass null to skip it.
     */
    public boolean isVisible(BlockGetter level, Block block, BlockPos pos) {
        if (!visible.contains(block)) {
            return false;
        }
        if (exposed && pos != null && level != null) {
            return isExposed(level, pos);
        }
        return true;
    }

    /** True if the block is one we want to see regardless of exposure. */
    public boolean isVisible(Block block) {
        return visible.contains(block);
    }

    /**
     * How the chunk mesher should draw a block. Returns -1 to leave it
     * alone. 0 to skip it. Anything else is the alpha for a see through
     * version of it.
     */
    public int alphaFor(BlockGetter level, BlockState state, BlockPos pos) {
        if (!isEnabled()) {
            return -1;
        }
        if (isVisible(level, state.getBlock(), pos)) {
            return -1;
        }
        int a = alpha;
        return a >= 255 ? -1 : a;
    }

    private static boolean isExposed(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction side : Direction.values()) {
            cursor.setWithOffset(pos, side);
            if (!level.getBlockState(cursor).isSolidRender()) {
                return true;
            }
        }
        return false;
    }
}
