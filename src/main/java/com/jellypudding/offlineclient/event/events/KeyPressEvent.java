package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

// A key or a mouse button going down or coming up. The input carries the modifier keys held
// with it and the action is one of the InputConstants actions.
public final class KeyPressEvent extends Event {

    private final Bind input;
    private final int action;

    // Whether a bind of the client asks for this input with exactly the modifiers held. Null
    // until something needs to know.
    private Boolean claimed;
    private boolean taken;

    public KeyPressEvent(Bind input, int action) {
        this.input = input;
        this.action = action;
    }

    // The scancode of a keyboard key. A mouse button gives the code of no key.
    public int getKey() {
        return input.isMouse() ? KeybindSetting.UNBOUND : input.code();
    }

    public int getAction() {
        return action;
    }

    // True on a press of what the bind holds. No event carries an unbound input.
    public boolean presses(KeybindSetting bind) {
        return action == InputConstants.PRESS && isFor(bind);
    }

    // True for a press or a repeat or a release of what the bind holds.
    public boolean isFor(KeybindSetting bind) {
        return isFor(bind.getValue());
    }

    // A bind with no modifiers answers whatever is held with its key. It gives way to another
    // bind that asks for the key with exactly the modifiers held.
    public boolean isFor(Bind bind) {
        if (!bind.answers(input) || bind.modifiers() != input.modifiers() && claimed()) {
            return false;
        }
        taken = true;
        return true;
    }

    // True for the key or button one of the game's own controls sits on whatever is held with it.
    public boolean isFor(KeyMapping mapping) {
        return InputUtil.isOn(mapping, input);
    }

    // True once a bind of the client has answered.
    public boolean wasTaken() {
        return taken;
    }

    private boolean claimed() {
        if (claimed == null) {
            claimed = input.modifiers() != 0 && KeyUse.asksFor(input);
        }
        return claimed;
    }
}
