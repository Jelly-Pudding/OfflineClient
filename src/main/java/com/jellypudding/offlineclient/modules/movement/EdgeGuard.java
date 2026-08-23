package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Behaviour lives in LocalPlayerMixin.
public final class EdgeGuard extends Module {

    private final NumberSetting maxDrop = new NumberSetting("Max drop",
        "Edges deeper than this stop you.",
        0.5, 0.5, 10, 0.5, " blocks");

    public EdgeGuard() {
        super("EdgeGuard", "Stops you from going over edges without the sneak slowdown.",
            Category.MOVEMENT);
        addSettings(maxDrop);
        searchTags("safewalk", "safe walk", "edge", "ledge");
    }

    @Override
    public String getSuffix() {
        return maxDrop.getValueString();
    }

    // True when the edge ahead is too deep to allow.
    public boolean shouldGuard() {
        if (!isEnabled() || !inGame()) {
            return false;
        }
        double depth = maxDrop.getValue();
        if (depth <= 0.5) {
            return true;
        }

        AABB probe = mc.player.getBoundingBox();
        Vec3 velocity = mc.player.getDeltaMovement();
        double horizontal = velocity.horizontalDistance();
        if (horizontal > 1e-5) {
            probe = probe.move(velocity.x / horizontal * 0.45, 0, velocity.z / horizontal * 0.45);
        }
        probe = probe.expandTowards(0, -depth, 0).inflate(-0.05, 0, -0.05);

        return mc.level.noCollision(mc.player, probe);
    }
}
