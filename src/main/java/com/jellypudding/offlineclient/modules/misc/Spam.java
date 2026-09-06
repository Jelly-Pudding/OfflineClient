package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.client.gui.screens.DisconnectedScreen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// A message starting with a slash is sent as a command.
public final class Spam extends Module {

    // Servers cut a chat line off here.
    private static final int MAX_LENGTH = 256;

    // Tacked onto the end of a message when the variation calls for it.
    private static final String TAILS = "!.~";

    private final TextLines lines = new TextLines("Messages",
        "How many lines to rotate through.", "minecraftoffline.net is OK I guess");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 5, 0.1, 60, 0.1, "s").min(0.1).max(600);
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Shifts each delay by a random amount to look less robotic.", false);
    private final NumberSetting spread = new NumberSetting("Spread",
        "The most a delay is shifted either way. A delay of 5s with a spread of 2s waits anywhere from 3s to 7s.",
        1, 0.1, 10, 0.1, "s").min(0.1).max(300)
        .under(randomise);
    private final BoolSetting vary = new BoolSetting("Vary text",
        "Makes small random changes to each message to get repeats past a spam filter.", false);
    private final NumberSetting variation = new NumberSetting("Variation",
        "How many changes each message gets. A letter changes case or doubles or the line gains a tail.",
        1, 1, 10, 1).max(100)
        .under(vary);
    private final BoolSetting split = new BoolSetting("Split long lines",
        "Sends a long message in pieces instead of cutting it short.", false);
    private final NumberSetting splitLength = new NumberSetting("Split length",
        "How many characters each piece holds.", MAX_LENGTH, 1, MAX_LENGTH, 1)
        .min(1).max(MAX_LENGTH)
        .under(split);
    private final NumberSetting splitDelay = new NumberSetting("Split delay",
        "Ticks between one piece and the next.", 20, 0, 200, 1, " ticks").min(0).max(1000)
        .under(split);
    private final BoolSetting stopOnLeave = new BoolSetting("Stop on leave",
        "Turns itself off when you leave a world.", true);
    private final BoolSetting stopOnDisconnect = new BoolSetting("Stop on disconnect",
        "Turns itself off when the disconnected screen opens.", true);

    // Pieces of a split message still waiting to go out.
    private final Deque<String> pieces = new ArrayDeque<>();
    private int timer;
    private boolean wasInGame;

    public Spam() {
        super("Spam", "Sends chat messages on a timer.", Category.MISC);
        addSettings(lines.settings());
        addSettings(delay, randomise, spread, vary, variation, split, splitLength, splitDelay,
            stopOnLeave, stopOnDisconnect);
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
        pieces.clear();
        wasInGame = inGame();
        lines.restart();
    }

    // Leaving a world or landing on the disconnected screen can switch it off.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (stopOnDisconnect.isOn() && mc.gui.screen() instanceof DisconnectedScreen) {
            setEnabled(false);
            return;
        }
        if (inGame()) {
            wasInGame = true;
            return;
        }
        if (wasInGame && stopOnLeave.isOn()) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.getConnection() == null) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        // The rest of a split message goes out before the next line starts.
        if (!pieces.isEmpty()) {
            timer = Math.max(1, splitDelay.getInt());
            mc.getConnection().sendChat(pieces.removeFirst());
            return;
        }
        timer = nextDelay();

        String text = lines.pick();
        if (text == null) {
            return;
        }
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
            return;
        }
        if (vary.isOn()) {
            for (int i = 0; i < variation.getInt(); i++) {
                text = vary(text);
            }
        }
        if (split.isOn() && text.length() > splitLength.getInt()) {
            cutInto(text);
            timer = Math.max(1, splitDelay.getInt());
            mc.getConnection().sendChat(pieces.removeFirst());
            return;
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        mc.getConnection().sendChat(text);
    }

    private void cutInto(String text) {
        int size = splitLength.getInt();
        for (int start = 0; start < text.length(); start += size) {
            pieces.addLast(text.substring(start, Math.min(text.length(), start + size)));
        }
    }

    // The shift never takes the delay below the slider floor.
    private int nextDelay() {
        double seconds = delay.getValue();
        if (randomise.isOn()) {
            double reach = spread.getValue();
            seconds += ThreadLocalRandom.current().nextDouble(-reach, reach);
            seconds = Math.max(delay.getHardMin(), seconds);
        }
        return Math.max(1, (int) Math.round(seconds * 20));
    }

    // One small change to keep the message readable. A letter changes case or doubles up
    // or the message gains a tail. With no letters only a number or tail is added.
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
