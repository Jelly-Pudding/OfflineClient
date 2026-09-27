package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// Vanilla waits ten ticks after a jump before the held key may jump again. A jump
// onto a block or under a low ceiling lands sooner and stands still for the rest.
public final class NoJumpDelay extends Module {

    public NoJumpDelay() {
        super("NoJumpDelay", "Jumps again the moment you land whilst you hold jump.", Category.MOVEMENT);
        searchTags("jump delay", "jump cooldown", "bunny hop");
    }

    // The tick event runs before the player moves and the wait is read.
    @Subscribe
    private void onTick(TickEvent event) {
        if (inGame()) {
            mc.player.noJumpDelay = 0;
        }
    }
}
