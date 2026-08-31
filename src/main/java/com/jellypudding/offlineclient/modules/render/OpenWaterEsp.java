package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.AABB;

/**
 * Open water is a five by five column of water with two blocks of air above
 * it around the bobber. Only open water gives treasure.
 */
public final class OpenWaterEsp extends Module {

    private static final int OPEN_COLOR = 0xFF30E030;
    private static final int SHALLOW_COLOR = 0xFFE03030;

    public OpenWaterEsp() {
        super("OpenWaterESP", "Shows whether your bobber sits in open water.", Category.RENDER);
        searchTags("fishing", "treasure", "auto fish esp");
    }

    @Override
    public String getSuffix() {
        FishingHook bobber = mc.player == null ? null : mc.player.fishing;
        if (bobber == null) {
            return null;
        }
        return bobber.calculateOpenWater(bobber.blockPosition()) ? "open" : "shallow";
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || mc.player.fishing == null) {
            return;
        }
        FishingHook bobber = mc.player.fishing;
        boolean open = bobber.calculateOpenWater(bobber.blockPosition());
        AABB box = new AABB(-2, -1, -2, 3, 3, 3).move(bobber.blockPosition());
        event.getBatch().outlineBox(box, open ? OPEN_COLOR : SHALLOW_COLOR, false);
    }
}
