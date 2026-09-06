package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AirStrafingSpeedEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class Glide extends Module {

    private final NumberSetting fallSpeed = new NumberSetting("Fall speed",
        "Blocks you drop per tick whilst gliding.", 0.125, 0.005, 0.3, 0.005);
    private final NumberSetting boost = new NumberSetting("Horizontal boost",
        "How much further your keys carry you through the air.", 1.2, 1, 3, 0.05, "x");
    private final NumberSetting minHeight = new NumberSetting("Minimum height",
        "Stops gliding once the ground is this close with zero turning it off.",
        0, 0, 4, 0.25, " blocks");
    private final BoolSetting pauseOnSneak = new BoolSetting("Pause whilst sneaking",
        "Holding sneak drops you at the normal speed.", true);

    // Read by the air strafing hook later in the same tick.
    private boolean gliding;

    public Glide() {
        super("Glide", "Slows your fall into a controlled drift.", Category.MOVEMENT);
        addSettings(fallSpeed, boost, minHeight, pauseOnSneak);
        searchTags("slow fall", "float", "parachute", "feather falling");
    }

    @Override
    public String getSuffix() {
        if (pauseOnSneak.isOn() && inGame() && mc.player.isShiftKeyDown()) {
            return "paused";
        }
        return fallSpeed.getValueString();
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.FALL_CONTROL;
    }

    @Override
    protected void onEnable() {
        gliding = false;
    }

    @Override
    protected void onDisable() {
        gliding = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        gliding = false;
        if (!inGame() || mc.player.isSpectator() || mc.player.isPassenger()) {
            return;
        }
        if (pauseOnSneak.isOn() && mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.player.onGround() || mc.player.isFallFlying() || mc.player.getAbilities().flying
            || mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()) {
            return;
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y >= 0 || groundIsNear()) {
            return;
        }

        gliding = true;
        // Vanilla takes gravity off this value again before it moves the player.
        double target = mc.player.getGravity() - fallSpeed.getValue();
        mc.player.setDeltaMovement(velocity.x, Math.max(velocity.y, target), velocity.z);
    }

    @Subscribe
    private void onAirStrafingSpeed(AirStrafingSpeedEvent event) {
        if (gliding) {
            event.setSpeed(event.getSpeed() * boost.getFloat());
        }
    }

    // True when there is anything to land on within the minimum height.
    // Fluids are checked by hand because they carry no collision shape.
    private boolean groundIsNear() {
        double reach = minHeight.getValue();
        if (reach <= 0) {
            return false;
        }
        AABB box = mc.player.getBoundingBox();
        AABB swept = box.minmax(box.move(0, -reach, 0));
        if (!mc.level.noCollision(mc.player, swept)) {
            return true;
        }
        for (BlockPos pos : BlockPos.betweenClosed(swept)) {
            if (!BlockUtil.state(pos).getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
