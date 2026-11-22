package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.ShowRule;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.TextMarkup;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.ARGB;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TextElement extends RowsElement {

    public static final int USUAL = 3;

    private static final double START_X = 50;
    private static final double START_Y = 4;
    private static final double START_STEP = 6;
    private static final double LOWEST_START = 100;

    private static final String GREETING = "Hello {username}";

    private final TextSetting text;
    private final ColorSetting color;
    private final ShowRule rule;
    private final RowCache<Component> lines = new RowCache<>(this::build);

    public TextElement(int number) {
        super("Custom text " + number, "Any text you type. Words in braces such as {fps} turn into live values.",
            false, START_X, Math.min(START_Y + (number - 1) * START_STEP, LOWEST_START));
        String name = getName();
        text = new TextSetting(name + " text", "What it says. Type \\n to start a new line and & with a colour code"
            + " such as &6 or &#ff8800 to colour what follows. You can use " + LiveText.WORDS + ".",
            number == 1 ? GREETING : "");
        color = new ColorSetting(name + " colour", "Colour of the words that no colour code changes.", 190, false);
        rule = new ShowRule(name);
        add(text, color);
        add(rule.settings().toArray(new Setting<?>[0]));
    }

    @Override
    public boolean visible() {
        return isActive() && lines.get().stream().anyMatch(line -> !line.getString().isBlank());
    }

    // The live words are filled in after the codes are read. NameProtect then hides names in them
    // as it does in chat. In the editor the words show whatever the rule says or there would be
    // nothing to place.
    private List<Component> build() {
        ShowRule.Verdict verdict = rule.judge();
        if (verdict.problem() != null) {
            return List.of(Component.literal(verdict.problem()).withStyle(ChatFormatting.RED));
        }
        if (!editing() && !verdict.shows()) {
            return List.of();
        }
        List<Component> built = new ArrayList<>();
        for (Component line : TextMarkup.lines(text.getValue(), LiveText::fill)) {
            built.add(EntityUtil.displayed(line));
        }
        return built;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        List<Component> filled = lines.get();
        List<TextRow> rows = new ArrayList<>(filled.size());
        for (int i = 0; i < filled.size(); i++) {
            rows.add(rowOf(font, filled.get(i), color.rowColor(i)));
        }
        return rows;
    }

    // A run without a colour of its own takes the given one.
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
}
