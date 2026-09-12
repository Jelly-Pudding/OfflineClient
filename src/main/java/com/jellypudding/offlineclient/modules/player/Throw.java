package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// Every right click is repeated many times in the same tick. A stack of pearls
// or snowballs or eggs goes out in one go.
public final class Throw extends Module {

    private final NumberSetting amount = new NumberSetting("Amount",
        "How many uses one click fires.", 16, 2, 256, 1).min(2).max(100000);

    public Throw() {
        super("Throw", "Fires a whole stack of throwables in one click.", Category.PLAYER);
        addSettings(amount);
        searchTags("spam throw", "pearl spam", "snowball", "egg");
    }

    @Override
    public String getSuffix() {
        return amount.getValueString();
    }

    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (!inGame() || mc.rightClickDelay > 0 || !mc.options.keyUse.isDown()) {
            return;
        }
        for (int i = 0; i < amount.getInt(); i++) {
            if (mc.hitResult instanceof BlockHitResult block
                && block.getType() == HitResult.Type.BLOCK) {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, block);
            }
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }
}
