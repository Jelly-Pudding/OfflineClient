package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

// Fades a mark out as the camera closes in on it. A box right in front of you would
// otherwise hide what it marks. The strength is the square of how far into the fade
// distance the mark still is.
public final class NearFade {

    // Below this share of full strength a faded mark is not worth drawing.
    private static final float FAINT = 0.075f;

    private final NumberSetting distance;

    public NearFade(double defaultBlocks) {
        distance = new NumberSetting("Near fade",
            "Marks closer to the camera than this fade out. Nought keeps them solid.",
            defaultBlocks, 0, 12, 0.5, " blocks").min(0);
    }

    public NumberSetting setting() {
        return distance;
    }

    // One from the fade distance out and less inside it. Nought for a mark too faint to draw.
    public float strengthAt(Vec3 centre) {
        double fade = distance.getValue();
        if (fade <= 0) {
            return 1;
        }
        double away = DrawBatch.cameraPos().distanceToSqr(centre);
        if (away >= fade * fade) {
            return 1;
        }
        float strength = (float) (away / (fade * fade));
        return strength < FAINT ? 0 : strength;
    }
}
