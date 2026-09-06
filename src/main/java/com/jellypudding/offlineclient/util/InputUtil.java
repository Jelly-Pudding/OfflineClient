package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

// The real state of input devices. A module forcing a mapping down is ignored here.
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

    // Holds a key down on behalf of a module. Use and attack are toggles under
    // the accessibility options where setting them down flips them instead.
    public static void hold(KeyMapping mapping) {
        if (!mapping.isDown()) {
            mapping.setDown(true);
        }
    }

    // Lets go of a key a module was holding without really lifting a finger off it.
    // A toggle bound key throws away a plain false and has to be flipped.
    public static void release(KeyMapping mapping) {
        boolean held = physicallyHeld(mapping);
        mapping.setDown(held);
        if (mapping.isDown() != held) {
            mapping.setDown(true);
        }
    }

    // True only whilst the player really holds the bound key or button.
    public static boolean physicallyHeld(KeyMapping mapping) {
        InputConstants.Key key = mapping.key;
        Window window = OfflineClient.MC.getWindow();
        return switch (key.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, key.getValue());
            // Use and attack are mouse bound by default.
            case MOUSE -> GLFW.glfwGetMouseButton(window.handle(), key.getValue()) == GLFW.GLFW_PRESS;
            default -> mapping.isDown();
        };
    }
}
