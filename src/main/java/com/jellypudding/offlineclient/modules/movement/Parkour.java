package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Parkour extends Module {

    private final NumberSetting edgeDistance = new NumberSetting("Edge distance",
        "How far before the edge of a block the jump fires.",
        0.001, 0.001, 0.25, 0.001, " blocks");
    private final NumberSetting minDepth = new NumberSetting("Min depth",
        "How far the drop past the edge must be before it jumps.",
        0.5, 0.05, 10, 0.05, " blocks").min(0.05);
    private final BoolSetting whileSneaking = new BoolSetting("Jump whilst sneaking",
        "Keep jumping at edges whilst you sneak.", false);

    public Parkour() {
        super("Parkour", "Jumps for you at the edge of blocks.", Category.MOVEMENT);
        addSettings(edgeDistance, minDepth, whileSneaking);
        searchTags("edge jump", "auto jump");
    }

    // EdgeGuard holds you at the same lip Parkour leaps from.
    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.EDGE;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || mc.options.keyJump.isDown()) {
            return;
        }
        if (mc.player.isShiftKeyDown() && !whileSneaking.isOn()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.horizontalDistanceSqr() < 1e-6) {
            return;
        }

        // The shrunk box still counts the very lip of a block as ground.
        double edge = edgeDistance.getValue();
        AABB under = mc.player.getBoundingBox()
            .move(0, -minDepth.getValue(), 0)
            .inflate(-edge, 0, -edge);
        if (mc.level.noCollision(mc.player, under)) {
            mc.player.jumpFromGround();
        }
    }
}
