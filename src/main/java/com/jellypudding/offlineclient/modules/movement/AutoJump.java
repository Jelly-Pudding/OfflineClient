package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.MovementUtil;

public final class AutoJump extends Module {

    public enum When { SPRINTING, MOVING, ALWAYS }

    private final EnumSetting<When> when = new EnumSetting<>("Jump when",
        "Picks whether to jump whilst sprinting or whilst moving or always.",
        When.SPRINTING);

    public AutoJump() {
        super("AutoJump", "Jumps for you whenever you are on the ground.", Category.MOVEMENT);
        addSettings(when);
        searchTags("bunnyhop", "bhop", "bunny hop");
    }

    @Override
    public String getSuffix() {
        return when.getValueString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || mc.player.isShiftKeyDown()) {
            return;
        }
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()
            || mc.player.getAbilities().flying || mc.player.isPassenger()) {
            return;
        }
        boolean moving = MovementUtil.inputDirection().lengthSqr() > 0;
        boolean go = switch (when.getValue()) {
            case SPRINTING -> moving && mc.player.isSprinting();
            case MOVING -> moving;
            case ALWAYS -> true;
        };
        if (go) {
            mc.player.jumpFromGround();
        }
    }
}
