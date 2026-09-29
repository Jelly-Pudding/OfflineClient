package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.LiveText;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// Any text you like with a handful of words swapped in for live values.
public final class TextElement extends HudElement {

    private static final int LINE_GAP = 1;

    private final TextSetting text = new TextSetting("Text",
        "What it says. Type \\n to start a new line. You can use " + LiveText.WORDS + ".",
        "Hello {username}");
    private final ColorSetting color = new ColorSetting("Text colour",
        "Colour of the text.", 190, false);
    private final BoolSetting shadow = new BoolSetting("Text shadow",
        "Draw a shadow behind it.", true);

    public TextElement() {
        super("Custom text", "Any text you type. Words in braces such as {fps} turn into live values.",
            false, 50, 4);
        add(text, color, shadow);
    }

    @Override
    public boolean visible() {
        return isActive() && !text.getValue().isBlank();
    }

    private List<String> lines() {
        return LiveText.lines(text.getValue());
    }

    private static int widest(Font font, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    // Each line lines up with the side of the screen the element is anchored to.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<String> lines = lines();
        int widest = widest(font, lines);
        int y = 0;
        for (String line : lines) {
            int x = (int) Math.round((widest - font.width(line)) * alignment());
            context.text(font, line, x, y, color.getColor(), shadow.isOn());
            y += font.lineHeight + LINE_GAP;
        }
    }

    @Override
    public int width(Font font) {
        return Math.max(1, widest(font, lines()));
    }

    @Override
    public int height(Font font) {
        // Text made only of breaks splits into nothing and still takes a line.
        int count = Math.max(1, lines().size());
        return count * font.lineHeight + (count - 1) * LINE_GAP;
    }
}
