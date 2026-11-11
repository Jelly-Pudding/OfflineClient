package com.jellypudding.offlineclient.config;

import net.minecraft.SharedConstants;

import java.util.Locale;
import java.util.OptionalInt;

// One line of a macro read for what it asks. A line whose first word is wait or repeat is an
// instruction and never goes to the server. Anything else is sent or run as it stands.
public sealed interface MacroLine {

    String WAIT = "wait";
    String REPEAT = "repeat";

    // A line for the server or a command of the client's own.
    record Send(String text) implements MacroLine {
    }

    // Nothing runs for that many ticks.
    record Wait(int ticks) implements MacroLine {
    }

    // The same line that many times with a gap in ticks between one and the next.
    record Repeat(int times, int gap, String text) implements MacroLine {
    }

    // An instruction with something missing or wrong. The problem says what it should look like.
    record Unreadable(String problem) implements MacroLine {
    }

    static MacroLine read(String line) {
        String[] words = line.trim().split("\\s+", 4);
        if (words[0].equalsIgnoreCase(WAIT)) {
            OptionalInt ticks = words.length == 2 ? ticks(words[1]) : OptionalInt.empty();
            return ticks.isPresent() ? new Wait(ticks.getAsInt())
                : new Unreadable("A wait takes ticks or seconds such as wait 20 or wait 2s.");
        }
        if (!words[0].equalsIgnoreCase(REPEAT)) {
            return new Send(line.trim());
        }
        OptionalInt times = words.length == 4 ? count(words[1]) : OptionalInt.empty();
        OptionalInt gap = times.isPresent() ? ticks(words[2]) : OptionalInt.empty();
        if (gap.isEmpty()) {
            return new Unreadable("A repeat takes a count and a gap and a line such as repeat 5 2s hello.");
        }
        if (!(read(words[3]) instanceof Send)) {
            return new Unreadable("A repeat runs a plain line and never a wait or another repeat.");
        }
        return new Repeat(times.getAsInt(), gap.getAsInt(), words[3].trim());
    }

    // A whole number of ticks or seconds marked with an s such as 20 or 2s or 0.5s.
    static OptionalInt ticks(String text) {
        String typed = text.toLowerCase(Locale.ROOT);
        boolean seconds = typed.endsWith("s");
        if (seconds || typed.endsWith("t")) {
            typed = typed.substring(0, typed.length() - 1);
        }
        double amount;
        try {
            amount = seconds ? Double.parseDouble(typed) * SharedConstants.TICKS_PER_SECOND : Long.parseLong(typed);
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
        if (Double.isNaN(amount) || amount < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.min(Math.round(amount), Integer.MAX_VALUE));
    }

    private static OptionalInt count(String text) {
        try {
            int count = Integer.parseInt(text);
            return count > 0 ? OptionalInt.of(count) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
