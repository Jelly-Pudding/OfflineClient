package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

/**
 * The behavior lives in WebBlockMixin which skips the slowdown for the
 * local player while this is enabled.
 */
public final class NoWeb extends Module {

    public NoWeb() {
        super("NoWeb", "Move through cobwebs at full speed.", Category.MOVEMENT);
        searchTags("cobweb", "web");
    }
}
