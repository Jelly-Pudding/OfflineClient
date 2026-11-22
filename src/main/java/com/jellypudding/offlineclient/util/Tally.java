package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Things counted by name and said as words such as 3 shulker boxes and 1 ender chest. The
// names keep the order they were first counted in.
public final class Tally {

    private static final int FEW = 3;

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public void add(String name) {
        add(name, 1);
    }

    public void add(String name, int count) {
        counts.merge(name, count, Integer::sum);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public int total() {
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        return total;
    }

    public String words() {
        List<String> parts = new ArrayList<>();
        counts.forEach((name, count) -> parts.add(counted(count, name)));
        if (parts.size() < 2) {
            return String.join("", parts);
        }
        return String.join(" ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    // A number and a name such as 3 shulker boxes 2 torches or 2 chiseled bookshelves.
    public static String counted(int count, String name) {
        if (count == 1) {
            return "1 " + name;
        }
        if (name.endsWith("x") || name.endsWith("s") || name.endsWith("ch") || name.endsWith("sh")) {
            return count + " " + name + "es";
        }
        if (name.endsWith("f")) {
            return count + " " + name.substring(0, name.length() - 1) + "ves";
        }
        if (name.length() > 1 && name.endsWith("y") && "aeiou".indexOf(name.charAt(name.length() - 2)) < 0) {
            return count + " " + name.substring(0, name.length() - 1) + "ies";
        }
        return count + " " + name + "s";
    }

    // The first few names and how many more there are such as Stone and Dirt and Sand and 2 more.
    public static String firstFew(Collection<String> names) {
        List<String> shown = names.stream().limit(FEW).toList();
        String joined = String.join(" and ", shown);
        return names.size() > shown.size() ? joined + " and " + (names.size() - shown.size()) + " more" : joined;
    }

    public static String cleared(int count, String name) {
        return count == 0 ? "Nothing to clear" : "Cleared " + counted(count, name);
    }
}
