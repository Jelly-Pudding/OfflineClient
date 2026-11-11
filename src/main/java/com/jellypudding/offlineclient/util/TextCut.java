package com.jellypudding.offlineclient.util;

// Text from a server made fit to keep. A character such as an emoji is two halves and a half on
// its own cannot be written to a file. A cut never parts a pair and a half that came alone is
// replaced by the mark for a character that cannot be shown.
public final class TextCut {

    private static final char UNREADABLE = '\uFFFD';
    private static final String DOTS = "...";

    private TextCut() {
    }

    // At most that many characters. A pair the cut would part is left out whole.
    public static String cut(String text, int length) {
        if (text.length() <= length) {
            return whole(text);
        }
        int end = length > 0 && Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return whole(text.substring(0, end));
    }

    // The text or else its first characters with dots after them.
    public static String quote(String text, int length) {
        return text.length() <= length ? whole(text) : cut(text, length) + DOTS;
    }

    // The text with every half that has no partner replaced. Text without one comes back as it is.
    public static String whole(String text) {
        StringBuilder mended = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            boolean pair = Character.isHighSurrogate(c) && i + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(i + 1));
            int size = pair ? 2 : 1;
            if (!pair && Character.isSurrogate(c)) {
                if (mended == null) {
                    mended = new StringBuilder(text.length()).append(text, 0, i);
                }
                mended.append(UNREADABLE);
            } else if (mended != null) {
                mended.append(text, i, i + size);
            }
            i += size;
        }
        return mended == null ? text : mended.toString();
    }
}
