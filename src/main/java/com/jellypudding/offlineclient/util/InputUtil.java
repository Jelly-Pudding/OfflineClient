package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

// The real state of the keyboard. A module forcing a mapping down is ignored here.
public final class InputUtil {

    // Degrees of turn per unit of mouse movement. The same factor the game uses.
    public static final double MOUSE_TURN = 0.15;

    private InputUtil() {
    }

    // True when the raw GLFW code is the key the mapping is bound to.
    public static boolean isKey(KeyMapping mapping, int code) {
        InputConstants.Key key = mapping.key;
        return key.getType() == InputConstants.Type.KEYSYM && key.getValue() == code;
    }

    // Lets go of a key a module was holding down without lifting a finger that is really on it.
    public static void release(KeyMapping mapping) {
        mapping.setDown(physicallyHeld(mapping));
    }

    // True only whilst the player really holds the bound key. A mouse bind falls back to the mapping.
    public static boolean physicallyHeld(KeyMapping mapping) {
        InputConstants.Key key = mapping.key;
        if (key.getType() != InputConstants.Type.KEYSYM) {
            return mapping.isDown();
        }
        return InputConstants.isKeyDown(OfflineClient.MC.getWindow(), key.getValue());
    }
}
