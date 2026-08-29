package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// BlockCollisionsMixin gives a cactus a full block shape so you never touch it.
public final class AntiCactus extends Module {

    public AntiCactus() {
        super("AntiCactus", "Stops cacti from hurting you.", Category.PLAYER);
    }
}
