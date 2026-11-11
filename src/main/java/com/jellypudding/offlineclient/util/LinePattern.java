package com.jellypudding.offlineclient.util;

import java.util.function.Consumer;
import java.util.regex.MatchResult;

// What a rule looks for in a line of chat. A line a player wrote is searched for words or
// for a regular expression anywhere in it. A line the server wrote is read from its first
// letter. Anything a player can slip into a line of the server such as a whisper or a
// broadcast they paid for stands behind their name or a tag and never at the start.
public final class LinePattern {

    // Where the name of a player stands in a wording.
    public static final String PLAYER = "{player}";

    // The longest name a server lets a player join with.
    private static final int LONGEST_NAME = 16;

    // What a line gave a pattern. The name is null for a pattern that names nobody.
    public record Hit(String name) {

        private static final Hit NAMELESS = new Hit(null);
    }

    private final boolean fromStart;
    // Null for a row of plain words.
    private final BoundedRegex regex;
    private final boolean isRegex;
    // The words of a plain row. A wording is cut in two where the name stands.
    private final String before;
    private final String after;
    private final boolean named;

    private LinePattern(String typed, boolean isRegex, boolean fromStart, Consumer<String> problems) {
        this.fromStart = fromStart;
        this.isRegex = isRegex;
        regex = isRegex ? BoundedRegex.compile(typed, problems) : null;
        int nameAt = isRegex || !fromStart ? -1 : typed.indexOf(PLAYER);
        before = nameAt == -1 ? typed : typed.substring(0, nameAt);
        after = nameAt == -1 ? "" : typed.substring(nameAt + PLAYER.length());
        named = isRegex ? fromStart && regex != null && regex.groups() > 0 : nameAt != -1;
    }

    // For what players write. Plain words stand whole anywhere in the line whatever their case.
    public static LinePattern forPlayers(String typed, boolean regex, Consumer<String> problems) {
        return new LinePattern(typed.trim(), regex, false, problems);
    }

    // For what the server writes. A plain row is the line as the server starts it whatever its
    // case with {player} where a name stands. The first group in brackets of a regular
    // expression is the name.
    public static LinePattern forServer(String typed, boolean regex, Consumer<String> problems) {
        return new LinePattern(typed.trim(), regex, true, problems);
    }

    // True for a pattern that takes the name of a player out of the line.
    public boolean namesPlayer() {
        return named;
    }

    // Null when the line does not match. Only the start of a very long line is read.
    public Hit match(String line) {
        String text = BoundedRegex.cut(line);
        if (!fromStart) {
            boolean found = isRegex ? regex != null && regex.find(text) != null : ChatSender.holds(text, before);
            return found ? Hit.NAMELESS : null;
        }
        String trimmed = text.stripLeading();
        return isRegex ? matchRegex(trimmed) : matchWording(trimmed);
    }

    private Hit matchRegex(String text) {
        MatchResult result = regex == null ? null : regex.atStart(text);
        if (result == null) {
            return null;
        }
        if (!named) {
            return Hit.NAMELESS;
        }
        String name = result.group(1);
        return name != null && SentLine.oneWord(name) ? new Hit(name) : null;
    }

    // The longest word that can be a name is tried first and cut back until the rest of the
    // wording follows it. A name goes into a command and must be one word of it.
    private Hit matchWording(String text) {
        if (!text.regionMatches(true, 0, before, 0, before.length())) {
            return null;
        }
        if (!named) {
            return Hit.NAMELESS;
        }
        int from = before.length();
        int longest = 0;
        while (from + longest < text.length() && longest < LONGEST_NAME
            && !Character.isWhitespace(text.charAt(from + longest))) {
            longest++;
        }
        for (int length = longest; length > 0; length--) {
            String name = text.substring(from, from + length);
            if (SentLine.oneWord(name) && text.regionMatches(true, from + length, after, 0, after.length())) {
                return new Hit(name);
            }
        }
        return null;
    }
}
