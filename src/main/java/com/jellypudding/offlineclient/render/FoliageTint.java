package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.modules.render.Ambience;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

// The fixed tint the game gives spruce and birch leaves in place of the biome's foliage colour.
// In the world it takes Ambience's foliage colour whilst Ambience paints foliage. An item keeps
// the fixed tint.
public record FoliageTint(BlockTintSource fixed) implements BlockTintSource {

    @Override
    public int color(BlockState state) {
        return fixed.color(state);
    }

    @Override
    public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        Ambience ambience = Modules.get(Ambience.class);
        return ambience != null && ambience.paintsFoliage() ? ambience.foliageColor()
            : fixed.colorInWorld(state, level, pos);
    }
}
