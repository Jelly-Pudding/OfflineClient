package com.jellypudding.offlineclient.util;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

// A regular expression the player typed for text somebody else wrote. The text is cut to a set
// length and a search gives up once it has read a set number of letters. A pattern that needs
// more than that is switched off until its row changes. No line can make the client hang.
public final class BoundedRegex {

    // The longest text a pattern is run over. A chat line holds half of this at most.
    public static final int LONGEST_TEXT = 512;

    // How many letters one search may read. A pattern open at both ends such as .*word.* reads
    // the longest text about a quarter of this often before it finds nothing.
    private static final int STEPS = 1_000_000;

    // Thrown out of a search that has read too much. Nothing ever prints it.
    private static final class RanAway extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        RanAway() {
            super(null, null, false, false);
        }
    }

    // The text as a search reads it. Every letter read comes off what the search may still read.
    private static final class Counted implements CharSequence {

        private final String text;
        private int left = STEPS;

        Counted(String text) {
            this.text = text;
        }

        @Override
        public char charAt(int index) {
            if (--left < 0) {
                throw new RanAway();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final Pattern pattern;
    private final Consumer<String> problems;
    private boolean off;

    private BoundedRegex(Pattern pattern, Consumer<String> problems) {
        this.pattern = pattern;
        this.problems = problems;
    }

    // Null after saying why for text that is no regular expression. A pattern that later runs
    // away says that the same way.
    public static BoundedRegex compile(String typed, Consumer<String> problems) {
        try {
            return new BoundedRegex(Pattern.compile(typed), problems);
        } catch (PatternSyntaxException error) {
            problems.accept("§f" + typed + "§c is not a valid pattern.");
            return null;
        }
    }

    // The text a search sees. Anything past the longest length is never read.
    public static String cut(String text) {
        return text.length() > LONGEST_TEXT ? text.substring(0, LONGEST_TEXT) : text;
    }

    // How many groups in brackets the pattern has.
    public int groups() {
        return pattern.matcher("").groupCount();
    }

    // The first match in the text that takes in at least one letter. Null when there is none.
    public MatchResult find(String text) {
        List<MatchResult> found = search(text, false, true);
        return found.isEmpty() ? null : found.getFirst();
    }

    // The match that begins at the first letter of the text. Null when there is none.
    public MatchResult atStart(String text) {
        List<MatchResult> found = search(text, true, true);
        return found.isEmpty() ? null : found.getFirst();
    }

    // Every match in the text from the first to the last. A match of no letters is left out.
    public List<MatchResult> all(String text) {
        return search(text, false, false);
    }

    // One count of letters read covers the whole search however many matches it finds. A
    // pattern nested deep enough to overflow the stack is given up the same way.
    private List<MatchResult> search(String text, boolean atStart, boolean firstOnly) {
        List<MatchResult> found = new ArrayList<>();
        if (off) {
            return found;
        }
        Matcher matcher = pattern.matcher(new Counted(cut(text)));
        try {
            if (atStart) {
                if (matcher.lookingAt()) {
                    found.add(matcher.toMatchResult());
                }
                return found;
            }
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) {
                    found.add(matcher.toMatchResult());
                    if (firstOnly) {
                        break;
                    }
                }
            }
        } catch (RanAway | StackOverflowError error) {
            off = true;
            found.clear();
            problems.accept("§f" + pattern.pattern() + "§c took too long to search a line and is off until you"
                + " change it.");
        }
        return found;
    }
}
