package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// What the bar along the foot of a window says this frame. The first thing asked for wins.
// What an action just did stays a moment before the help comes back.
final class Footer {

    private static final long STATUS_MS = 2500;

    private String text;
    private int colour;
    private String status;
    private int statusColour;
    private long statusUntil;

    // Once at the start of a frame.
    void begin() {
        text = null;
    }

    void show(String words, int colour) {
        if (text == null) {
            text = words;
            this.colour = colour;
        }
    }

    // Shows what an action just did whilst it is fresh. The pointer still rests on the button
    // that did it and this beats its help.
    void showStatus() {
        if (status != null && System.currentTimeMillis() < statusUntil) {
            show(status, statusColour);
        }
    }

    void say(String words) {
        say(words, GuiTheme.GREEN);
    }

    // What went wrong is said in red.
    void say(String words, int colour) {
        status = words;
        statusColour = colour;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    // The hint fills the bar when nothing else asked.
    void render(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, String hint) {
        show(hint, GuiTheme.textFaint());
        WindowParts.footer(context, font, x, y, w, h, text, colour);
    }
}
