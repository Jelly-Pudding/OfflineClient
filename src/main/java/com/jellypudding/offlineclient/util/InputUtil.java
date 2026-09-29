package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;

import java.util.function.BooleanSupplier;

// The real state of input devices. A module forcing a mapping down is ignored here.
public final class InputUtil {

    // Degrees of turn per unit of mouse movement. The same factor the game uses.
    public static final double MOUSE_TURN = 0.15;

    // Vanilla repeats a held right click this many ticks apart.
    public static final int USE_DELAY = 4;

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

    public static boolean isKey(KeyMapping mapping, int code) {
        InputConstants.Key key = mapping.key;
        return key.getType() == InputConstants.Type.KEYBOARD && key.getValue() == code;
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
        return switch (key.getType()) {
            case KEYBOARD -> InputConstants.isKeyDown(key.getValue());
            // Use and attack are mouse bound by default.
            case MOUSE -> buttonHeld(key.getValue());
        };
    }

    private static boolean buttonHeld(int button) {
        return switch (button) {
            case InputConstants.MOUSE_BUTTON_LEFT -> OfflineClient.MC.mouseHandler.isLeftPressed();
            case InputConstants.MOUSE_BUTTON_RIGHT -> OfflineClient.MC.mouseHandler.isRightPressed();
            case InputConstants.MOUSE_BUTTON_MIDDLE -> OfflineClient.MC.mouseHandler.isMiddlePressed();
            default -> false;
        };
    }

    // A copy of an input record with only the sneak flag changed.
    static Input withShift(Input input, boolean shift) {
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
        SwingMode.swingArm(InteractionHand.MAIN_HAND);
        return true;
    }
}
