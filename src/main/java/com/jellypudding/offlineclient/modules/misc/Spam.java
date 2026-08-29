package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// A message starting with a slash is sent as a command.
public final class Spam extends Module {

    // Servers cut a chat line off here.
    private static final int MAX_LENGTH = 256;

    private static final int SLOTS = 100;

    // Tacked onto the end of a message when the variation calls for it.
    private static final String TAILS = "!.~";

    public enum Order {
        SEQUENCE, RANDOM;

        @Override
        public String toString() {
            return this == SEQUENCE ? "In order" : name();
        }
    }

    private final NumberSetting count = new NumberSetting("Messages",
        "How many lines to rotate through.", 1, 1, 30, 1).max(SLOTS);
    private final TextSetting[] lines = new TextSetting[SLOTS];
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "How the lines are picked.", Order.SEQUENCE)
        .describe(Order.SEQUENCE, "Sends the lines top to bottom and starts over.")
        .describe(Order.RANDOM, "Picks a random line each time.")
        .under(count, () -> count.getInt() > 1);
    private final BoolSetting skipDuplicates = new BoolSetting("Skip repeats",
        "Never sends the same line twice in a row.", true)
        .under(order, () -> count.getInt() > 1 && order.is(Order.RANDOM));
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 5, 0.1, 60, 0.1, "s").min(0.1).max(600);
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Varies the delay so it looks less robotic.", false);
    private final BoolSetting vary = new BoolSetting("Vary text",
        "Makes small random changes to each message so repeats get past a spam filter.", false);
    private final NumberSetting variation = new NumberSetting("Variation",
        "How many changes each message gets. A letter changes case or doubles or the line gains a tail.",
        1, 1, 5, 1).max(20)
        .under(vary);

    private int timer;
    private int next;
    private String lastSent = "";

    public Spam() {
        super("Spam", "Sends chat messages on a timer.", Category.MISC);
        addSettings(count);
        for (int i = 0; i < SLOTS; i++) {
            int slot = i;
            lines[i] = new TextSetting("Line " + (i + 1),
                "The text to send. Click to type it.",
                i == 0 ? "minecraftoffline.net is OK I guess" : "")
                .under(count, () -> slot < count.getInt());
            addSettings(lines[i]);
        }
        addSettings(order, skipDuplicates, delay, randomise, vary, variation);
        searchTags("auto message", "advert", "chat spam");
    }

    @Override
    public String getSuffix() {
        return delay.getValueString();
    }

    @Override
    protected void onEnable() {
        // Enabling never fires instantly.
        timer = 20;
        next = 0;
        lastSent = "";
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.getConnection() == null) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        timer = nextDelay();

        String text = pick();
        if (text == null) {
            return;
        }
        lastSent = text;
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
            return;
        }
        if (vary.isOn()) {
            for (int i = 0; i < variation.getInt(); i++) {
                text = vary(text);
            }
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        mc.getConnection().sendChat(text);
    }

    // Null when every slot in use is blank.
    private String pick() {
        List<String> filled = new ArrayList<>(count.getInt());
        for (int i = 0; i < count.getInt(); i++) {
            String value = lines[i].getValue().trim();
            if (!value.isEmpty()) {
                filled.add(value);
            }
        }
        if (filled.isEmpty()) {
            return null;
        }
        if (filled.size() == 1) {
            return filled.getFirst();
        }
        if (order.is(Order.RANDOM)) {
            String choice = filled.get(ThreadLocalRandom.current().nextInt(filled.size()));
            if (skipDuplicates.isOn() && choice.equals(lastSent)) {
                choice = filled.get((filled.indexOf(choice) + 1) % filled.size());
            }
            return choice;
        }
        next %= filled.size();
        return filled.get(next++);
    }

    private int nextDelay() {
        double seconds = delay.getValue();
        if (randomise.isOn()) {
            seconds *= ThreadLocalRandom.current().nextDouble(0.5, 1.5);
        }
        return Math.max(1, (int) Math.round(seconds * 20));
    }

    /**
     * One small change that keeps the message readable. A letter changes
     * case or doubles up or the line gains a tail. Text with no letters can
     * only gain a tail.
     */
    private static String vary(String text) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Integer> letters = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                letters.add(i);
            }
        }
        int kind = letters.isEmpty() ? random.nextInt(2) : random.nextInt(4);
        switch (kind) {
            case 0 -> {
                return text + " " + random.nextInt(10, 100);
            }
            case 1 -> {
                return text + TAILS.charAt(random.nextInt(TAILS.length()));
            }
            default -> {
                int at = letters.get(random.nextInt(letters.size()));
                char letter = text.charAt(at);
                String replacement = kind == 2
                    ? String.valueOf(Character.isUpperCase(letter)
                        ? Character.toLowerCase(letter) : Character.toUpperCase(letter))
                    : String.valueOf(letter) + letter;
                return text.substring(0, at) + replacement + text.substring(at + 1);
            }
        }
    }
}
