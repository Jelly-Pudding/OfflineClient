package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.phys.AABB;

// ItemEntityRendererMixin asks this module where the drop sits and how it lies.
public final class ItemPhysics extends Module {

    // A model thinner than this is a flat sprite and lies on its back.
    private static final float FLAT_DEPTH = 0.0625f;

    // A hair off the ground so the drop does not fight the floor for depth.
    private static final float LIFT = 0.001f;

    // How far a drop can be turned from the one before it.
    private static final int SPREAD = 90;

    private final BoolSetting randomRotation = new BoolSetting("Random rotation",
        "Turn each drop by its own angle so a pile does not all face one way.", true);

    public ItemPhysics() {
        super("ItemPhysics", "Dropped items lie on the ground instead of hovering and spinning.",
            Category.RENDER);
        addSettings(randomRotation);
        searchTags("item", "drop", "physics");
    }

    // How far up the model goes to rest on the floor.
    public float groundOffset(ItemEntityRenderState state) {
        AABB box = state.item.getModelBoundingBox();
        return (float) (isFlat(box) ? -box.minZ : -box.minY) + LIFT;
    }

    // The turn is applied first so the item still lies flat once it is laid down.
    public void lay(PoseStack poseStack, ItemEntityRenderState state) {
        if (randomRotation.isOn()) {
            poseStack.mulPose(Axis.YP.rotationDegrees(Math.floorMod(state.seed, SPREAD)));
        }
        if (isFlat(state.item.getModelBoundingBox())) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-90));
        }
    }

    private static boolean isFlat(AABB box) {
        return box.getZsize() <= FLAT_DEPTH;
    }
}
