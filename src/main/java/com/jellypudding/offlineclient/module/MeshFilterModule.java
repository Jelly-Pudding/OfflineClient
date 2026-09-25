package com.jellypudding.offlineclient.module;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChunkRebuild;
import net.minecraft.world.level.block.Block;

import java.util.Set;

// A module that changes which blocks the chunk mesher keeps. Worker threads read the
// block set and the opacity. Both are replaced whole and the chunks rebuilt whenever
// a setting changes.
public abstract class MeshFilterModule extends Module {

    protected final NumberSetting opacity;

    private volatile Set<Block> listed = Set.of();
    private volatile int alpha;
    private final ChunkRebuild rebuild = new ChunkRebuild();

    protected MeshFilterModule(String name, String description, String opacityText) {
        super(name, description, Category.RENDER);
        opacity = new NumberSetting("Opacity", opacityText, 0, 0, 100, 5, "%").max(100);
    }

    // The blocks the settings name right now.
    protected abstract Set<Block> chosenBlocks();

    // Anything else the mesher reads from the subclass. True when it changed.
    protected boolean snapshotExtra() {
        return false;
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

    private boolean snapshot() {
        Set<Block> next = Set.copyOf(chosenBlocks());
        int nextAlpha = (int) Math.round(opacity.getValue() / 100.0 * 255.0);
        boolean changed = snapshotExtra();
        if (!next.equals(listed) || nextAlpha != alpha) {
            listed = next;
            alpha = nextAlpha;
            changed = true;
        }
        return changed;
    }

    protected final boolean listed(Block block) {
        return listed.contains(block);
    }

    protected final boolean seeThrough() {
        return alpha > 0;
    }

    // What the mesher does with a block this module hides. Zero skips it and anything
    // short of full is the alpha of a see through copy. Minus one leaves it alone.
    protected final int hiddenAlpha() {
        int a = alpha;
        return a >= 255 ? -1 : a;
    }
}
