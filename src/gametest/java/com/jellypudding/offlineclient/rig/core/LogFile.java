package com.jellypudding.offlineclient.rig.core;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads the game's own log file as it grows and splits it into entries. The game and the report
// both read the log through this.
public final class LogFile {

    // One logged event with the lines of its stack trace.
    public record Entry(String time, String thread, String level, String logger, String text) {

        public boolean isError() {
            return level.equals("ERROR") || level.equals("FATAL");
        }

        public boolean isWarning() {
            return level.equals("WARN");
        }

        public String firstLine() {
            int end = text.indexOf('\n');
            return end < 0 ? text : text.substring(0, end);
        }

        // The first line and the cause when the entry carries a stack trace.
        public String brief() {
            String first = firstLine();
            int end = text.indexOf('\n');
            if (end < 0) {
                return first;
            }
            String second = text.substring(end + 1);
            int next = second.indexOf('\n');
            second = next < 0 ? second : second.substring(0, next);
            return second.startsWith("\tat ") || first.contains(second.trim()) ? first : first + " | " + second.trim();
        }
    }

    private static final Pattern HEAD = Pattern.compile(
        "^\\[(\\d\\d:\\d\\d:\\d\\d)] \\[(.+?)/(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)] \\((.*?)\\) (.*)$");

    private final Path path;
    private long offset;

    public LogFile(Path path) {
        this.path = path;
    }

    // Every entry written since the last call.
    public List<Entry> readNew() {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            long length = file.length();
            // The log starts again when it rolls over.
            if (length < offset) {
                offset = 0;
            }
            byte[] bytes = new byte[(int) (length - offset)];
            file.seek(offset);
            file.readFully(bytes);
            // Half a line may be on disk. It is read with the next call.
            int whole = lastLineEnd(bytes);
            offset += whole;
            return parse(new String(bytes, 0, whole, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RigFailure("The log could not be read at " + path, e);
        }
    }

    // Forgets what is in the log up to this moment.
    public void skipToEnd() {
        readNew();
    }

    private static int lastLineEnd(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    public static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        String[] head = null;
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n")) {
            line = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            Matcher matcher = HEAD.matcher(line);
            if (matcher.matches()) {
                add(entries, head, body);
                head = new String[] {matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)};
                body = new StringBuilder(matcher.group(5));
            } else if (head != null) {
                body.append('\n').append(line);
            }
        }
        add(entries, head, body);
        return entries;
    }

    private static void add(List<Entry> entries, String[] head, StringBuilder body) {
        if (head != null) {
            entries.add(new Entry(head[0], head[1], head[2], head[3], body.toString().stripTrailing()));
        }
    }
}
