package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// Every right click runs several times in the same tick. A stack of pearls or snowballs goes
// out in one go.
public final class Throw extends Module {

    // A use aimed at a block sends a click on the block and then a use of the item. The real
    // click after the extra ones keeps room for both.
    private static final int REAL_CLICK = 2;

    private final NumberSetting uses = new NumberSetting("Uses per click",
        "How many times one right click is used. On Paper at most nine use packets land at once.",
        16, 2, 64, 1).min(2);
    private final BoolSetting blocks = new BoolSetting("Blocks too",
        "The extra uses also click the block or creature you aim at. Switched off they only use"
            + " the item in hand. A door or lever clicked an even number of times ends where it began.", false);

    // Set whilst the extra uses run. Each one posts the right click again.
    private boolean repeating;

    public Throw() {
        super("Throw", "Repeats each right click several times in the same tick.", Category.PLAYER);
        addSettings(uses, blocks);
        searchTags("spam throw", "pearl spam", "snowball", "egg");
    }

    @Override
    public String getSuffix() {
        return uses.getValueString();
    }

    // The extra uses go through the game's own right click. It picks the hand and swings. Paper
    // throws away every use past its limit without an answer and the hand would show items that
    // were never used. The budget is read again before each use.
    @Subscribe
    private void onRightClick(RightClickEvent event) {
        if (repeating || event.isCancelled() || !inGame() || !InputUtil.physicallyHeld(mc.options.keyUse)) {
            return;
        }
        HitResult aimed = mc.hitResult;
        boolean blank = !blocks.isOn() && aimed != null && aimed.getType() != HitResult.Type.MISS;
        int cost = blank ? 1 : REAL_CLICK;
        repeating = true;
        try {
            if (blank) {
                mc.hitResult = BlockHitResult.miss(aimed.getLocation(), Direction.UP,
                    BlockPos.containing(aimed.getLocation()));
            }
            for (int i = 1; i < uses.getInt() && UseBudget.remaining() >= REAL_CLICK + cost; i++) {
                int before = UseBudget.remaining();
                mc.startUseItem();
                cost = Math.max(cost, before - UseBudget.remaining());
            }
        } finally {
            mc.hitResult = aimed;
            repeating = false;
        }
    }
}
