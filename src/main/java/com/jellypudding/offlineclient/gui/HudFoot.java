package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

final class HudFoot {

    // Each fits a panel of the starting width.
    private static final List<String> HELP = List.of(
        "Drag to move",
        "Pull edges to resize",
        "Right click to reset",
        "Arrows to nudge",
        "Drag a box to pick");

    private static final int PAD_X = 5;
    private static final int PAD_Y = 3;
    private static final int RULE = 1;

    // The panel asks how tall the foot is many times a frame and the paragraphs are only wrapped
    // again when its width or its font changes.
    private static final class Wrapped {

        private final List<String> paragraphs;
        private int width = -1;
        private Font font;
        private List<String> lines = List.of();

        private Wrapped(List<String> paragraphs) {
            this.paragraphs = paragraphs;
        }

        private List<String> at(Font font, int width) {
            if (width != this.width || font != this.font) {
                this.width = width;
                this.font = font;
                lines = new ArrayList<>();
                for (String paragraph : paragraphs) {
                    lines.addAll(RenderUtil.wrap(font, paragraph, width - 2 * PAD_X));
                }
            }
            return lines;
        }
    }

    // Undo is null when there is nothing Ctrl Z would take back.
    private record Said(Wrapped text, Defaults.Change undo, boolean done, long at, long until) {

        private boolean over() {
            return System.currentTimeMillis() > until || (undo != null && !Defaults.canUndo(undo));
        }

        private int colour() {
            int ink = done ? ColorUtil.lerp(GuiTheme.textDim(), GuiTheme.GREEN, Flash.strength(at + Flash.SHOWN_MS))
                : GuiTheme.textDim();
            return ColorUtil.fade(ink, Flash.strength(until));
        }
    }

    private final Wrapped help = new Wrapped(HELP);
    private Said said;

    void refuse(String text) {
        say(text, null, false, Flash.SHOWN_MS);
    }

    void report(String text, Defaults.Change change) {
        say(text + " Press " + InputUtil.undoKeys() + " to undo.", change, true, ResetFoot.OFFER_MS);
    }

    private void say(String text, Defaults.Change undo, boolean done, long shown) {
        long now = System.currentTimeMillis();
        said = new Said(new Wrapped(List.of(text)), undo, done, now, now + shown);
    }

    // As tall as the help however short the line that stands in for it. The panel keeps its
    // size when a right click speaks.
    int height(Font font, int width) {
        int rows = Math.max(help.at(font, width).size(), lines(font, width).size());
        return RULE + 2 * PAD_Y + rows * pitch(font) - 1;
    }

    void render(GuiGraphicsExtractor context, Font font, int x, int top, int width) {
        if (said != null && said.over()) {
            said = null;
        }
        context.fill(x, top, x + width, top + RULE, GuiTheme.RULE);
        int colour = said == null ? GuiTheme.textFaint() : said.colour();
        int y = top + RULE + PAD_Y;
        for (String line : lines(font, width)) {
            context.text(font, line, x + PAD_X, y, colour, false);
            y += pitch(font);
        }
    }

    private static int pitch(Font font) {
        return font.lineHeight + 1;
    }

    private List<String> lines(Font font, int width) {
        return (said == null ? help : said.text()).at(font, width);
    }
}
