package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

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

    private final NumberSetting count;
    private final TextSetting[] lines = new TextSetting[SLOTS];
    private final EnumSetting<Order> order;
    private final BoolSetting skipRepeats;

    // A switch the whole list sits beneath. Always open until under is called.
    private Supplier<Boolean> gate = () -> true;

    // False for a list read whole rather than handed out one line at a time.
    private boolean rotates = true;

    private int next;
    private String last = "";

    // The defaults fill the first rows and set how many rows start open.
    public TextLines(String countName, String countDescription, String... defaults) {
        count = new NumberSetting(countName, countDescription,
            Math.max(1, defaults.length), 1, 30, 1).max(SLOTS)
            .visibleWhen(() -> gate.get());
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            lines[i] = new TextSetting("Line " + (i + 1), "The text to send. Click to type it.",
                i < defaults.length ? defaults[i] : "")
                .under(count, () -> gate.get() && slot < count.getInt());
        }
        order = new EnumSetting<>("Order", "How the lines are picked.", Order.SEQUENCE)
            .describe(Order.SEQUENCE, "Sends the lines top to bottom and starts over.")
            .describe(Order.RANDOM, "Picks a random line each time.")
            .under(count, () -> rotates && gate.get() && count.getInt() > 1);
        skipRepeats = new BoolSetting("Skip repeats", "Never sends the same line twice in a row.", true)
            .under(order, () -> rotates && gate.get() && count.getInt() > 1 && order.is(Order.RANDOM));
    }

    // Every row is used at once. The pick order rows are pointless.
    public TextLines plain() {
        rotates = false;
        return this;
    }

    // Makes every row of the list a sub option of a switch.
    public TextLines under(BoolSetting parent) {
        count.under(parent);
        gate = parent::isOn;
        return this;
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>(SLOTS + 3);
        all.add(count);
        for (int i = 0; i < SLOTS; i++) {
            all.add(lines[i]);
        }
        all.add(order);
        all.add(skipRepeats);
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
