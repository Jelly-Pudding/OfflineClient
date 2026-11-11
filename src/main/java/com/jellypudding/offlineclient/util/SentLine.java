package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.util.StringUtil;

// A row the player typed made into the line a module sends for them. Each word in braces is
// filled in one pass over the row with the value given for it or its live value. Nothing
// somebody else wrote is ever read as a word in braces and only the row itself can make the
// line a command. The line goes out through ChatPace.
public final class SentLine {

    private SentLine() {
    }

    // A command as the player typed it into a row. Without its slash it would go out as a chat line.
    public static String asCommand(String typed) {
        String command = typed.trim();
        return command.startsWith("/") ? command : "/" + command;
    }

    // The words of a line the player typed for the server with each live word filled in once.
    // Whether the line is chat or a command was read off it before. A value read from the server
    // may hold a colour mark or run past the length of a chat line and both are taken out after.
    public static String live(String words) {
        return StringUtil.trimChatMessage(StringUtil.filterText(LiveText.fill(words)));
    }

    // The command typed into a row that acts on one player such as /ignore. The name goes on the
    // end or where the row holds {player}. A command that left the name out would act on
    // whoever the server picks. Empty for a blank row.
    public static String naming(String typed) {
        if (typed.isBlank()) {
            return "";
        }
        String command = asCommand(typed);
        return command.contains(LinePattern.PLAYER) ? command : command + " " + LinePattern.PLAYER;
    }

    // The row a module that whispers keeps the command for it in.
    public static TextSetting whisperWith() {
        return new TextSetting("Whisper with",
            "The command that whispers to a player on this server such as /msg or /w or /tell.", "/msg");
    }

    // The row with its live words filled in at whatever length that comes to.
    public static String filled(String row) {
        return build(row);
    }

    // The row cut to the length of a chat line with each word in braces swapped for the
    // value that follows it in the pairs. Empty when nothing is left to send and for a command
    // that would carry a value that is not one plain word.
    public static String of(String row, String... pairs) {
        return StringUtil.trimChatMessage(build(row, pairs));
    }

    // Words somebody else chose as a line of chat. A slash in front would run them as a command.
    public static String chat(String words) {
        return StringUtil.trimChatMessage(unslashed(StringUtil.filterText(words).strip()));
    }

    // The text as a whisper to a player through the command typed for it such as /msg. The text
    // is taken as it is and never searched for words in braces. Empty when the text is and for
    // a name that cannot stand as one word of a command.
    public static String whisper(String command, String to, String text) {
        String words = StringUtil.filterText(text).strip();
        return words.isEmpty() || !oneWord(to) ? ""
            : StringUtil.trimChatMessage(StringUtil.filterText(asCommand(command)) + " " + to + " " + words);
    }

    // True for a name that can stand as one word of a command. An offline server lets players
    // in with a space in the name or with a sign the server kicks you for sending. Such a name
    // in a command would run it on somebody else or get you kicked.
    public static boolean oneWord(String value) {
        return !value.isEmpty() && StringUtil.isValidPlayerName(value);
    }

    // A row that does not start with a slash never comes out as a command whatever a value holds.
    private static String build(String row, String... pairs) {
        String typed = row.strip();
        boolean command = typed.startsWith("/");
        if (command && !valuesFit(pairs)) {
            return "";
        }
        String line = StringUtil.filterText(fill(typed, pairs)).strip();
        return command ? line : unslashed(line);
    }

    private static boolean valuesFit(String[] pairs) {
        for (int i = 1; i < pairs.length; i += 2) {
            if (!oneWord(pairs[i])) {
                return false;
            }
        }
        return true;
    }

    private static String unslashed(String line) {
        String rest = line;
        while (rest.startsWith("/")) {
            rest = rest.substring(1).strip();
        }
        return rest;
    }

    // One pass over the row. The nearest brace before a closing one opens the word between
    // them. A value goes in as it is and is never read again.
    private static String fill(String row, String[] pairs) {
        if (row.indexOf('{') < 0) {
            return row;
        }
        StringBuilder out = new StringBuilder(row.length());
        int at = 0;
        int open = -1;
        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '{') {
                open = i;
            } else if (c == '}' && open >= 0) {
                out.append(row, at, open).append(valueOf(row.substring(open, i + 1), pairs));
                at = i + 1;
                open = -1;
            }
        }
        return out.append(row, at, row.length()).toString();
    }

    // The value given for a word in braces or else its live value. A word nothing knows stays
    // as it was typed.
    private static String valueOf(String word, String[] pairs) {
        String key = word.substring(1, word.length() - 1);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].equals(key)) {
                return pairs[i + 1];
            }
        }
        return LiveText.fill(word);
    }
}
