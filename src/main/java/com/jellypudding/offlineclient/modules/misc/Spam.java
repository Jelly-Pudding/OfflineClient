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

    private static final int SLOTS = 6;

    public enum Order {
        SEQUENCE("In order"),
        RANDOM("Random");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final NumberSetting count = new NumberSetting("Messages",
        "How many lines to rotate through.", 1, 1, SLOTS, 1);
    private final TextSetting[] lines = new TextSetting[SLOTS];
    private final EnumSetting<Order> order = new EnumSetting<>("Order",
        "How the lines are picked.", Order.SEQUENCE)
        .visibleWhen(() -> count.getInt() > 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 5, 0.1, 60, 0.1, "s").min(0.1).max(600);
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Varies the delay so it looks less robotic.", false);
    private final BoolSetting skipDuplicates = new BoolSetting("Skip repeats",
        "Never sends the same line twice in a row.", true)
        .visibleWhen(() -> count.getInt() > 1 && order.is(Order.RANDOM));

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
                .visibleWhen(() -> slot < count.getInt());
            addSettings(lines[i]);
        }
        addSettings(order, delay, randomise, skipDuplicates);
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
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
        } else {
            mc.getConnection().sendChat(text);
        }
    }

    // Null when every slot in use is blank.
    private String pick() {
        List<String> filled = new ArrayList<>(SLOTS);
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
}
