package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

// Scores how well a candidate answers a search. Every list the client filters
// uses this to order results with the closest match first.
public final class SearchRank {

    // Nothing about the candidate answers the query.
    public static final int NO_MATCH = 0;

    private static final int EXACT = 10_000;
    private static final int WHOLE_WORD = 8_000;
    private static final int PREFIX = 6_000;
    private static final int WORD_PREFIX = 4_000;
    private static final int CONTAINS = 2_000;

    private SearchRank() {
    }

    // Higher is a better answer and NO_MATCH means no answer at all. Shorter candidates
    // win ties because a query is usually most of what the searcher wanted to type.
    public static int score(String candidate, String query) {
        if (candidate == null || candidate.isEmpty() || query == null || query.isEmpty()) {
            return NO_MATCH;
        }
        String text = candidate.toLowerCase(Locale.ROOT);
        String wanted = query.toLowerCase(Locale.ROOT);

        if (text.equals(wanted)) {
            return EXACT;
        }
        int at = text.indexOf(wanted);
        if (at == -1) {
            return NO_MATCH;
        }
        int shortness = Math.max(0, 200 - text.length());
        if (isWholeWord(candidate, text, at, wanted.length())) {
            return WHOLE_WORD + shortness;
        }
        if (at == 0) {
            return PREFIX + shortness;
        }
        if (startsWord(candidate, at)) {
            return WORD_PREFIX + shortness;
        }
        // A later match is a weaker one.
        return CONTAINS + shortness - Math.min(at, 100);
    }

    // The best score across several fields. Every field after the name drops
    // one band. A strong tag match can still outrank a weak name match.
    public static int best(String query, String name, String... weaker) {
        int best = score(name, query);
        int penalty = 0;
        for (String field : weaker) {
            penalty += CONTAINS;
            int found = score(field, query);
            if (found != NO_MATCH) {
                best = Math.max(best, Math.max(1, found - penalty));
            }
        }
        return best;
    }

    private record Scored<T>(T candidate, int score) {
    }

    // The candidates that answer the query at all with the best first. Equal
    // scores keep the order they were given in.
    public static <T> List<T> rank(List<T> candidates, ToIntFunction<T> scorer) {
        List<Scored<T>> scored = new ArrayList<>();
        for (T candidate : candidates) {
            int score = scorer.applyAsInt(candidate);
            if (score > NO_MATCH) {
                scored.add(new Scored<>(candidate, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored<T>::score).reversed());
        List<T> result = new ArrayList<>(scored.size());
        for (Scored<T> entry : scored) {
            result.add(entry.candidate());
        }
        return result;
    }

    private static boolean isWholeWord(String original, String lower, int at, int length) {
        int end = at + length;
        boolean startClean = at == 0 || startsWord(original, at);
        boolean endClean = end == lower.length() || endsWord(original, end);
        return startClean && endClean;
    }

    // A word starts after a separator or at a capital inside a run of letters.
    private static boolean startsWord(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return index == 0;
        }
        char before = text.charAt(index - 1);
        if (!Character.isLetterOrDigit(before)) {
            return true;
        }
        char here = text.charAt(index);
        return Character.isUpperCase(here) && !Character.isUpperCase(before);
    }

    private static boolean endsWord(String text, int index) {
        if (index >= text.length()) {
            return true;
        }
        char here = text.charAt(index);
        if (!Character.isLetterOrDigit(here)) {
            return true;
        }
        char before = text.charAt(index - 1);
        return Character.isUpperCase(here) && !Character.isUpperCase(before);
    }
}
