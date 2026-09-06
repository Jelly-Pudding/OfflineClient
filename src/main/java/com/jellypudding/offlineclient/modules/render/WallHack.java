package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The mirror of XRay. The listed blocks go see through and the rest stay solid.
// XRay owns the meshing path and asks this module what the chosen blocks need.
public final class WallHack extends Module {

    // The chunk mesher reads this once per block.
    private static volatile WallHack instance;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks that go see through.", BuiltInRegistries.BLOCK,
        List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANDESITE,
            Blocks.DIORITE, Blocks.GRANITE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.NETHERRACK, Blocks.END_STONE));
    private final NumberSetting opacity = new NumberSetting("Opacity",
        "How visible the listed blocks stay.", 0, 0, 100, 5, "%").max(100);
    private final BoolSetting skipHidden = new BoolSetting("Skip hidden chunks",
        "Let the game leave out chunks it thinks are out of sight.", false);

    // Read from worker threads and replaced whole.
    private volatile Set<Block> seeThrough = Set.of();
    private volatile int alpha;
    private int rebuildCooldown;

    public WallHack() {
        super("WallHack", "Makes the blocks you choose see through.", Category.RENDER);
        addSettings(blocks, opacity, skipHidden);
        searchTags("xray", "see through", "walls");
        instance = this;
    }

    // Null before the client has started.
    public static WallHack get() {
        return instance;
    }

    @Override
    public String getSuffix() {
        return count(blocks.size());
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

    // The rebuild fires once the settings sit still for half a second.
    @Subscribe
    private void onTick(TickEvent event) {
        if (snapshot()) {
            rebuildCooldown = 10;
        } else if (rebuildCooldown > 0 && --rebuildCooldown == 0) {
            rebuildChunks();
        }
    }

    // True if anything changed.
    private boolean snapshot() {
        Set<Block> next = new HashSet<>(blocks.resolved());
        int nextAlpha = (int) Math.round(opacity.getValue() / 100.0 * 255.0);
        boolean changed = !next.equals(seeThrough) || nextAlpha != alpha;
        if (changed) {
            seeThrough = Set.copyOf(next);
            alpha = nextAlpha;
        }
        return changed;
    }

    private void rebuildChunks() {
        if (mc.levelExtractor != null) {
            mc.levelExtractor.allChanged();
        }
    }

    // Vanilla skips chunk sections that are boxed in by solid ground.
    public boolean showsHiddenChunks() {
        return isEnabled() && !skipHidden.isOn();
    }

    // Minus one leaves the block alone and zero skips it. Anything else is the
    // alpha for a see through version.
    public int alphaFor(Block block) {
        if (!isEnabled() || !seeThrough.contains(block)) {
            return -1;
        }
        int a = alpha;
        return a >= 255 ? -1 : a;
    }
}
