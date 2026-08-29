package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;

public final class SelfWeb extends Module {

    public enum Mode { ALWAYS, ENEMY_NEAR }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "When the web goes down.", Mode.ALWAYS)
        .describe(Mode.ALWAYS, "Webs your block as soon as it is turned on.")
        .describe(Mode.ENEMY_NEAR, "Waits until an enemy comes within range.");
    private final NumberSetting range = new NumberSetting("Enemy range",
        "How close an enemy has to be.", 4, 1, 10, 0.5, " blocks")
        .under(mode, Mode.ENEMY_NEAR);
    private final BoolSetting doubles = new BoolSetting("Upper body",
        "Also web the block your head is in.", false);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the web is down.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward the web.", true);

    private final SlotSwap slots = new SlotSwap();

    public SelfWeb() {
        super("SelfWeb", "Webs your own block to stop knockback.", Category.COMBAT);
        addSettings(mode, range, doubles, toggleOff, rotate);
        searchTags("cobweb", "web", "anti knockback");
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mode.is(Mode.ENEMY_NEAR) && EntityUtil.nearestEnemy(range.getValue()) == null) {
            return;
        }

        BlockPos feet = mc.player.blockPosition();
        AutoWeb.placeWeb(feet, rotate.isOn(), slots);
        if (doubles.isOn()) {
            AutoWeb.placeWeb(feet.above(), rotate.isOn(), slots);
        }
        // The client puts its own copy down.
        boolean done = !AutoWeb.webbable(feet)
            && (!doubles.isOn() || !AutoWeb.webbable(feet.above()));
        if (toggleOff.isOn() && done) {
            setEnabled(false);
        }
    }
}
