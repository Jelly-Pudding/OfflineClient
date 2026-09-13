package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChunkRebuild;
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

// The chunk mesher asks this module which blocks to keep.
// Chunks are rebuilt whenever a setting changes.
public final class XRay extends Module {

    // The chunk mesher reads this once per block.
    private static volatile XRay instance;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks that stay visible.", BuiltInRegistries.BLOCK,
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
        "Shows lava before you dig into it.", true);
    private final BoolSetting water = new BoolSetting("Water",
        "Show water.", false);
    private final BoolSetting exposedOnly = new BoolSetting("Exposed only",
        "Only show ores that touch air or a cave.", false);
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How visible the hidden blocks stay.",
        0, 0, 100, 5, "%").max(100);

    // Alpha of the block being meshed on the current worker thread. Minus one
    // means the block is untouched.
    private static final ThreadLocal<Integer> MESH_ALPHA = ThreadLocal.withInitial(() -> -1);

    // Read from worker threads and replaced whole.
    private volatile Set<Block> visible = Set.of();
    private volatile boolean exposed;
    private volatile int alpha;
    private final ChunkRebuild rebuild = new ChunkRebuild();

    public XRay() {
        super("XRay", "See ores through the ground.", Category.RENDER);
        addSettings(blocks, lava, water, exposedOnly, opacity);
        searchTags("ore", "wallhack");
        instance = this;
    }

    // Puts a block on the list or takes it off. True when it went on.
    public boolean toggleBlock(Block block) {
        if (blocks.contains(block)) {
            blocks.remove(block);
            return false;
        }
        blocks.add(block);
        return true;
    }

    // Null before the client has started.
    public static XRay get() {
        return instance;
    }

    // The alpha the chunk mesher gives this block. Minus one leaves it alone
    // and zero skips it. WallHack drives the same path with the list turned round.
    public static int meshAlphaFor(BlockGetter level, BlockState state, BlockPos pos) {
        XRay xray = instance;
        if (xray != null && xray.isEnabled()) {
            return xray.alphaFor(level, state, pos);
        }
        WallHack wallHack = WallHack.get();
        return wallHack == null ? -1 : wallHack.alphaFor(state.getBlock());
    }

    public static void setMeshAlpha(int alpha) {
        MESH_ALPHA.set(alpha);
    }

    public static int meshAlpha() {
        return MESH_ALPHA.get();
    }

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
        ChunkRebuild.now();
    }

    @Override
    protected void onDisable() {
        ChunkRebuild.now();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        rebuild.tick(snapshot());
    }

    // True if anything changed.
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
        if (changed) {
            visible = Set.copyOf(next);
            alpha = nextAlpha;
            exposed = nextExposed;
        }
        return changed;
    }

    // A null position skips the exposed only check.
    public boolean isVisible(BlockGetter level, Block block, BlockPos pos) {
        if (!visible.contains(block)) {
            return false;
        }
        if (exposed && pos != null && level != null) {
            return isExposed(level, pos);
        }
        return true;
    }

    // Ignores the exposed only check.
    public boolean isVisible(Block block) {
        return visible.contains(block);
    }

    // Minus one leaves the block alone and zero skips it. Anything else is the
    // alpha for a see through version.
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
