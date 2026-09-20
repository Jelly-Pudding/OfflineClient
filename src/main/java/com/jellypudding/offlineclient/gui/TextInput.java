package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.components.events.GuiEventListener;

// Typed characters only reach a screen whilst the game has text input running
// for it. Every field the client draws itself has to ask for that.
public final class TextInput {

    private final GuiEventListener owner;
    private boolean active;

    public TextInput(GuiEventListener owner) {
        this.owner = owner;
    }

    public void set(boolean wanted) {
        if (wanted == active) {
            return;
        }
        active = wanted;
        OfflineClient.MC.onTextInputFocusChange(owner, wanted);
    }
}
