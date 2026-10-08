package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// A solid box over part of the screen. On top it goes on after everything else the game draws.
public final class CoverElement extends HudElement {

    // How many covers the HUD offers.
    public static final int COUNT = 3;

    // Before any stretch a cover is about one line of text.
    private static final int WIDTH = 64;
    private static final int HEIGHT = 16;

    // Stretched this far a cover is wider and taller than any screen. Beyond the edge it only
    // stays full.
    private static final double MOST_STRETCH = 512;

    // Where the covers start out. Each one sits a quarter of the screen further across.
    private static final double START_STEP = 25;
    private static final double START_Y = 30;

    private static final float PERCENT = 100f;

    private final ColorSetting color;
    private final NumberSetting opacity;
    private final BoolSetting onTop;
    private final TextSetting text;
    private final ColorSetting textColor;

    public CoverElement(int number) {
        super("Cover " + number, "A solid box that hides part of the screen such as your coordinates whilst"
            + " streaming.", false, number * START_STEP, START_Y, 1, MOST_STRETCH, Background.NONE);
        String name = getName();
        color = new ColorSetting(name + " colour", "Colour of the box.", 0, 0, 0.08f, false);
        opacity = new NumberSetting(name + " opacity",
            "How solid the box is. At a hundred nothing shows through it.", 100, 0, 100, 5, "%").min(0).max(100);
        onTop = new BoolSetting(name + " on top",
            "Draws the box over the tab list and the debug screen and any open screen.", true);
        text = new TextSetting(name + " text",
            "Words written in the middle of the box. Leave it empty for a plain box.", "");
        textColor = new ColorSetting(name + " text colour", "Colour of the words.", 0, 0, 1, false)
            .under(text, () -> !text.isBlank());
        add(color, opacity, onTop, text, textColor);
    }

    @Override
    public boolean touchesEdges() {
        return true;
    }

    // With on top switched off the box draws under the tab list and the debug screen and any
    // open screen.
    @Override
    public boolean drawsOnTop() {
        return onTop.isOn();
    }

    // A cover still hides what it covers whilst the rest of the overlay steps aside.
    @Override
    public boolean staysWhenHidden() {
        return true;
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        context.fill(0, 0, WIDTH, HEIGHT, ColorUtil.fade(color.getColor(), opacity.getFloat() / PERCENT));
        if (!text.isBlank()) {
            context.guiRenderState.up();
            label(context, font);
        }
    }

    // Centred in the box at its normal size however far the box is stretched. Lines that
    // do not fit inside the box are left off.
    private void label(GuiGraphicsExtractor context, Font font) {
        float across = (float) scaleX();
        float down = (float) scaleY();
        List<String> lines = RenderUtil.wrap(font, text.getValue().trim(), Math.round(WIDTH * across));
        int fits = Math.round(HEIGHT * down) / font.lineHeight;
        List<String> shown = lines.subList(0, Math.min(fits, lines.size()));
        context.pose().pushMatrix();
        context.pose().translate(WIDTH / 2f, HEIGHT / 2f);
        context.pose().scale(1 / across, 1 / down);
        int y = -shown.size() * font.lineHeight / 2;
        for (String line : shown) {
            write(context, font, line, -font.width(line) / 2, y, textColor.getColor());
            y += font.lineHeight;
        }
        context.pose().popMatrix();
    }

    @Override
    public int width(Font font) {
        return WIDTH;
    }

    @Override
    public int height(Font font) {
        return HEIGHT;
    }
}
