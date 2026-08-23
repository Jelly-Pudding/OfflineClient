package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;

// The behaviour lives in GameRendererMixin which skips the hurt tilt.
public final class NoHurtCam extends Module {

    public NoHurtCam() {
        super("NoHurtCam", "Removes the camera tilt when you take damage.", Category.RENDER);
        searchTags("hurt cam", "damage shake", "screen shake");
    }
}
