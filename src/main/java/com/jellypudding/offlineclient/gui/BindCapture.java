package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.input.KeyEvent;

import java.util.function.Consumer;

public final class BindCapture {

    public static final String LISTENING = "press a key";
    public static final String HELP = "Click here and then press a key or a middle or side mouse button."
        + " Ctrl and Alt and Shift can be held with it. DELETE unbinds. ESC cancels.";

    // The modifier key that went down last. It may be the start of a combination or the bind itself.
    private int waiting = KeybindSetting.UNBOUND;

    public void begin() {
        waiting = KeybindSetting.UNBOUND;
    }

    // Delete and backspace hand over no bind and escape hands over nothing.
    public boolean keyPressed(KeyEvent event, Consumer<Bind> taker) {
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            return true;
        }
        if (Bind.isModifierKey(key)) {
            waiting = key;
            return false;
        }
        boolean clears = key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE;
        taker.accept(clears ? Bind.NONE : Bind.of(event));
        return true;
    }

    // A modifier key let go before any other key is the bind.
    public boolean keyReleased(KeyEvent event, Consumer<Bind> taker) {
        if (waiting == KeybindSetting.UNBOUND || event.key() != waiting) {
            return false;
        }
        taker.accept(Bind.ofReleased(waiting));
        return true;
    }

    // A middle or side button is the bind. Left and right stay the way to click somewhere else.
    public boolean mouseClicked(int button, Consumer<Bind> taker) {
        Bind click = Bind.ofClick(button);
        if (click.isMainButton()) {
            return false;
        }
        taker.accept(click);
        return true;
    }
}
