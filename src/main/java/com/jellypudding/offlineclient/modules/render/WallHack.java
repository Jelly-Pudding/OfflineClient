package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.MeshFilterModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// The mirror of XRay. The listed blocks go see through and the rest stay solid.
// XRay owns the meshing path and asks this module what the chosen blocks need.
public final class WallHack extends MeshFilterModule {

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks that go see through.", BuiltInRegistries.BLOCK,
        List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.ANDESITE,
            Blocks.DIORITE, Blocks.GRANITE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.NETHERRACK, Blocks.END_STONE));
    private final BoolSetting skipHidden = new BoolSetting("Skip hidden chunks",
        "Let the game leave out chunks it thinks are out of sight.", false);

    public WallHack() {
        super("WallHack", "Makes the blocks you choose see through.",
            "How visible the listed blocks stay.");
        addSettings(blocks, opacity, skipHidden);
        searchTags("xray", "see through", "walls");
    }

    @Override
    public String getSuffix() {
        return count(blocks.size());
    }

    @Override
    protected Set<Block> chosenBlocks() {
        return new HashSet<>(blocks.resolved());
    }

    // Vanilla skips chunk sections that are boxed in by solid ground.
    public boolean showsHiddenChunks() {
        return isEnabled() && !skipHidden.isOn();
    }

    public int alphaFor(Block block) {
        return isEnabled() && listed(block) ? hiddenAlpha() : -1;
    }
}
