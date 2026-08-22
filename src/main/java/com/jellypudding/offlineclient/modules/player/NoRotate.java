package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

/**
 * Keeps the view where it was when the server sends a forced rotation.
 * ClientPacketListenerMixin saves the look angles before the packet is
 * applied and puts them back after.
 */
public final class NoRotate extends Module {

    public NoRotate() {
        super("NoRotate", "Stops the server from turning your head on teleports.", Category.PLAYER);
        searchTags("no rotate", "teleport", "camera snap");
    }
}
