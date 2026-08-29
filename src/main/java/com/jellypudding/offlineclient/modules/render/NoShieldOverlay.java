package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.mojang.blaze3d.vertex.PoseStack;

// ItemInHandRendererMixin lowers the shield by these amounts.
public final class NoShieldOverlay extends Module {

    private final NumberSetting blocking = new NumberSetting("Blocking offset",
        "How far the shield drops whilst you block.", 0.5, 0, 0.8, 0.01).min(0);
    private final NumberSetting resting = new NumberSetting("Resting offset",
        "How far the shield drops whilst it is only held.", 0.2, 0, 0.5, 0.01).min(0);

    public NoShieldOverlay() {
        super("NoShieldOverlay", "Moves a held shield down out of your view.", Category.RENDER);
        addSettings(blocking, resting);
        searchTags("shield", "block view");
    }

    public void lowerShield(PoseStack pose, boolean isBlocking) {
        if (!isEnabled()) {
            return;
        }
        pose.translate(0, -(isBlocking ? blocking : resting).getFloat(), 0);
    }
}
