package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Things counted by name and said as words such as 3 shulker boxes and 1 ender chest. The
// names keep the order they were first counted in.
public final class Tally {

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public void add(String name) {
        counts.merge(name, 1, Integer::sum);
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

    // Every name with its count such as 3 shulker boxes 2 droppers and 1 ender chest.
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
        return count + " " + name + "s";
    }

    // What a clear button says such as Cleared 3 finds or Nothing to clear.
    public static String cleared(int count, String name) {
        return count == 0 ? "Nothing to clear" : "Cleared " + counted(count, name);
    }
}
