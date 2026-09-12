package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// LevelRendererMixin drops the vanilla black outline whilst this is on.
public final class BlockSelection extends Module {

    private final BoolSetting trueShape = new BoolSetting("True shape",
        "Follow every edge of stairs and fences and slabs rather than a plain cube round them.", true);
    private final BoolSetting singleSide = new BoolSetting("Single side",
        "Only mark the face you are looking at.", false);
    private final BoxStyle style = BoxStyle.white(BoxStyle.Shape.BOTH);
    private final BoolSetting hideInside = new BoolSetting("Hide inside",
        "Draw nothing whilst your head is inside the block you are looking at.", true);

    public BlockSelection() {
        super("BlockSelection", "Recolours the outline on the block you are looking at.", Category.RENDER);
        addSettings(trueShape, singleSide);
        addSettings(style.settings());
        addSettings(hideInside);
        searchTags("outline", "highlight", "crosshair");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit)
            || hit.getType() == HitResult.Type.MISS) {
            return;
        }
        if (hideInside.isOn() && hit.isInside()) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        VoxelShape shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        AABB bounds = shape.bounds().move(pos);
        if (singleSide.isOn()) {
            style.drawFace(batch, bounds, hit.getDirection(), false);
        } else if (trueShape.isOn()) {
            drawShape(batch, shape, pos);
        } else {
            style.draw(batch, bounds, false);
        }
    }

    // Every edge and every sub box the shape is made of.
    private void drawShape(DrawBatch batch, VoxelShape shape, BlockPos pos) {
        if (style.drawsLines()) {
            int line = style.lineColor();
            shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> batch.line(
                new Vec3(pos.getX() + x1, pos.getY() + y1, pos.getZ() + z1),
                new Vec3(pos.getX() + x2, pos.getY() + y2, pos.getZ() + z2), line, false));
        }
        if (style.drawsSides()) {
            int fill = style.fillColor();
            for (AABB part : shape.toAabbs()) {
                batch.solidBox(part.move(pos), fill, false);
            }
        }
    }
}
