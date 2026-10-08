package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.DisconnectedScreen;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

// A message starting with a slash is sent as a command.
public final class Spam extends Module {

    public enum Source { LIST, FILE }

    // Tacked onto the end of a message when the variation calls for it.
    private static final String TAILS = "!.~";

    private final EnumSetting<Source> source = new EnumSetting<>("Source",
        "Where the lines come from.", Source.LIST)
        .describe(Source.LIST, "The lines typed below.")
        .describe(Source.FILE, "Each line of a text file in the offlineclient folder once.");
    private final TextSetting file = new TextSetting("File",
        "Name of the text file inside the offlineclient folder.", "spam.txt")
        .under(source, Source.FILE);
    private final TextLines lines = new TextLines("Messages",
        "How many lines to rotate through.", "The text to send. Click to type it.",
        "minecraftoffline.net is OK I guess")
        .under(source, Source.LIST);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 5, 0.1, 60, 0.1, "s").min(0.1);
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Shifts each delay by a random amount to look less robotic.", false);
    private final NumberSetting spread = new NumberSetting("Spread",
        "The most a delay is shifted either way. A delay of 5s with a spread of 2s waits anywhere from 3s to 7s.",
        1, 0.1, 10, 0.1, "s").min(0.1)
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
        .min(1).max(SharedConstants.MAX_CHAT_LENGTH)
        .under(split);
    private final NumberSetting splitDelay = new NumberSetting("Split delay",
        "Ticks between one piece and the next.", 20, 0, 200, 1, " ticks").min(0)
        .under(split);
    private final NumberSetting stopAfter = new NumberSetting("Stop after",
        "Switches off after sending this many messages. Zero keeps going.", 0, 0, 100, 1).min(0);
    private final BoolSetting stopOnLeave = new BoolSetting("Stop on leave",
        "Turns itself off when you leave a world.", true);
    private final BoolSetting stopOnDisconnect = new BoolSetting("Stop on disconnect",
        "Turns itself off when the disconnected screen opens.", true);

    // Pieces of a split message still waiting to go out.
    private final Deque<String> pieces = new ArrayDeque<>();
    private int timer;
    private boolean wasInGame;
    private int sent;

    // The lines of the file and the name they were read from. Null until the file is read.
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
        if (!inGame() || mc.getConnection() == null) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        // The rest of a split message goes out before the next line starts.
        if (!pieces.isEmpty()) {
            sendPiece();
            return;
        }
        timer = nextDelay();

        String text = nextLine();
        if (text == null) {
            return;
        }
        // A command goes out as it is. Only chat is varied and split.
        if (text.startsWith("/")) {
            ChatUtil.say(text);
            lineSent();
            return;
        }
        if (vary.isOn()) {
            for (int i = 0; i < variation.getInt(); i++) {
                text = vary(text);
            }
        }
        if (split.isOn() && text.length() > splitLength.getInt()) {
            cutInto(text);
            sendPiece();
            return;
        }
        if (text.length() > SharedConstants.MAX_CHAT_LENGTH) {
            text = text.substring(0, SharedConstants.MAX_CHAT_LENGTH);
        }
        mc.getConnection().sendChat(text);
        lineSent();
    }

    // A split line counts as one message once its last piece is out.
    private void sendPiece() {
        timer = Math.max(1, splitDelay.getInt());
        mc.getConnection().sendChat(pieces.removeFirst());
        if (pieces.isEmpty()) {
            lineSent();
        }
    }

    private void lineSent() {
        sent++;
        if (stopAfter.getInt() > 0 && sent >= stopAfter.getInt()) {
            ChatUtil.message("§bSpam §7stopped after §f" + Tally.counted(sent, "message") + "§7.");
            setEnabled(false);
        } else {
            fileSpent();
        }
    }

    // Switches off once every line of the file has gone out. True when it did.
    private boolean fileSpent() {
        if (!source.is(Source.FILE) || fileLines == null || fileNext < fileLines.size()) {
            return false;
        }
        ChatUtil.message("§bSpam §7sent every line of §f" + fileName + "§7.");
        setEnabled(false);
        return true;
    }

    // The next line of the list or the file. Null whilst there is nothing to send.
    private String nextLine() {
        if (source.is(Source.LIST)) {
            return lines.pick();
        }
        if (fileLines == null || !file.getValue().equals(fileName)) {
            readFile();
            if (fileLines == null) {
                return null;
            }
        }
        return fileSpent() ? null : fileLines.get(fileNext++);
    }

    // A file that cannot be read or holds no text switches the module off and says why.
    private void readFile() {
        Path path = DataFiles.path(file.getValue());
        Optional<String> text = DataFiles.readText(path);
        List<String> read = text.map(Spam::textLines).orElse(List.of());
        if (read.isEmpty()) {
            disable(text.isEmpty() ? "Could not read " + path.getFileName() + " from the offlineclient folder."
                : path.getFileName() + " holds no lines.");
            return;
        }
        fileLines = read;
        fileName = file.getValue();
        fileNext = 0;
    }

    private static List<String> textLines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
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
