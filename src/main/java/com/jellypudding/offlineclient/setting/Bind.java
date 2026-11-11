package com.jellypudding.offlineclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.InputUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

// One key or mouse button and the modifier keys that have to be down with it. The code is a
// scancode for a key and a button number for the mouse and the two share numbers. Nothing
// tells them apart but the device.
public record Bind(InputConstants.Type device, int code, int modifiers) {

    // A code the device cannot report makes no bind. Asking whether such a key is down would
    // read past the end of the keyboard state.
    public Bind {
        int last = device == InputConstants.Type.MOUSE ? LAST_BUTTON : LAST_SCANCODE;
        if (code <= NO_KEY || code > last) {
            device = InputConstants.Type.KEYBOARD;
            code = NO_KEY;
            modifiers = 0;
        }
    }

    // A key that goes with another one. Each knows the game's mask for it and its two keys.
    public enum Modifier {
        CTRL(InputConstants.MOD_CONTROL, InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL),
        ALT(InputConstants.MOD_ALT, InputConstants.KEY_LALT, InputConstants.KEY_RALT),
        SHIFT(InputConstants.MOD_SHIFT, InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT);

        private final int gameMask;
        private final int leftKey;
        private final int rightKey;

        Modifier(int gameMask, int leftKey, int rightKey) {
            this.gameMask = gameMask;
            this.leftKey = leftKey;
            this.rightKey = rightKey;
        }

        private int bit() {
            return 1 << ordinal();
        }

        private boolean isKey(int key) {
            return key == leftKey || key == rightKey;
        }

        private boolean isDown() {
            return InputConstants.isKeyDown(leftKey) || InputConstants.isKeyDown(rightKey);
        }

        private String saveName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    // The game keeps the same code for its own unknown key.
    private static final int NO_KEY = 0;

    // The keyboard state holds 512 scancodes and a mouse button travels in one signed byte.
    private static final int LAST_SCANCODE = 511;
    private static final int LAST_BUTTON = Byte.MAX_VALUE;

    public static final Bind NONE = new Bind(InputConstants.Type.KEYBOARD, NO_KEY, 0);

    // The order a list of binds reads in. Keys before buttons and a bare key before its combinations.
    public static final Comparator<Bind> ORDER = Comparator.<Bind, InputConstants.Type>comparing(Bind::device)
        .thenComparingInt(Bind::code).thenComparingInt(Bind::modifiers);

    // The jar names no constant for these two number pad scancodes.
    private static final int KEYPAD_DIVIDE = 84;
    private static final int KEYPAD_PERIOD = 99;

    // The highest scancode a typed name is looked for under.
    private static final int LAST_KEY = InputConstants.KEY_RGUI;

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

    // The three buttons every mouse has. Any other reads as the prefix and its number.
    private static final Map<String, Integer> NAMED_BUTTONS = Map.of(
        "LMB", InputConstants.MOUSE_BUTTON_LEFT,
        "MMB", InputConstants.MOUSE_BUTTON_MIDDLE,
        "RMB", InputConstants.MOUSE_BUTTON_RIGHT);
    private static final Map<Integer, String> BUTTON_NAMES = NAMED_BUTTONS.entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    private static final String BUTTON_PREFIX = "MOUSE";

    // A key with no name anywhere prints as this followed by its code.
    private static final String RAW_PREFIX = "KEY";

    private static final String UNBOUND_NAME = "None";
    private static final char JOIN = '+';

    private static final String SAVED_KEY = "key";
    private static final String SAVED_BUTTON = "mouse";

    public static Bind key(int code) {
        return new Bind(InputConstants.Type.KEYBOARD, code, 0);
    }

    public static Bind mouse(int button) {
        return new Bind(InputConstants.Type.MOUSE, button, 0);
    }

    // The key or button one of the game's own controls sits on.
    public static Bind of(InputConstants.Key key) {
        return new Bind(key.getType(), key.getValue(), 0);
    }

    // What a key press asks for. A modifier key pressed on its own is the key and not also its
    // own modifier.
    public static Bind of(KeyEvent event) {
        return key(event.key()).with(modifiersOf(event) & ~ownModifier(event.key()));
    }

