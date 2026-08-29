package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

// The real state of the keyboard. A module forcing a mapping down is ignored here.
public final class InputUtil {

    private InputUtil() {
    }

    /**
     * True only whilst the player really holds the bound key. A mouse bind
     * falls back to the mapping's own state.
     */
    // True when the raw GLFW code is the key the mapping is bound to.
    public static boolean isKey(KeyMapping mapping, int code) {
        InputConstants.Key key = mapping.key;
        return key.getType() == InputConstants.Type.KEYSYM && key.getValue() == code;
    }

    public static boolean physicallyHeld(KeyMapping mapping) {
        InputConstants.Key key = mapping.key;
        if (key.getType() != InputConstants.Type.KEYSYM) {
            return mapping.isDown();
        }
        return InputConstants.isKeyDown(OfflineClient.MC.getWindow(), key.getValue());
    }
}
