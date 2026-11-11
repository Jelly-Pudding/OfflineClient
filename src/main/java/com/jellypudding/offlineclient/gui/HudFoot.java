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

// The foot of the Overlay panel in the HUD editor. It lists what the pointer and the keys do
// there. What a right click just did takes its place for some seconds.
final class HudFoot {

    // One gesture to a line. Each fits a panel of the starting width.
    private static final List<String> HELP = List.of(
        "Drag to move",
        "Pull edges to resize",
        "Right click to reset",
        "Arrows to nudge",
        "Drag a box to pick");

    private static final int PAD_X = 5;
    private static final int PAD_Y = 3;
    // The hairline between the rows and the foot.
    private static final int RULE = 1;

    // Paragraphs wrapped to the panel. The panel asks how tall the foot is many times a frame
    // and they are only wrapped again when its width or its font changes.
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

    // What the last right click did. Undo is the change Ctrl Z would take back and null when
    // there is none.
    private record Said(Wrapped text, Defaults.Change undo, boolean done, long at, long until) {

        // Over once its time is up or the change it offers to undo cannot be taken back.
        private boolean over() {
            return System.currentTimeMillis() > until || (undo != null && !Defaults.canUndo(undo));
        }

        // Green at first when something was done and then the colour of the help. It fades
        // out at the end.
        private int colour() {
            int ink = done ? ColorUtil.lerp(GuiTheme.textDim(), GuiTheme.GREEN, Flash.strength(at + Flash.SHOWN_MS))
                : GuiTheme.textDim();
            return ColorUtil.fade(ink, Flash.strength(until));
        }
    }

    private final Wrapped help = new Wrapped(HELP);
    private Said said;

    // A right click that changed nothing says why for a moment.
    void refuse(String text) {
        say(text, null, false, Flash.SHOWN_MS);
    }

    // What a right click put back. The line says how to take it back and goes once that cannot
    // be done.
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

    // The help unless a right click just spoke.
    private List<String> lines(Font font, int width) {
        return (said == null ? help : said.text()).at(font, width);
    }
}
