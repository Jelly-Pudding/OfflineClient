package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
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

// Your keys and mouse buttons as a small keyboard that lights up under your fingers. A key of
// the game reads the binding it stands for and follows a rebind. A key of your own is typed one
// to a row with what it says.
public final class KeystrokesElement extends HudElement {

    private static final int KEY = 24;
    private static final int GAP = 2;
    private static final int ACROSS = 3;
    private static final int WIDTH = ACROSS * KEY + (ACROSS - 1) * GAP;
    // The jump bar and every row of small keys are half as tall as a key.
    private static final int BAR_HEIGHT = 12;
    // Kept clear between a label and the sides of its key.
    private static final int LABEL_ROOM = 1;
    // Between the name of a button and its clicks a second under it.
    private static final int LINE_GAP = 3;
    // A key at rest lets the game show through more than a held one.
    private static final float RESTING_OPACITY = 0.5f;
    private static final float HELD_OPACITY = 0.85f;
    // How many keys of your own there are rows for. Four rows of three.
    private static final int OWN_KEYS = 12;

    // One key with its place and size before any stretch. Rate is what it writes under its name
    // and null for a key that writes nothing there. A key that is not known never lights up.
    private record Key(String label, boolean held, String rate, boolean known, int x, int y, int width, int height) {
    }

    // The settings for one key of your own. The key row folds its label away.
    private static final class OwnKey {

        private final TextSetting key;
        private final TextSetting label;
        // The typed key as it was last read and the bind it names. The bind is null for a name
        // that is no key.
        private String read = "";
        private Bind bind;

        private OwnKey(int number, NumberSetting count) {
            int slot = number - 1;
            String name = "Own key " + number;
            key = new TextSetting(name,
                "The key or mouse button it draws such as r or mouse4 or ctrl+g. Click to type it.", "")
                .under(count, () -> slot < count.getInt());
            label = new TextSetting(name + " label", "What the key says. Left blank it says the name of the key.", "")
                .under(key, () -> slot < count.getInt());
        }

        // The typed key is read again only once it changes. A name that is no key is told in chat
        // whilst the player is the one changing it.
        private void reread(boolean tell) {
            String typed = key.getValue().trim();
            if (typed.equals(read)) {
                return;
            }
            read = typed;
            bind = typed.isEmpty() ? null : Bind.parse(typed);
            if (bind == null && !typed.isEmpty() && tell) {
                ChatUtil.error(Bind.unknown(typed));
            }
        }

        // False whilst nothing is typed or the key typed is none.
        private boolean draws() {
            return !read.isEmpty() && (bind == null || bind.isBound());
        }

        // A name that is no key is drawn in red as it was typed.
        private Key cap(int x, int y, int width) {
            if (bind == null) {
                return new Key(read, false, null, false, x, y, width, BAR_HEIGHT);
            }
            String says = label.getValue().trim();
            return new Key(says.isEmpty() ? bind.name() : says, bind.isHeld(), null, true, x, y, width, BAR_HEIGHT);
        }
    }

    private final BoolSetting movement = new BoolSetting("Movement keys",
        "Draws the keys you walk and jump with.", true);
    private final BoolSetting sneak = new BoolSetting("Sneak key",
        "Draws your sneak key under the jump bar.", true).under(movement);
    private final BoolSetting sprint = new BoolSetting("Sprint key",
        "Draws your sprint key under the jump bar.", false).under(movement);
    private final BoolSetting buttons = new BoolSetting("Mouse buttons",
        "Draws the attack and use buttons.", true);
    private final BoolSetting clicks = new BoolSetting("Clicks per second",
        "Writes how many times a second you click each mouse button.", true).under(buttons);
    private final BoolSetting actions = new BoolSetting("Action keys",
        "Draws the keys that drop an item and swap hands and open your inventory.", false);
    private final BoolSetting hotbar = new BoolSetting("Hotbar keys",
        "Draws your nine hotbar keys in three rows.", false);
    private final NumberSetting ownCount = new NumberSetting("Own keys",
        "How many keys of your own it draws under the rest. A row opens for each.", 0, 0, OWN_KEYS, 1)
        .max(OWN_KEYS);
    private final OwnKey[] own = new OwnKey[OWN_KEYS];
    private final ColorSetting color = new ColorSetting("Keystroke colour",
        "Colour of a key you are not pressing.", 0, 0, 0, false);
    private final ColorSetting heldColor = new ColorSetting("Keystroke pressed colour",
        "Colour of a key whilst you hold it down.", 0, 0, 1, false);

    // False until the typed keys have been read once. Chat stays quiet about what was loaded.
    private boolean ownRead;

    public KeystrokesElement() {
        super("Keystrokes", "Your keys and mouse buttons light up as you press them.", false, 0, 62);
        add(movement, sneak, sprint, buttons, clicks, actions, hotbar, ownCount);
        for (int i = 0; i < OWN_KEYS; i++) {
            own[i] = new OwnKey(i + 1, ownCount);
            add(own[i].key, own[i].label);
        }
        add(color, heldColor);
    }

