package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Open water is a five by five column of water with two air blocks above the bobber.
// Only open water gives treasure. This scans once a tick rather than every frame.
public final class OpenWaterEsp extends Module {

    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.LINES);
    private final ColorSetting openColor = new ColorSetting("Open colour",
        "Colour whilst the bobber sits in open water.", 120, 0.79f, 0.88f, false);
    private final ColorSetting shallowColor = new ColorSetting("Shallow colour",
        "Colour whilst the water is too shallow for treasure.", 0, 0.79f, 0.88f, false);
    private final BoolSetting cross = new BoolSetting("Cross",
        "Draw a cross over every face whilst the water is too shallow.", true);

    // Null whilst no bobber is out.
    private AABB box;
    private boolean open;

    public OpenWaterEsp() {
        super("OpenWaterESP", "Shows whether your bobber sits in open water.", Category.RENDER);
        addSettings(style.settings());
        addSettings(openColor, shallowColor, cross);
        searchTags("fishing", "treasure", "auto fish esp");
    }

    @Override
    public String getSuffix() {
        if (box == null) {
            return null;
        }
        return open ? "open" : "shallow";
    }

    @Override
    protected void onDisable() {
        box = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        FishingHook bobber = inGame() ? mc.player.fishing : null;
        if (bobber == null) {
            box = null;
            return;
        }
        BlockPos surface = surfaceOf(bobber);
        open = bobber.calculateOpenWater(surface);
        box = new AABB(-2, -1, -2, 3, 3, 3).move(surface);
    }

    // The water block the bobber floats in. A bobber riding high sits in the air block above it.
    private BlockPos surfaceOf(FishingHook bobber) {
        BlockPos pos = bobber.blockPosition();
        if (mc.level.getFluidState(pos).is(FluidTags.WATER)) {
            return pos;
        }
        BlockPos below = pos.below();
        return mc.level.getFluidState(below).is(FluidTags.WATER) ? below : pos;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (box == null) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int color = open ? openColor.getColor() : shallowColor.getColor();
        style.draw(batch, box, color, false);
        if (!open && cross.isOn()) {
            drawCross(batch, color);
        }
    }

    // Both diagonals of all six faces. The shallow box reads as barred off.
    private void drawCross(DrawBatch batch, int color) {
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                Vec3[] corners = face(axis, side);
                batch.line(corners[0], corners[2], color, false);
                batch.line(corners[1], corners[3], color, false);
            }
        }
    }

    // The four corners of one face in order around it.
    private Vec3[] face(int axis, int side) {
        double x = side == 0 ? box.minX : box.maxX;
        double y = side == 0 ? box.minY : box.maxY;
        double z = side == 0 ? box.minZ : box.maxZ;
        return switch (axis) {
            case 0 -> new Vec3[] {new Vec3(x, box.minY, box.minZ), new Vec3(x, box.minY, box.maxZ),
                new Vec3(x, box.maxY, box.maxZ), new Vec3(x, box.maxY, box.minZ)};
            case 1 -> new Vec3[] {new Vec3(box.minX, y, box.minZ), new Vec3(box.maxX, y, box.minZ),
                new Vec3(box.maxX, y, box.maxZ), new Vec3(box.minX, y, box.maxZ)};
            default -> new Vec3[] {new Vec3(box.minX, box.minY, z), new Vec3(box.maxX, box.minY, z),
                new Vec3(box.maxX, box.maxY, z), new Vec3(box.minX, box.maxY, z)};
        };
    }
}
