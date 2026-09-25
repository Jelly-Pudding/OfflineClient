package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class KeybindSetting extends Setting<Integer> {

    public static final int UNBOUND = 0;

    // Returned by keyFromName when the text is not a key at all.
    public static final int UNKNOWN = Integer.MIN_VALUE;

    // The jar names no constant for these three number pad scancodes.
    private static final int KEYPAD_DIVIDE = 84;
    private static final int KEYPAD_MINUS = 86;
    private static final int KEYPAD_PERIOD = 99;

    // Keys with a short name of their own. Printing and typing both read this table.
    private static final Map<String, Integer> NAMED_KEYS = Map.ofEntries(
        Map.entry("SPACE", InputConstants.KEY_SPACE),
        Map.entry("TAB", InputConstants.KEY_TAB),
        Map.entry("ENTER", InputConstants.KEY_RETURN),
        Map.entry("BACK", InputConstants.KEY_BACKSPACE),
        Map.entry("CAPS", InputConstants.KEY_CAPSLOCK),
        Map.entry("LSHIFT", InputConstants.KEY_LSHIFT),
        Map.entry("RSHIFT", InputConstants.KEY_RSHIFT),
        Map.entry("LCTRL", InputConstants.KEY_LCONTROL),
        Map.entry("RCTRL", InputConstants.KEY_RCONTROL),
        Map.entry("LALT", InputConstants.KEY_LALT),
        Map.entry("RALT", InputConstants.KEY_RALT),
        Map.entry("UP", InputConstants.KEY_UP),
        Map.entry("DOWN", InputConstants.KEY_DOWN),
        Map.entry("LEFT", InputConstants.KEY_LEFT),
        Map.entry("RIGHT", InputConstants.KEY_RIGHT),
        Map.entry("HOME", InputConstants.KEY_HOME),
        Map.entry("END", InputConstants.KEY_END),
        Map.entry("PGUP", InputConstants.KEY_PAGEUP),
        Map.entry("PGDN", InputConstants.KEY_PAGEDOWN),
        Map.entry("INSERT", InputConstants.KEY_INSERT),
        Map.entry("DELETE", InputConstants.KEY_DELETE));
    private static final Map<Integer, String> KEY_NAMES = NAMED_KEYS.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    // A key with no name anywhere prints as this followed by its code.
    private static final String RAW_PREFIX = "KEY";

    public KeybindSetting(String name, String description, int defaultKey) {
        super(name, description, defaultKey);
    }

    // True whilst the bound key is physically down. Never true whilst unbound.
    public boolean isHeld() {
        return isBound() && InputConstants.isKeyDown(value);
    }

    public boolean isBound() {
        return value != UNBOUND;
    }

    // Letters and digits run together low in the scancode table with enter
    // escape backspace and tab sitting between them and the punctuation.
    public static boolean typesCharacter(int key) {
        return key >= InputConstants.KEY_A && key <= InputConstants.KEY_0
            || key >= InputConstants.KEY_SPACE && key <= InputConstants.KEY_SLASH
            || key >= KEYPAD_DIVIDE && key <= KEYPAD_PERIOD && key != InputConstants.KEY_NUMPADENTER;
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
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'A' && c <= 'Z') {
                return InputConstants.KEY_A + (c - 'A');
            }
            // The digits run one to nine and then zero.
            if (c >= '1' && c <= '9') {
                return InputConstants.KEY_1 + (c - '1');
            }
            if (c == '0') {
                return InputConstants.KEY_0;
            }
        }
        if (name.length() <= 3 && name.charAt(0) == 'F') {
            try {
                int number = Integer.parseInt(name.substring(1));
                if (number >= 1 && number <= 12) {
                    return InputConstants.KEY_F1 + number - 1;
                }
                if (number >= 13 && number <= 24) {
                    return InputConstants.KEY_F13 + number - 13;
                }
            } catch (NumberFormatException ignored) {
            }
            return UNKNOWN;
        }
        Integer named = NAMED_KEYS.get(name);
        if (named != null) {
            return named;
        }
        if (name.startsWith(RAW_PREFIX)) {
            try {
                return Integer.parseInt(name.substring(RAW_PREFIX.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        return UNKNOWN;
    }

    // The bind a key press asks for. Delete and backspace clear it. Escape asks for
    // no change and comes back as UNKNOWN.
    public static int fromPress(int key) {
        if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
            return UNBOUND;
        }
        return key == InputConstants.KEY_ESCAPE ? UNKNOWN : key;
    }

    public String getKeyName() {
        return nameOfKey(value);
    }

    public static String nameOfKey(int key) {
        if (key == UNBOUND) {
            return "None";
        }
        String named = KEY_NAMES.get(key);
        if (named != null) {
            return named;
        }
        String name = InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
        return name.isBlank() ? RAW_PREFIX + key : name.toUpperCase(Locale.ROOT);
    }

    // A legacy config holds binds as window library codes. Keys are read by
    // scancode and the numbers differ.
    public static int fromLegacyKey(int legacy) {
        if (legacy <= 0) {
            return UNBOUND;
        }
        if (legacy >= 'A' && legacy <= 'Z') {
            return InputConstants.KEY_A + (legacy - 'A');
        }
        if (legacy >= '1' && legacy <= '9') {
            return InputConstants.KEY_1 + (legacy - '1');
        }
        if (legacy == '0') {
            return InputConstants.KEY_0;
        }
        if (legacy >= 290 && legacy <= 301) {
            return InputConstants.KEY_F1 + (legacy - 290);
        }
        if (legacy >= 302 && legacy <= 313) {
            return InputConstants.KEY_F13 + (legacy - 302);
        }
        if (legacy >= 321 && legacy <= 329) {
            return InputConstants.KEY_NUMPAD1 + (legacy - 321);
        }
        return switch (legacy) {
            case 32 -> InputConstants.KEY_SPACE;
            case 39 -> InputConstants.KEY_APOSTROPHE;
            case 44 -> InputConstants.KEY_COMMA;
            case 45 -> InputConstants.KEY_MINUS;
            case 46 -> InputConstants.KEY_PERIOD;
            case 47 -> InputConstants.KEY_SLASH;
            case 59 -> InputConstants.KEY_SEMICOLON;
            case 61 -> InputConstants.KEY_EQUALS;
            case 91 -> InputConstants.KEY_LBRACKET;
            case 92 -> InputConstants.KEY_BACKSLASH;
            case 93 -> InputConstants.KEY_RBRACKET;
            case 96 -> InputConstants.KEY_GRAVE;
            case 256 -> InputConstants.KEY_ESCAPE;
            case 257 -> InputConstants.KEY_RETURN;
            case 258 -> InputConstants.KEY_TAB;
            case 259 -> InputConstants.KEY_BACKSPACE;
            case 260 -> InputConstants.KEY_INSERT;
            case 261 -> InputConstants.KEY_DELETE;
            case 262 -> InputConstants.KEY_RIGHT;
            case 263 -> InputConstants.KEY_LEFT;
            case 264 -> InputConstants.KEY_DOWN;
            case 265 -> InputConstants.KEY_UP;
            case 266 -> InputConstants.KEY_PAGEUP;
            case 267 -> InputConstants.KEY_PAGEDOWN;
            case 268 -> InputConstants.KEY_HOME;
            case 269 -> InputConstants.KEY_END;
            case 280 -> InputConstants.KEY_CAPSLOCK;
            case 281 -> InputConstants.KEY_SCROLLLOCK;
            case 282 -> InputConstants.KEY_NUMLOCK;
            case 283 -> InputConstants.KEY_PRINTSCREEN;
            case 284 -> InputConstants.KEY_PAUSE;
            case 320 -> InputConstants.KEY_NUMPAD0;
            case 330 -> KEYPAD_PERIOD;
            case 331 -> KEYPAD_DIVIDE;
            case 332 -> InputConstants.KEY_MULTIPLY;
            case 333 -> KEYPAD_MINUS;
            case 334 -> InputConstants.KEY_ADD;
            case 335 -> InputConstants.KEY_NUMPADENTER;
            case 336 -> InputConstants.KEY_NUMPADEQUALS;
            case 340 -> InputConstants.KEY_LSHIFT;
            case 341 -> InputConstants.KEY_LCONTROL;
            case 342 -> InputConstants.KEY_LALT;
            case 343 -> InputConstants.KEY_LGUI;
            case 344 -> InputConstants.KEY_RSHIFT;
            case 345 -> InputConstants.KEY_RCONTROL;
            case 346 -> InputConstants.KEY_RALT;
            case 347 -> InputConstants.KEY_RGUI;
            default -> UNBOUND;
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

    public void migrateLegacyKey() {
        value = fromLegacyKey(value);
    }
}
