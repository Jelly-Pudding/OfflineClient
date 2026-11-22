package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ListFile;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.DisconnectedScreen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Spam extends Module {

    public enum Source { LIST, FILE }

    private static final String TAILS = "!.~";

    private final EnumSetting<Source> source = new EnumSetting<>("Source",
        "Where the lines come from.", Source.LIST)
        .describe(Source.LIST, "The lines typed below.")
        .describe(Source.FILE, "Each line of a text file in the offlineclient folder once. A line can hold the"
            + " same live words as a typed one.");
    private final TextSetting file = new TextSetting("File",
        "Name of the text file inside the offlineclient folder.", "spam.txt")
        .under(source, Source.FILE);
    private final TextLines lines = new TextLines("Messages",
        "How many lines to rotate through.", "The text to send. Click to type it." + TextLines.LIVE_WORDS,
        "minecraftoffline.net is OK I guess")
        .under(source, Source.LIST);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 5, 0.1, 60, 0.1, "s");
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Shifts each delay by a random amount to look less robotic.", false);
    private final NumberSetting spread = new NumberSetting("Spread",
        "The most a delay is shifted either way. A delay of 5s with a spread of 2s waits anywhere from 3s to 7s.",
        1, 0.1, 10, 0.1, "s")
        .under(randomise);
    private final BoolSetting vary = new BoolSetting("Vary text",
        "Makes small random changes to each message to get repeats past a spam filter.", false);
    private final NumberSetting variation = new NumberSetting("Variation",
        "How many changes each message gets. A letter changes case or doubles or the line gains a tail or a number.",
        1, 1, 10, 1)
        .under(vary);
    private final BoolSetting split = new BoolSetting("Split long lines",
        "Sends a long message in pieces instead of cutting it short.", false);
    private final NumberSetting splitLength = new NumberSetting("Split length",
        "How many characters each piece holds.", SharedConstants.MAX_CHAT_LENGTH, 1, SharedConstants.MAX_CHAT_LENGTH, 1)
        .max(SharedConstants.MAX_CHAT_LENGTH)
        .under(split);
    private final NumberSetting splitDelay = new NumberSetting("Split delay",
        "Ticks between one piece and the next.", 20, 0, 200, 1, " ticks")
        .under(split);
    private final NumberSetting stopAfter = new NumberSetting("Stop after",
        "Switches off after sending this many messages. Zero keeps going.", 0, 0, 100, 1);
    private final BoolSetting stopOnLeave = new BoolSetting("Stop on leave",
        "Turns itself off when you leave a world.", true);
    private final BoolSetting stopOnDisconnect = new BoolSetting("Stop on disconnect",
        "Turns itself off when the disconnected screen opens.", true);

    private final Deque<String> pieces = new ArrayDeque<>();
    private boolean command;
    private boolean splitting;
    private int after;
    private int timer;
    private boolean wasInGame;
    private int sent;

    private List<String> fileLines;
    private String fileName;
    private int fileNext;

    public Spam() {
        super("Spam", "Sends chat messages on a timer.", Category.MISC);
        addSettings(source, file);
        addSettings(lines.settings());
        addSettings(delay, randomise, spread, vary, variation, split, splitLength, splitDelay,
            stopAfter, stopOnLeave, stopOnDisconnect);
        searchTags("auto message", "advert", "chat spam", "command spam");
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
        sent = 0;
        lines.restart();
        fileLines = null;
        fileNext = 0;
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
        if (mc.getConnection() == null) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        if (pieces.isEmpty()) {
            timer = nextDelay();
            after = timer;
            if (!take()) {
                return;
            }
        }
        sendPiece();
    }

    private boolean take() {
        String text = nextLine();
        if (text == null) {
            return false;
        }
        // A command goes out as it is. Only chat is varied and split.
        command = text.startsWith("/");
        splitting = false;
        if (command) {
            pieces.add(text);
            return true;
        }
        if (vary.isOn()) {
            for (int i = 0; i < variation.getInt(); i++) {
                text = vary(text);
            }
        }
        if (split.isOn() && text.length() > splitLength.getInt()) {
            splitting = true;
            cutInto(text);
            return true;
        }
        pieces.add(text.length() > SharedConstants.MAX_CHAT_LENGTH
            ? text.substring(0, SharedConstants.MAX_CHAT_LENGTH) : text);
        return true;
    }

    // A piece of a split message is chat whatever it starts with.
    private void sendPiece() {
        String piece = pieces.getFirst();
        if (!(command ? ChatPace.MACRO.say(piece) : ChatPace.MACRO.chat(piece))) {
            timer = 1;
            return;
        }
        pieces.removeFirst();
        timer = splitting ? Math.max(1, splitDelay.getInt()) : after;
        if (pieces.isEmpty()) {
            lineSent();
        }
    }

    private void lineSent() {
        sent++;
        if (stopAfter.getInt() > 0 && sent >= stopAfter.getInt()) {
            tell("stopped after §f" + Tally.counted(sent, "message") + "§7.");
            setEnabled(false);
        } else {
            fileSpent();
        }
    }

    private boolean fileSpent() {
        if (!source.is(Source.FILE) || fileLines == null || fileNext < fileLines.size()) {
            return false;
        }
        tell("sent every line of §f" + fileName + "§7.");
        setEnabled(false);
        return true;
    }

    private String nextLine() {
        if (source.is(Source.LIST)) {
            return lines.pickWhole();
        }
        if (fileLines == null || !file.getValue().equals(fileName)) {
            readFile();
            if (fileLines == null) {
                return null;
            }
        }
        return fileSpent() ? null : SentLine.filled(fileLines.get(fileNext++));
    }

    private void readFile() {
        ListFile.Read read = ListFile.read(file.getValue());
        if (read.problem() != null) {
            disable(read.problem());
            return;
        }
        fileLines = read.lines();
        fileName = file.getValue();
        fileNext = 0;
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
        return Math.max(1, (int) Math.round(seconds * SharedConstants.TICKS_PER_SECOND));
    }

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
