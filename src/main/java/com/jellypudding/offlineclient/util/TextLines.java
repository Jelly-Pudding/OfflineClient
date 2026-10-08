package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// A numbered list of chat lines. A count slider decides how many line rows
// show and pick hands them out in order or at random.
public final class TextLines {

    private static final int SLOTS = 100;

    public enum Order {
        SEQUENCE, RANDOM;

        @Override
        public String toString() {
            return this == SEQUENCE ? "In order" : "Random";
        }
    }

    // Every other row hangs beneath the count and shows only whilst it does.
    private final NumberSetting count;
    private final TextSetting[] lines = new TextSetting[SLOTS];
    private final EnumSetting<Order> order;
    private final BoolSetting skipRepeats;

    // False for a list read whole rather than handed out one line at a time.
    private boolean rotates = true;

    private int next;
    private String last = "";

    // The defaults fill the first rows and set how many rows start open.
    public TextLines(String countName, String countDescription, String lineDescription, String... defaults) {
        this(Order.SEQUENCE, countName, countDescription, lineDescription, defaults);
    }

    public TextLines(Order startOrder, String countName, String countDescription, String lineDescription,
                     String... defaults) {
        this(startOrder, countName, "Line", countDescription, lineDescription, defaults);
    }

    private TextLines(Order startOrder, String countName, String rowName, String countDescription,
                      String lineDescription, String[] defaults) {
        count = new NumberSetting(countName, countDescription,
            Math.max(1, defaults.length), 1, 30, 1).max(SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            lines[i] = new TextSetting(rowName + " " + (i + 1), lineDescription,
                i < defaults.length ? defaults[i] : "")
                .under(count, () -> slot < count.getInt());
        }
        order = new EnumSetting<>("Order", "How the lines are picked.", startOrder)
            .describe(Order.SEQUENCE, "Sends the lines top to bottom and starts over.")
            .describe(Order.RANDOM, "Picks a random line each time.")
            .under(count, () -> rotates && count.getInt() > 1);
        skipRepeats = new BoolSetting("Skip repeats", "Never sends the same line twice in a row.", true)
            .under(order, () -> rotates && count.getInt() > 1 && order.is(Order.RANDOM));
    }

    // A plain list whose rows carry its own name such as Join name 1. Two of them can
    // then sit in one module.
    public static TextLines named(String countName, String rowName, String countDescription,
                                  String lineDescription) {
        return new TextLines(Order.SEQUENCE, countName, rowName, countDescription, lineDescription,
            new String[0]).plain();
    }

    // Every row is used at once. The pick order rows are pointless.
    public TextLines plain() {
        rotates = false;
        return this;
    }

    // Makes every row of the list a sub option of a switch.
    public TextLines under(BoolSetting parent) {
        count.under(parent);
        return this;
    }

    // Makes every row of the list a sub option of a choice. The rows show whilst it holds one of the values.
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final <E extends Enum<E>> TextLines under(EnumSetting<E> parent, E... values) {
        count.under(parent, values);
        return this;
    }

    // A plain list leaves out the pick order rows. They would never show.
    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(SLOTS + 3);
        all.add(count);
        for (int i = 0; i < SLOTS; i++) {
            all.add(lines[i]);
        }
        if (rotates) {
            all.add(order);
            all.add(skipRepeats);
        }
        return all.toArray(new Setting<?>[0]);
    }

    // Starts again from the top and forgets the last line handed out.
    public void restart() {
        next = 0;
        last = "";
    }

    // True when every row in use is blank.
    public boolean isEmpty() {
        return filled().isEmpty();
    }

    // The next line to send. Null when every row in use is blank.
    public String pick() {
        List<String> filled = filled();
        if (filled.isEmpty()) {
            return null;
        }
        String choice;
        if (filled.size() == 1) {
            choice = filled.getFirst();
        } else if (order.is(Order.RANDOM)) {
            choice = filled.get(ThreadLocalRandom.current().nextInt(filled.size()));
            if (skipRepeats.isOn() && choice.equals(last)) {
                choice = filled.get((filled.indexOf(choice) + 1) % filled.size());
            }
        } else {
            next %= filled.size();
            choice = filled.get(next++);
        }
        last = choice;
        return choice;
    }

    // Every row in use that has text in it.
    public List<String> all() {
        return filled();
    }

    // True when a row in use holds the text whatever its case.
    public boolean holds(String text) {
        String wanted = text.trim();
        return filled().stream().anyMatch(row -> row.equalsIgnoreCase(wanted));
    }

    // Writes the text into the first blank row in use or opens one more row for it. False when
    // a row holds it already or every row is taken.
    public boolean add(String text) {
        String wanted = text.trim();
        if (wanted.isEmpty() || holds(wanted)) {
            return false;
        }
        int open = count.getInt();
        for (int i = 0; i < open; i++) {
            if (lines[i].getValue().isBlank()) {
                lines[i].setValue(wanted);
                return true;
            }
        }
        if (open >= SLOTS) {
            return false;
        }
        lines[open].setValue(wanted);
        count.setValue((double) (open + 1));
        return true;
    }

    // Takes every row holding the text out whatever its case. The rows below move up and the
    // count shrinks to match. False when no row held it.
    public boolean remove(String text) {
        String wanted = text.trim();
        int open = count.getInt();
        int kept = 0;
        for (int i = 0; i < open; i++) {
            String value = lines[i].getValue();
            if (!value.trim().equalsIgnoreCase(wanted)) {
                lines[kept++].setValue(value);
            }
        }
        if (kept == open) {
            return false;
        }
        for (int i = kept; i < open; i++) {
            lines[i].setValue("");
        }
        count.setValue((double) Math.max(1, kept));
        return true;
    }

    private List<String> filled() {
        List<String> filled = new ArrayList<>(count.getInt());
        for (int i = 0; i < count.getInt(); i++) {
            String value = lines[i].getValue().trim();
            if (!value.isEmpty()) {
                filled.add(value);
            }
        }
        return filled;
    }
}
