package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.modules.player.NoMiningTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// The block ray of the crosshair. Every crosshair ray of the client is cast with it. NoMiningTrace
// looks through nether portals and Hitboxes grows the outline of the small blocks it lists. A hit
// stays inside the block it names and the server takes it as a click on that block.
public final class CrosshairClip extends ClipContext {

    public CrosshairClip(Vec3 from, Vec3 to, ClipContext.Fluid fluid, Entity entity) {
        super(from, to, ClipContext.Block.OUTLINE, fluid, entity);
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
        NoMiningTrace trace = Modules.active(NoMiningTrace.class);
        if (trace != null && trace.looksThrough(state)) {
            return Shapes.empty();
        }
        VoxelShape shape = super.getBlockShape(state, level, pos);
        Hitboxes hitboxes = Modules.active(Hitboxes.class);
        return hitboxes == null ? shape : grown(shape, pos, getFrom(), hitboxes.aimGrowth(state));
    }

    // A ray that starts inside the block space keeps the plain outline or a box round the eyes
    // would catch every look.
    public static VoxelShape grown(VoxelShape outline, BlockPos pos, Vec3 from, double share) {
        return share > 0 && !new AABB(pos).contains(from) ? grown(outline, share) : outline;
    }

    // Each side of the outline moves the share of the way to the edge of the block space. The box
    // keeps the whole outline and an outline inside the block space never leaves it.
    public static VoxelShape grown(VoxelShape outline, double share) {
        if (share <= 0 || outline.isEmpty()) {
            return outline;
        }
        AABB box = outline.bounds();
        return Shapes.create(
            Math.min(box.minX, box.minX * (1 - share)), Math.min(box.minY, box.minY * (1 - share)),
            Math.min(box.minZ, box.minZ * (1 - share)), Math.max(box.maxX, box.maxX + (1 - box.maxX) * share),
            Math.max(box.maxY, box.maxY + (1 - box.maxY) * share),
            Math.max(box.maxZ, box.maxZ + (1 - box.maxZ) * share));
    }
}
