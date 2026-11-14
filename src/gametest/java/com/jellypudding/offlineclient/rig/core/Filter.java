package com.jellypudding.offlineclient.rig.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Picks the cases of a run by name. The words are split by commas. A case runs when its full
// name holds one of the words and none of the words that start with a minus sign.
public final class Filter {

    private static final String SEPARATOR = ",";
    private static final String NOT = "-";

    private final List<String> wanted = new ArrayList<>();
    private final List<String> unwanted = new ArrayList<>();

    public Filter(String words) {
        for (String word : words.split(SEPARATOR)) {
            String clean = word.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith(NOT) && clean.length() > NOT.length()) {
                unwanted.add(clean.substring(NOT.length()));
            } else if (!clean.isEmpty()) {
                wanted.add(clean);
            }
        }
    }

    public boolean wants(String suite, String name) {
        String id = (suite + "/" + name).toLowerCase(Locale.ROOT);
        for (String word : unwanted) {
            if (id.contains(word)) {
                return false;
            }
        }
        if (wanted.isEmpty()) {
            return true;
        }
        for (String word : wanted) {
            if (id.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
