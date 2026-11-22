package com.jellypudding.offlineclient.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// The first thing asked for wins.
final class Footer {

    private static final long STATUS_MS = 2500;

    private String text;
    private int colour;
    private String status;
    private int statusColour;
    private long statusUntil;

    void begin() {
        text = null;
    }

    void show(String words, int colour) {
        if (text == null) {
            text = words;
            this.colour = colour;
        }
    }

    // The pointer still rests on the button that did it and this beats its help.
    void showStatus() {
        if (status != null && System.currentTimeMillis() < statusUntil) {
            show(status, statusColour);
        }
    }

    void say(String words) {
        say(words, GuiTheme.GREEN);
    }

    void say(String words, int colour) {
        status = words;
        statusColour = colour;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    void render(GuiGraphicsExtractor context, Font font, int x, int y, int w, int h, String hint) {
        show(hint, GuiTheme.textFaint());
        WindowParts.footer(context, font, x, y, w, h, text, colour);
    }
}
