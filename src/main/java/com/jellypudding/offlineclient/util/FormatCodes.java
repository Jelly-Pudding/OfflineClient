package com.jellypudding.offlineclient.util;

import net.minecraft.ChatFormatting;

import java.util.function.ToIntFunction;

public final class FormatCodes {

    public static final char TYPED = '&';

    // Paper cuts a sign line down to this many characters before it reads it. A line is never
    // made longer than that.
    public static final int SIGN_LINE = 80;

    private FormatCodes() {
    }

    // The text as everyone reads it once the server has kept the codes. A book takes this as it
    // is because the server never looks through its pages.
    public static String coded(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(isCode(text, i) ? ChatFormatting.PREFIX_CODE : text.charAt(i));
        }
        return out.toString();
    }

    // The line to send for a sign. The server takes every code out of a sign line in one pass
    // from the left. A second section sign in front of a code and the letter once more behind it
    // leave that pass a whole code to keep. A code the line has no room for stays as typed.
    public static String forSign(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            // A code grows from two characters to four.
            if (isCode(line, i) && out.length() + line.length() - i + 2 <= SIGN_LINE) {
                char letter = line.charAt(++i);
                out.append(ChatFormatting.PREFIX_CODE).append(ChatFormatting.PREFIX_CODE).append(letter).append(letter);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // Codes take no room and none is cut in half.
    public static String fit(String line, ToIntFunction<String> width, int maxWidth) {
        StringBuilder out = new StringBuilder(line.length());
        StringBuilder letters = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            if (isCode(line, i)) {
                out.append(line, i, i + 2);
                i++;
                continue;
            }
            letters.append(line.charAt(i));
            if (width.applyAsInt(letters.toString()) > maxWidth) {
                break;
            }
            out.append(line.charAt(i));
        }
        return out.toString();
    }

    private static boolean isCode(String text, int index) {
        return text.charAt(index) == TYPED && index + 1 < text.length()
            && ChatFormatting.getByCode(text.charAt(index + 1)) != null;
    }
}
