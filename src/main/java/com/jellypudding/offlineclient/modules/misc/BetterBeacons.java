package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// BeaconScreenMixin rebuilds the screen whilst this is on.
public final class BetterBeacons extends Module {

    public BetterBeacons() {
        super("BetterBeacons", "Offers every beacon effect whatever the pyramid is worth.",
            Category.MISC);
        searchTags("beacon", "effects");
    }
}
