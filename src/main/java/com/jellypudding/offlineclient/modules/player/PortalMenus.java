package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// The behaviour lives in LocalPlayerMixin which hides the open screen from
// the portal effect. It is never closed.
public final class PortalMenus extends Module {

    public PortalMenus() {
        super("PortalMenus", "Keeps your inventory and chat open whilst you stand in a portal.",
            Category.PLAYER);
        searchTags("portal gui", "portal chat", "portal inventory", "portals");
    }
}