    public static Bind of(MouseButtonInfo button) {
        return mouse(button.button()).with(modifiersOf(button));
    }

    // The button with whichever modifier keys are down at this moment. For a click that arrives
    // as a bare button number.
    public static Bind ofClick(int button) {
        return mouse(button).with(heldModifiers());
    }

    public Bind with(int modifiers) {
        return isBound() ? new Bind(device, code, modifiers) : this;
    }

    public boolean isBound() {
        return code != NO_KEY;
    }

    public boolean isMouse() {
        return device == InputConstants.Type.MOUSE;
    }

    // True for the two buttons the game itself cannot be played without.
    public boolean isMainButton() {
        return isMouse() && (InputUtil.isLeft(code) || InputUtil.isRight(code));
    }

    // True whilst the key or button and every modifier of the bind is really down.
    public boolean isHeld() {
        if (!isBound()) {
            return false;
        }
        boolean down = isMouse() ? InputUtil.buttonDown(code) : InputConstants.isKeyDown(code);
        return down && (heldModifiers() & modifiers) == modifiers;
    }

    // True for the same key or button whatever the modifiers.
    public boolean sameInput(Bind other) {
        return device == other.device && code == other.code;
    }

    // True when a press answers this bind. A bind with no modifiers answers under any of them
    // and one with modifiers only under exactly its own.
    public boolean answers(Bind pressed) {
        return isBound() && sameInput(pressed) && (modifiers == 0 || modifiers == pressed.modifiers);
    }

    // The game's own value for the key or button. Its controls are matched against it.
    public InputConstants.Key gameKey() {
        return device.getOrCreate(code);
    }

    // Letters and digits run together low in the scancode table with enter escape backspace
    // and tab sitting between them and the punctuation. No combination with Ctrl or Alt types.
    public boolean typesCharacter() {
        if (isMouse() || (modifiers & ~Modifier.SHIFT.bit()) != 0) {
            return false;
        }
        return code >= InputConstants.KEY_A && code <= InputConstants.KEY_0
            || code >= InputConstants.KEY_SPACE && code <= InputConstants.KEY_SLASH
            || code >= KEYPAD_DIVIDE && code <= KEYPAD_PERIOD && code != InputConstants.KEY_NUMPADENTER;
    }

    // How the bind reads in the interface and in chat such as CTRL+G or MOUSE4. Typing the same
    // text names the same bind.
    public String name() {
        if (!isBound()) {
            return UNBOUND_NAME;
        }
        StringBuilder name = new StringBuilder();
        for (Modifier modifier : Modifier.values()) {
            if ((modifiers & modifier.bit()) != 0) {
                name.append(modifier.name()).append(JOIN);
            }
        }
        return name.append(isMouse() ? buttonName(code) : keyName(code)).toString();
    }

    // Turns typed text like "k" or "ctrl+f5" or "mouse4" or "none" into a bind. Null when the
    // text names nothing.
    public static Bind parse(String text) {
        String typed = text.trim().toUpperCase(Locale.ROOT);
        if (typed.isEmpty()) {
            return null;
        }
        if (typed.equals("NONE") || typed.equals("UNBOUND")) {
            return NONE;
        }
        // The last character may be the plus key itself.
        int cut = typed.lastIndexOf(JOIN, typed.length() - 2);
        int modifiers = 0;
        if (cut >= 0) {
            for (String word : typed.substring(0, cut).split("\\" + JOIN)) {
                Modifier modifier = modifierNamed(word);
                if (modifier == null) {
                    return null;
                }
                modifiers |= modifier.bit();
            }
        }
        Bind bare = bareNamed(typed.substring(cut + 1));
        return bare == null ? null : bare.with(modifiers);
    }

    // What chat says about typed text that names no key.
    public static String unknown(String typed) {
        return "There is no key called " + typed + ". Try g or ctrl+g or f5 or mouse4 or mmb or none.";
    }

    private static Modifier modifierNamed(String word) {
        for (Modifier modifier : Modifier.values()) {
            if (modifier.name().equals(word)) {
                return modifier;
            }
        }
        return null;
    }

