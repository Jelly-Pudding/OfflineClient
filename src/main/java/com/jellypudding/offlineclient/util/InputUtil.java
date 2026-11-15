package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.setting.Bind;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.BooleanSupplier;

// The real state of input devices. A module forcing a mapping down is ignored here.
public final class InputUtil {

    // Degrees of turn per unit of mouse movement. The same factor the game uses.
    public static final double MOUSE_TURN = 0.15;

    // Vanilla repeats a held right click this many ticks apart.
    public static final int USE_DELAY = 4;

    // Key codes for letters are the lower case character.
    private static final int UNDO_LETTER = 'z';

    // The mouse buttons that are down by their numbers.
    private static final BitSet HELD_BUTTONS = new BitSet();

    // The game sets the right click delay on every use and counts it down once a tick.
    public static void capUseDelay(int ticks) {
        OfflineClient.MC.rightClickDelay = Math.min(OfflineClient.MC.rightClickDelay, Math.max(0, ticks));
    }

    private InputUtil() {
    }

    // 26.3 numbers mouse buttons the SDL way where left is one and right is three.
    public static boolean isLeft(int button) {
        return button == InputConstants.MOUSE_BUTTON_LEFT;
    }

    public static boolean isRight(int button) {
        return button == InputConstants.MOUSE_BUTTON_RIGHT;
    }

    public static boolean isMiddle(int button) {
        return button == InputConstants.MOUSE_BUTTON_MIDDLE;
    }

    // Ctrl Z or Cmd Z on a Mac. Matched on the letter the layout types the way the game matches copy.
    public static boolean isUndo(KeyEvent event) {
        return event.shortcutKey() == UNDO_LETTER && event.hasControlDownWithQuirk()
            && !event.hasShiftDown() && !event.hasAltDown();
    }

    // The undo keys as the player reads them.
    public static String undoKeys() {
        return InputQuirks.REPLACE_CTRL_KEY_WITH_CMD_KEY ? "Cmd Z" : "Ctrl Z";
    }

    // True for a press the game takes as a debug combo. Only a key one of its debug keys sits on
    // counts whilst the debug key is held.
    public static boolean isDebugCombo(KeyEvent event) {
        Options options = OfflineClient.MC.options;
        return options.keyDebugModifier.isDown()
            && Arrays.stream(options.debugKeys).anyMatch(combo -> combo.matches(event));
    }

    // True when the control sits on the key or button of the input. An unbound control sits on none.
    public static boolean isOn(KeyMapping mapping, Bind input) {
        return mapping.matches(input.gameKey());
    }

    // Every press and release of a mouse button comes through here. The game itself only
    // remembers the first three and none of them under a screen.
    public static void trackButton(int button, boolean down) {
        if (button > 0) {
            HELD_BUTTONS.set(button, down);
        }
    }

    // True whilst the button is really down with or without a screen open.
    public static boolean buttonDown(int button) {
        return HELD_BUTTONS.get(button);
    }

    // A button let go outside the window sends no release. Without the window none is held.
    public static void releaseButtons() {
        HELD_BUTTONS.clear();
    }

    // Holds a key down on behalf of a module. Use and attack are toggles under
    // the accessibility options where setting them down flips them instead.
    public static void hold(KeyMapping mapping) {
        if (!mapping.isDown()) {
            mapping.setDown(true);
        }
    }

    // Lets go of a key a module was holding without really lifting a finger off it.
    public static void release(KeyMapping mapping) {
        setHeld(mapping, physicallyHeld(mapping));
    }

    // Sets a mapping up or down. A toggle bound key throws away a plain false and flips on a true.
    public static void setHeld(KeyMapping mapping, boolean down) {
        mapping.setDown(down);
        if (mapping.isDown() != down) {
            mapping.setDown(true);
        }
    }

    // True whilst the player really holds any of the four movement keys.
    public static boolean steering() {
        Options options = OfflineClient.MC.options;
        return physicallyHeld(options.keyUp) || physicallyHeld(options.keyDown) || physicallyHeld(options.keyLeft)
            || physicallyHeld(options.keyRight);
    }

    // True only whilst the player really holds the bound key or button.
    public static boolean physicallyHeld(KeyMapping mapping) {
        InputConstants.Key key = mapping.key;
        return switch (key.getType()) {
            case KEYBOARD -> InputConstants.isKeyDown(key.getValue());
            // Use and attack are mouse bound by default.
            case MOUSE -> buttonHeld(key.getValue());
        };
    }

    // A click made under a screen never counts for one of the game's own controls.
    private static boolean buttonHeld(int button) {
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> OfflineClient.MC.mouseHandler.isLeftPressed();
            case InputConstants.MOUSE_BUTTON_RIGHT -> OfflineClient.MC.mouseHandler.isRightPressed();
            case InputConstants.MOUSE_BUTTON_MIDDLE -> OfflineClient.MC.mouseHandler.isMiddlePressed();
            default -> false;
        };
    }

    // A copy of an input record with only the sneak flag changed.
    public static Input withShift(Input input, boolean shift) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(),
            input.jump(), shift, input.sprint());
    }

    // Turns the sneak flag on in an input packet on its way out.
    public static void keepShift(PacketSendEvent event) {
        if (event.getPacket() instanceof ServerboundPlayerInputPacket packet && !packet.input().shift()) {
            event.setPacket(new ServerboundPlayerInputPacket(withShift(packet.input(), true)));
        }
    }

    // Tells the server the sneak flag by hand. Vanilla only resends the input on a change.
    public static void sendShift(boolean shift) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            player.connection.send(new ServerboundPlayerInputPacket(
                withShift(player.getLastSentInput(), shift)));
        }
    }

    // Runs an action as a sneaking player on both sides and lets go straight after.
    // The server handles all three packets before the player ticks and never crouches.
    public static boolean whileSneaking(BooleanSupplier action) {
        return withSneak(true, action);
    }

    // Runs an action standing up on both sides whilst the player holds sneak. A sneaking
    // player with something in hand uses the item against a block instead of the block.
    public static boolean whileStanding(BooleanSupplier action) {
        return withSneak(false, action);
    }

    private static boolean withSneak(boolean shift, BooleanSupplier action) {
        LocalPlayer player = OfflineClient.MC.player;
        Input held = player.input.keyPresses;
        boolean told = player.getLastSentInput().shift();
        if (told != shift) {
            sendShift(shift);
        }
        player.input.keyPresses = withShift(held, shift);
        try {
            return action.getAsBoolean();
        } finally {
            player.input.keyPresses = held;
            if (told != shift) {
                sendShift(told);
            }
        }
    }

    // Uses the item in the main hand. The arm swings when the game takes the use.
    public static boolean useMainHand() {
        Minecraft mc = OfflineClient.MC;
        if (!mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND).consumesAction()) {
            return false;
        }
        SwingMode.swingUse(InteractionHand.MAIN_HAND);
        return true;
    }
}