    @Override
    public boolean visible() {
        return isActive() && !layout().isEmpty();
    }

    // The chosen keys laid out from the top left. The walking keys sit over the jump bar with
    // sneak and sprint under it. The mouse buttons follow and then the rows of small keys.
    private List<Key> layout() {
        Options options = OfflineClient.MC.options;
        List<Key> laid = new ArrayList<>();
        int y = 0;
        if (movement.isOn()) {
            laid.add(cap(options.keyUp, KEY + GAP, y, KEY, KEY, false));
            y += KEY + GAP;
            y = row(laid, y, KEY, false, List.of(options.keyLeft, options.keyDown, options.keyRight));
            y = row(laid, y, BAR_HEIGHT, false, List.of(options.keyJump));
            List<KeyMapping> modifiers = new ArrayList<>(2);
            if (sneak.isOn()) {
                modifiers.add(options.keyShift);
            }
            if (sprint.isOn()) {
                modifiers.add(options.keySprint);
            }
            y = row(laid, y, BAR_HEIGHT, false, modifiers);
        }
        if (buttons.isOn()) {
            y = row(laid, y, KEY, true, List.of(options.keyAttack, options.keyUse));
        }
        if (actions.isOn()) {
            y = row(laid, y, BAR_HEIGHT, false, List.of(options.keyDrop, options.keySwapOffhand,
                options.keyInventory));
        }
        if (hotbar.isOn()) {
            List<KeyMapping> slots = List.of(options.keyHotbarSlots);
            for (int from = 0; from < slots.size(); from += ACROSS) {
                y = row(laid, y, BAR_HEIGHT, false, slots.subList(from, Math.min(from + ACROSS, slots.size())));
            }
        }
        layOwnKeys(laid, y);
        return laid;
    }

    // The keys of your own in rows of three. A row that draws nothing takes no room.
    private void layOwnKeys(List<Key> laid, int y) {
        List<OwnKey> drawn = new ArrayList<>();
        for (int i = 0; i < ownCount.getInt(); i++) {
            own[i].reread(ownRead);
            if (own[i].draws()) {
                drawn.add(own[i]);
            }
        }
        ownRead = true;
        for (int from = 0; from < drawn.size(); from += ACROSS) {
            int count = Math.min(ACROSS, drawn.size() - from);
            for (int i = 0; i < count; i++) {
                laid.add(drawn.get(from + i).cap(left(i, count), y, share(count)));
            }
            y += BAR_HEIGHT + GAP;
        }
    }

    // Lays the keys side by side across the full width. A lone key takes all of it and no keys
    // take no room. Hands back where the next row starts.
    private int row(List<Key> laid, int y, int height, boolean button, List<KeyMapping> mappings) {
        int count = mappings.size();
        if (count == 0) {
            return y;
        }
        for (int i = 0; i < count; i++) {
            laid.add(cap(mappings.get(i), left(i, count), y, share(count), height, button));
        }
        return y + height + GAP;
    }

    // How wide each of that many keys in one row is.
    private static int share(int count) {
        return (WIDTH - (count - 1) * GAP) / count;
    }

    // The last key ends flush with the side whatever the division left over.
    private static int left(int index, int count) {
        return index == count - 1 ? WIDTH - share(count) : index * (share(count) + GAP);
    }

    // A key of the game. A button can write its clicks a second.
    private Key cap(KeyMapping mapping, int x, int y, int width, int height, boolean button) {
        int rate = button && clicks.isOn() ? clicksOf(mapping) : -1;
        return new Key(nameOf(mapping), InputUtil.physicallyHeld(mapping), rate < 0 ? null : rate + " CPS", true,
            x, y, width, height);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        for (Key key : layout()) {
            int shade = key.held() ? heldColor.getColor() : color.getColor();
            context.fill(key.x(), key.y(), key.x() + key.width(), key.y() + key.height(),
                ColorUtil.fade(shade, key.held() ? HELD_OPACITY : RESTING_OPACITY));
            int ink = key.known() ? GuiTheme.contrastText(shade, GuiTheme.HUD_TEXT) : PROBLEM_INK;
            float middle = key.y() + key.height() / 2f;
            if (key.rate() == null) {
                label(context, font, key.label(), key, middle, ink);
            } else {
                float apart = (GuiTheme.TEXT_HEIGHT + LINE_GAP) / 2f;
                label(context, font, key.label(), key, middle - apart, ink);
                label(context, font, key.rate(), key, middle + apart, ink);
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

    // The bound key or button the way the client names every bind.
    private static String nameOf(KeyMapping mapping) {
        return mapping.isUnbound() ? "" : Bind.of(mapping.key).name();
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