    private static Bind bareNamed(String name) {
        Integer button = NAMED_BUTTONS.get(name);
        if (button != null) {
            return mouse(button);
        }
        if (name.startsWith(BUTTON_PREFIX)) {
            int number = number(name.substring(BUTTON_PREFIX.length()));
            // The first three buttons only go by their names. Players number them in different orders.
            return number > InputConstants.MOUSE_BUTTON_RIGHT && number <= LAST_BUTTON ? mouse(number) : null;
        }
        int key = keyNamed(name);
        return key == NO_KEY ? null : key(key);
    }

    // The scancode a name stands for. No key when it stands for none.
    private static int keyNamed(String name) {
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
            int number = number(name.substring(1));
            if (number >= 1 && number <= 12) {
                return InputConstants.KEY_F1 + number - 1;
            }
            if (number >= 13 && number <= 24) {
                return InputConstants.KEY_F13 + number - 13;
            }
        }
        Integer named = NAMED_KEYS.get(name);
        if (named != null) {
            return named;
        }
        if (name.startsWith(RAW_PREFIX)) {
            int code = number(name.substring(RAW_PREFIX.length()));
            if (code > NO_KEY && code <= LAST_SCANCODE) {
                return code;
            }
        }
        // Whatever a key prints as can be typed back with its spaces left out.
        for (int key = NO_KEY + 1; key <= LAST_KEY; key++) {
            if (keyName(key).replace(" ", "").equals(name)) {
                return key;
            }
        }
        return NO_KEY;
    }

    // Minus one for text that is no whole number.
    private static int number(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String keyName(int key) {
        String named = KEY_NAMES.get(key);
        if (named != null) {
            return named;
        }
        InputConstants.Key gameKey = InputConstants.Type.KEYBOARD.getOrCreate(key);
        String name = gameKey.getDisplayName().getString();
        // A key the game has no word for hands its own id back.
        return name.isBlank() || name.equals(gameKey.getName()) ? RAW_PREFIX + key : name.toUpperCase(Locale.ROOT);
    }

    private static String buttonName(int button) {
        String named = BUTTON_NAMES.get(button);
        return named == null ? BUTTON_PREFIX + button : named;
    }

    private static int modifiersOf(InputWithModifiers event) {
        int held = 0;
        for (Modifier modifier : Modifier.values()) {
            if ((event.modifiers() & modifier.gameMask) != 0) {
                held |= modifier.bit();
            }
        }
        return held;
    }

    private static int ownModifier(int key) {
        for (Modifier modifier : Modifier.values()) {
            if (modifier.isKey(key)) {
                return modifier.bit();
            }
        }
        return 0;
    }

    public static boolean isModifierKey(int key) {
        return ownModifier(key) != 0;
    }

    // The modifier keys that are down at this moment.
    private static int heldModifiers() {
        int held = 0;
        for (Modifier modifier : Modifier.values()) {
            if (modifier.isDown()) {
                held |= modifier.bit();
            }
        }
        return held;
    }

    // The same key with the modifiers that are still down. For a modifier key bound by letting
    // it go.
    public static Bind ofReleased(int key) {
        return key(key).with(heldModifiers() & ~ownModifier(key));
    }

    public JsonElement toJson() {
        JsonObject saved = new JsonObject();
        if (isBound()) {
            saved.addProperty(isMouse() ? SAVED_BUTTON : SAVED_KEY, code);
            for (Modifier modifier : Modifier.values()) {
                if ((modifiers & modifier.bit()) != 0) {
                    saved.addProperty(modifier.saveName(), true);
                }
            }
        }
        return saved;
    }

    // A bare number is a keyboard key. Null for anything that is no bind.
    public static Bind fromJson(JsonElement json) {
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            return key(json.getAsInt());
        }
        if (!json.isJsonObject()) {
            return null;
        }
        JsonObject saved = json.getAsJsonObject();
        Bind bare = saved.has(SAVED_BUTTON) ? mouse(saved.get(SAVED_BUTTON).getAsInt())
            : saved.has(SAVED_KEY) ? key(saved.get(SAVED_KEY).getAsInt()) : NONE;
        int modifiers = 0;
        for (Modifier modifier : Modifier.values()) {
            if (saved.has(modifier.saveName()) && saved.get(modifier.saveName()).getAsBoolean()) {
                modifiers |= modifier.bit();
            }
        }
        return bare.with(modifiers);
    }
}
