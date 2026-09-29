package com.jellypudding.offlineclient.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

// Styled text a player types in one line of chat. An ampersand and a colour or format code styles
// what follows the way chat codes do. An ampersand and a hash and six hex digits picks any colour.
// A typed backslash and n starts a new line and every line starts plain.
public final class TextMarkup {

    // A text box has no enter key of its own. A typed backslash and n breaks the line.
    public static final Pattern LINE_BREAK = Pattern.compile(Pattern.quote("\\n"));

    private static final char CODE = '&';
    private static final char HEX = '#';
    // The ampersand and the character after it.
    private static final int CODE_LENGTH = 2;
    private static final int HEX_DIGITS = 6;

    // A code read at some point in the text and how many characters it took up.
    private record Code(Style style, int length) {
    }

    private TextMarkup() {
    }

    public static List<Component> lines(String markup) {
        List<Component> lines = new ArrayList<>();
        for (String line : LINE_BREAK.split(markup, -1)) {
            lines.add(line(line));
        }
        return lines;
    }

    private static Component line(String text) {
        MutableComponent line = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            Code code = text.charAt(at) == CODE ? code(text, at, style) : null;
            if (code == null) {
                run.append(text.charAt(at));
                at++;
                continue;
            }
            addRun(line, run, style);
            style = code.style();
            at += code.length();
        }
        addRun(line, run, style);
        return line;
    }

    // Null when the ampersand at the index starts no code and stands for itself. A colour clears
    // the formats before it as in chat.
    private static Code code(String text, int at, Style current) {
        if (at + 1 >= text.length()) {
            return null;
        }
        char key = text.charAt(at + 1);
        if (key != HEX) {
            ChatFormatting format = ChatFormatting.getByCode(key);
            return format == null ? null : new Code(current.applyLegacyFormat(format), CODE_LENGTH);
        }
        int end = at + CODE_LENGTH + HEX_DIGITS;
        if (end > text.length()) {
            return null;
        }
        String digits = text.substring(at + CODE_LENGTH, end);
        if (!digits.chars().allMatch(HexFormat::isHexDigit)) {
            return null;
        }
        return new Code(Style.EMPTY.withColor(HexFormat.fromHexDigits(digits)), end - at);
    }

    private static void addRun(MutableComponent line, StringBuilder run, Style style) {
        if (!run.isEmpty()) {
            line.append(Component.literal(run.toString()).setStyle(style));
            run.setLength(0);
        }
    }
}
