package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ClickRate;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

// Your movement keys and mouse buttons as a small keyboard that lights up under your fingers.
// Each key reads the binding it stands for and follows a rebind.
public final class KeystrokesElement extends HudElement {

    public enum Keys { MOVEMENT, CLICKS, BOTH }

    private static final int KEY = 24;
    private static final int GAP = 2;
    private static final int WIDTH = 3 * KEY + 2 * GAP;
    // The two mouse buttons share a row.
    private static final int HALF = (WIDTH - GAP) / 2;
    private static final int JUMP_HEIGHT = 12;
    // Kept clear between a label and the sides of its key.
    private static final int LABEL_ROOM = 1;
    // Between the name of a button and its clicks a second under it.
    private static final int LINE_GAP = 3;
    // A key at rest lets the game show through more than a held one.
    private static final float RESTING_OPACITY = 0.5f;
    private static final float HELD_OPACITY = 0.85f;

    // One key with its place and size before any stretch. A button can show its clicks a second.
    private record Key(KeyMapping mapping, int x, int y, int width, int height, boolean button) {
    }

    private final EnumSetting<Keys> keys = new EnumSetting<>("Keystroke keys",
        "Which keys are drawn.", Keys.BOTH)
        .describe(Keys.MOVEMENT, "The keys you walk and jump with.")
        .describe(Keys.CLICKS, "The attack and use buttons.")
        .describe(Keys.BOTH, "The walking keys with the attack and use buttons under them.");
    private final BoolSetting clicks = new BoolSetting("Clicks per second",
        "Writes how many times a second you click each mouse button.", true)
        .under(keys, Keys.CLICKS, Keys.BOTH);
    private final ColorSetting color = new ColorSetting("Keystroke colour",
        "Colour of a key you are not pressing.", 0, 0, 0, false);
    private final ColorSetting heldColor = new ColorSetting("Keystroke pressed colour",
        "Colour of a key whilst you hold it down.", 0, 0, 1, false);

    public KeystrokesElement() {
        super("Keystrokes", "Your movement keys and mouse buttons light up as you press them.", false, 0, 62);
        add(keys, clicks, color, heldColor);
    }

    // The chosen keys laid out from the top left. The walking keys sit over the jump bar and
    // the mouse buttons go under them.
    private List<Key> layout() {
        Options options = OfflineClient.MC.options;
        List<Key> laid = new ArrayList<>();
        int y = 0;
        if (!keys.is(Keys.CLICKS)) {
            laid.add(new Key(options.keyUp, KEY + GAP, y, KEY, KEY, false));
            y += KEY + GAP;
            laid.add(new Key(options.keyLeft, 0, y, KEY, KEY, false));
            laid.add(new Key(options.keyDown, KEY + GAP, y, KEY, KEY, false));
            laid.add(new Key(options.keyRight, 2 * (KEY + GAP), y, KEY, KEY, false));
            y += KEY + GAP;
            laid.add(new Key(options.keyJump, 0, y, WIDTH, JUMP_HEIGHT, false));
            y += JUMP_HEIGHT + GAP;
        }
        if (!keys.is(Keys.MOVEMENT)) {
            laid.add(new Key(options.keyAttack, 0, y, HALF, KEY, true));
            laid.add(new Key(options.keyUse, WIDTH - HALF, y, HALF, KEY, true));
        }
        return laid;
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        for (Key key : layout()) {
            boolean held = InputUtil.physicallyHeld(key.mapping());
            int shade = held ? heldColor.getColor() : color.getColor();
            context.fill(key.x(), key.y(), key.x() + key.width(), key.y() + key.height(),
                ColorUtil.fade(shade, held ? HELD_OPACITY : RESTING_OPACITY));
            int ink = GuiTheme.contrastText(shade, GuiTheme.HUD_TEXT);
            String name = nameOf(key.mapping());
            float middle = key.y() + key.height() / 2f;
            int rate = key.button() && clicks.isOn() ? clicksOf(key.mapping()) : -1;
            if (rate < 0) {
                label(context, font, name, key, middle, ink);
            } else {
                float apart = (GuiTheme.TEXT_HEIGHT + LINE_GAP) / 2f;
                label(context, font, name, key, middle - apart, ink);
                label(context, font, rate + " CPS", key, middle + apart, ink);
            }
        }
    }

    // Centred on its key with its middle at the given height and never wider than the key. The
    // writing grows with the smaller stretch and keeps its shape. A glyph advances one pixel past its ink.
    private void label(GuiGraphicsExtractor context, Font font, String text, Key key, float middle, int color) {
        float across = (float) scaleX();
        float down = (float) scaleY();
        int ink = Math.max(1, font.width(text) - 1);
        float size = Math.min(Math.min(across, down), (key.width() - 2 * LABEL_ROOM) * across / ink);
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(key.x() + key.width() / 2f - ink * size / across / 2,
            middle - GuiTheme.TEXT_HEIGHT * size / down / 2);
        pose.scale(size / across, size / down);
        write(context, font, text, 0, 0, color);
        pose.popMatrix();
    }

    // The bound key as short as it goes. The mouse buttons read LMB and RMB and MMB.
    private static String nameOf(KeyMapping mapping) {
        if (mapping.isUnbound()) {
            return "";
        }
        InputConstants.Key bound = mapping.key;
        if (bound.getType() != InputConstants.Type.MOUSE) {
            return KeybindSetting.nameOfKey(bound.getValue());
        }
        return switch (bound.getValue()) {
            case InputConstants.MOUSE_BUTTON_LEFT -> "LMB";
            case InputConstants.MOUSE_BUTTON_RIGHT -> "RMB";
            case InputConstants.MOUSE_BUTTON_MIDDLE -> "MMB";
            default -> "M" + bound.getValue();
        };
    }

    // Only mouse presses are counted. Minus one for a binding on the keyboard.
    private static int clicksOf(KeyMapping mapping) {
        InputConstants.Key bound = mapping.key;
        return bound.getType() == InputConstants.Type.MOUSE ? ClickRate.perSecond(bound.getValue()) : -1;
    }

    @Override
    public int width(Font font) {
        return WIDTH;
    }

    @Override
    public int height(Font font) {
        int bottom = 1;
        for (Key key : layout()) {
            bottom = Math.max(bottom, key.y() + key.height());
        }
        return bottom;
    }
}
