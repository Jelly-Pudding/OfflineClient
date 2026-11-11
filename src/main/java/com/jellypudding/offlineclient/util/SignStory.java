package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.entity.SignText;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The text of a file that AutoSign runs on from sign to sign four lines at a time. It owns the
// rows that name the file and step back through it and keeps how far the signs have got.
public final class SignStory {

    // The largest story file that is read.
    private static final long MAX_STORY_BYTES = 1 << 20;
    // Characters looked at for the four lines of one sign. Far more than any sign holds.
    private static final int STORY_WINDOW = 512;

    // The lines one sign takes from the story and where the next sign carries on.
    public record Chunk(List<String> lines, int end) {
    }

    private final TextSetting file = new TextSetting("File",
        "Name of the text file inside the offlineclient folder.", "autosign.txt");
    private final ActionSetting redo = new ActionSetting("Redo last sign",
        "Steps the text back to where the last sign began. The next sign takes those lines again.",
        this::redoSign);
    private final ActionSetting startOver = new ActionSetting("Start over",
        "Goes back to the top of the file.", this::startOver)
        .confirm();
    private final NumberSetting place = new NumberSetting("Story place",
        "How many characters of the file the signs written have taken.", 0, 0, 1, 1).internal();
    private final TextSetting placeFile = new TextSetting("Story of",
        "The file the story place counts in.", "").internal();

    // The text of the file. Null whilst none could be read. Read on the network thread.
    private volatile String text;
    // The file name the last read was for whether it worked or not.
    private String readName;
    // Where each sign of the story written this session began. Redo steps back through them.
    private final Deque<Integer> signStarts = new ArrayDeque<>();
    // The four lines at the story place for each sign width asked about.
    private final Map<Integer, Chunk> chunks = new HashMap<>();
    private int chunksFrom = -1;
    private boolean toldEnd;
    private final ChatWarning noStory = new ChatWarning();

    public Setting<?>[] settings() {
        return new Setting<?>[] {file, redo, startOver, place, placeFile};
    }

    // Shows the rows the player uses only whilst the parent holds the value.
    public <E extends Enum<E>> SignStory under(EnumSetting<E> parent, E value) {
        Setting.under(new Setting<?>[] {file, redo, startOver}, parent, value);
        return this;
    }

    // The next read takes the file afresh even under the same name.
    public void readAgain() {
        readName = null;
    }

    // Once a tick whilst the story is in use. Chat says when the signs have taken all of it.
    public void read() {
        readFile();
        if (text != null && !toldEnd && start() == -1) {
            toldEnd = true;
            ChatUtil.tell("AutoSign", "has written all of §f" + readName + "§7.");
        }
    }

    // True whilst some of the story is still to be written. Safe on any thread.
    public boolean left() {
        String story = text;
        return story != null && place.getInt() < story.length();
    }

    // The next four lines of the story at the width of a sign. Null once the story is told.
    public Chunk chunk(int width) {
        String story = text;
        int from = start();
        if (from == -1) {
            return null;
        }
        if (from != chunksFrom) {
            chunks.clear();
            chunksFrom = from;
        }
        return chunks.computeIfAbsent(width, key -> cut(story, from, key));
    }

    // The front of a sign took its lines. The next sign carries on after them.
    public void took(Chunk chunk) {
        signStarts.push(place.getInt());
        place.setValue((double) chunk.end());
    }

    // Where the next sign of the story begins. Blank lines ahead of it are passed over and a
    // sign never comes out empty. Minus one once the story is told or whilst none is read.
    private int start() {
        String story = text;
        if (story == null) {
            return -1;
        }
        int from = place.getInt();
        while (from < story.length() && Character.isWhitespace(story.charAt(from))) {
            from++;
        }
        return from < story.length() ? from : -1;
    }

    // Reads the story file once for each name typed. A file that cannot be used is named in chat.
    private void readFile() {
        String name = file.getValue().strip();
        if (name.equals(readName)) {
            return;
        }
        readName = name;
        text = null;
        toldEnd = false;
        chunksFrom = -1;
        String story = storyText(name);
        if (story == null) {
            return;
        }
        noStory.clear();
        // The place belongs to one file. Another starts at its top.
        if (!name.equals(placeFile.getValue())) {
            placeFile.setValue(name);
            place.setValue(0.0);
            signStarts.clear();
        }
        text = story.replace("\r", "").replace('\t', ' ');
    }

    // The text of the named file in the offlineclient folder. Null when there is none to use and
    // chat says why. A name that leads out of the folder is refused. A file there could hold
    // anything and the signs would show it to everyone.
    private String storyText(String name) {
        if (name.isEmpty()) {
            noStory.say("AutoSign needs the name of a story file.");
            return null;
        }
        Path path = DataFiles.inside(DataFiles.path(), name);
        if (path == null) {
            noStory.say("AutoSign cannot use " + name + " as a file in the offlineclient folder.");
            return null;
        }
        if (!Files.isRegularFile(path)) {
            noStory.say("AutoSign found no file called " + name + " in the offlineclient folder.");
            return null;
        }
        try {
            if (Files.size(path) > MAX_STORY_BYTES) {
                noStory.say("AutoSign only reads a story file of one megabyte at most.");
                return null;
            }
            return Files.readString(path);
        } catch (CharacterCodingException e) {
            noStory.say("AutoSign could not read " + name + ". Save it as UTF 8 text.");
        } catch (IOException e) {
            noStory.say("AutoSign could not read " + name + ".");
        }
        return null;
    }

    private String redoSign() {
        Integer start = signStarts.poll();
        if (start == null) {
            return "No sign to redo";
        }
        place.setValue((double) start);
        toldEnd = false;
        return "Back one sign";
    }

    private String startOver() {
        signStarts.clear();
        place.setValue(0.0);
        toldEnd = false;
        return "Back at the top";
    }

    // The game's own line breaking decides where each line ends.
    private static Chunk cut(String text, int from, int width) {
        String window = text.substring(from, Math.min(text.length(), from + STORY_WINDOW));
        List<String> lines = new ArrayList<>(SignText.LINES);
        int[] seen = {0};
        int[] end = {from + window.length()};
        OfflineClient.MC.font.getSplitter().splitLines(window, width, Style.EMPTY, false, (style, start, stop) -> {
            if (seen[0] < SignText.LINES) {
                lines.add(window.substring(start, stop));
            } else if (seen[0] == SignText.LINES) {
                end[0] = from + start;
            }
            seen[0]++;
        });
        return new Chunk(lines, Math.max(end[0], from + 1));
    }
}
