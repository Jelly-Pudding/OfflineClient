package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.modules.misc.NameProtect;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TextMarkup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Any text you like with a handful of words swapped in for live values and colour codes read the
// way holograms read them.
public final class TextElement extends HudElement {

    // How many custom texts the HUD offers.
    public static final int COUNT = 3;

    // Where the texts start out. The first sits at the top in the middle and each one after it
    // a little lower.
    private static final double START_X = 50;
    private static final double START_Y = 4;
    private static final double START_STEP = 6;

    private static final String GREETING = "Hello {username}";

    private final TextSetting text;
    private final ColorSetting color;
    // The lines are measured and drawn several times a frame.
    private final RowCache<Component> lines = new RowCache<>(this::build);

    public TextElement(int number) {
        super("Custom text " + number, "Any text you type. Words in braces such as {fps} turn into live values.",
            false, START_X, START_Y + (number - 1) * START_STEP);
        String name = getName();
        text = new TextSetting(name + " text", "What it says. Type \\n to start a new line and & with a colour code"
            + " such as &6 or &#ff8800 to colour what follows. You can use " + LiveText.WORDS + ".",
            number == 1 ? GREETING : "");
        color = new ColorSetting(name + " colour", "Colour of the words that no colour code changes.", 190, false);
        add(text, color);
    }

    // Live words that fill to nothing and colour codes or spaces alone leave nothing to show.
    @Override
    public boolean visible() {
        return isActive() && lines.get().stream().anyMatch(line -> !line.getString().isBlank());
    }

    // The live words are filled in after the codes are read. NameProtect then hides names in
    // them as it does in chat.
    private List<Component> build() {
        NameProtect nameProtect = Modules.get(NameProtect.class);
        List<Component> built = new ArrayList<>();
        for (Component line : TextMarkup.lines(text.getValue(), LiveText::fill)) {
            built.add(nameProtect == null ? line : nameProtect.filter(line));
        }
        return built;
    }

    // A rainbow runs down the lines.
    private List<TextRow> rows(Font font) {
        List<Component> filled = lines.get();
        List<TextRow> rows = new ArrayList<>(filled.size());
        for (int i = 0; i < filled.size(); i++) {
            rows.add(rowOf(font, filled.get(i), color.rowColor(i)));
        }
        return rows;
    }

    // Each run of one style becomes a part. The font reads the bold and italic and other marks as
    // codes in front of the words and a run without a colour of its own takes the given one.
    private static TextRow rowOf(Font font, Component line, int color) {
        List<String> parts = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        line.visit((style, words) -> {
            parts.add(marks(style) + words);
            TextColor own = style.getColor();
            colors.add(own == null ? color : ARGB.opaque(own.getValue()));
            return Optional.empty();
        }, Style.EMPTY);
        return TextRow.of(font, parts, colors);
    }

    private static String marks(Style style) {
        StringBuilder marks = new StringBuilder();
        if (style.isBold()) {
            marks.append(ChatFormatting.BOLD);
        }
        if (style.isItalic()) {
            marks.append(ChatFormatting.ITALIC);
        }
        if (style.isUnderlined()) {
            marks.append(ChatFormatting.UNDERLINE);
        }
        if (style.isStrikethrough()) {
            marks.append(ChatFormatting.STRIKETHROUGH);
        }
        if (style.isObfuscated()) {
            marks.append(ChatFormatting.OBFUSCATED);
        }
        return marks.toString();
    }

    // Each line lines up with the side of the screen the element is anchored to.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        TextRow.draw(context, font, rows(font), alignment());
    }

    @Override
    public int width(Font font) {
        return TextRow.widest(rows(font));
    }

    @Override
    public int height(Font font) {
        return TextRow.height(font, rows(font).size());
    }
}
