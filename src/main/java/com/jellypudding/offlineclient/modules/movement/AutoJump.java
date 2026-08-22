package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;

public final class AutoJump extends Module {

    public enum When {
        SPRINTING("Sprinting"),
        MOVING("Moving"),
        ALWAYS("Always");

        private final String label;

        When(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final EnumSetting<When> when = new EnumSetting<>("Jump when",
        "Sprinting only jumps while you sprint. Moving jumps whenever you walk. Always jumps even when standing still.",
        When.SPRINTING);

    public AutoJump() {
        super("AutoJump", "Jumps for you whenever you are on the ground.", Category.MOVEMENT);
        addSettings(when);
        searchTags("bunnyhop", "bhop", "bunny hop");
    }

    @Override
    public String getSuffix() {
        return when.getValue().toString();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !mc.player.onGround() || mc.player.isShiftKeyDown()) {
            return;
        }
        // Water and ladders and flight all have their own vertical movement.
        if (mc.player.isInWater() || mc.player.isInLava() || mc.player.onClimbable()
            || mc.player.getAbilities().flying || mc.player.isPassenger()) {
            return;
        }
        boolean moving = mc.player.input.getMoveVector().length() > 1e-4f;
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
