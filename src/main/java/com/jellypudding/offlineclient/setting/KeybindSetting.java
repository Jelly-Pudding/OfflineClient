package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class KeybindSetting extends Setting<Integer> {

    public static final int UNBOUND = GLFW.GLFW_KEY_UNKNOWN;

    // Returned by keyFromName when the text is not a key at all.
    public static final int UNKNOWN = Integer.MIN_VALUE;

    public KeybindSetting(String name, String description, int defaultKey) {
        super(name, description, defaultKey);
    }

    public boolean isBound() {
        return value != UNBOUND;
    }

    // Turns typed text like "k" or "f5" or "none" into a key code.
    public static int keyFromName(String text) {
        String name = text.trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return UNKNOWN;
        }
        if (name.equals("NONE") || name.equals("UNBOUND")) {
            return UNBOUND;
        }
        // GLFW codes for letters and digits match their ASCII codes.
        if (name.length() == 1 && Character.isLetterOrDigit(name.charAt(0))) {
            return name.charAt(0);
        }
        if (name.length() <= 3 && name.charAt(0) == 'F') {
            try {
                int number = Integer.parseInt(name.substring(1));
                if (number >= 1 && number <= 25) {
                    return GLFW.GLFW_KEY_F1 + number - 1;
                }
            } catch (NumberFormatException ignored) {
            }
            return UNKNOWN;
        }
        return switch (name) {
            case "SPACE" -> GLFW.GLFW_KEY_SPACE;
            case "TAB" -> GLFW.GLFW_KEY_TAB;
            case "ENTER" -> GLFW.GLFW_KEY_ENTER;
            case "LSHIFT" -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case "RSHIFT" -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case "LCTRL" -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case "RCTRL" -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case "LALT" -> GLFW.GLFW_KEY_LEFT_ALT;
            case "RALT" -> GLFW.GLFW_KEY_RIGHT_ALT;
            case "UP" -> GLFW.GLFW_KEY_UP;
            case "DOWN" -> GLFW.GLFW_KEY_DOWN;
            case "LEFT" -> GLFW.GLFW_KEY_LEFT;
            case "RIGHT" -> GLFW.GLFW_KEY_RIGHT;
            case "HOME" -> GLFW.GLFW_KEY_HOME;
            case "END" -> GLFW.GLFW_KEY_END;
            case "PGUP" -> GLFW.GLFW_KEY_PAGE_UP;
            case "PGDN" -> GLFW.GLFW_KEY_PAGE_DOWN;
            case "INSERT" -> GLFW.GLFW_KEY_INSERT;
            case "DELETE" -> GLFW.GLFW_KEY_DELETE;
            default -> UNKNOWN;
        };
    }

    public String getKeyName() {
        if (!isBound()) {
            return "None";
        }
        String name = GLFW.glfwGetKeyName(value, 0);
        if (name != null) {
            return name.toUpperCase(Locale.ROOT);
        }
        return switch (value) {
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            case GLFW.GLFW_KEY_ENTER -> "ENTER";
            case GLFW.GLFW_KEY_BACKSPACE -> "BACK";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPS";
            case GLFW.GLFW_KEY_UP -> "UP";
            case GLFW.GLFW_KEY_DOWN -> "DOWN";
            case GLFW.GLFW_KEY_LEFT -> "LEFT";
            case GLFW.GLFW_KEY_RIGHT -> "RIGHT";
            case GLFW.GLFW_KEY_HOME -> "HOME";
            case GLFW.GLFW_KEY_END -> "END";
            case GLFW.GLFW_KEY_PAGE_UP -> "PGUP";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "PGDN";
            case GLFW.GLFW_KEY_INSERT -> "INSERT";
            case GLFW.GLFW_KEY_DELETE -> "DELETE";
            default -> value >= GLFW.GLFW_KEY_F1 && value <= GLFW.GLFW_KEY_F25
                ? "F" + (value - GLFW.GLFW_KEY_F1 + 1) : "KEY" + value;
        };
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value);
    }

    @Override
    public void fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            value = json.getAsInt();
        }
    }
}
