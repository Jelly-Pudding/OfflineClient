package com.jellypudding.offlineclient.event.events;

import com.jellypudding.offlineclient.event.Event;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class KeyPressEvent extends Event {

    private final Bind input;
    private final int action;

    // Null until something needs to know.
    private Boolean claimed;
    private boolean taken;

    public KeyPressEvent(Bind input, int action) {
        this.input = input;
        this.action = action;
    }

    // A mouse button gives the code of no key.
    public int getKey() {
        return input.isMouse() ? KeybindSetting.UNBOUND : input.code();
    }

    public int getAction() {
        return action;
    }

    public boolean presses(KeybindSetting bind) {
        return action == InputConstants.PRESS && isFor(bind);
    }

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

    public boolean isFor(KeyMapping mapping) {
        return InputUtil.isOn(mapping, input);
    }

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
