package com.jellypudding.offlineclient.util;

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A song read from a Note Block Studio file. Notes are keyed by the game
 * tick they play on. The file format is little endian throughout.
 */
public final class NoteSong {

    // One note block pitch. Zero to twenty four.
    public record Note(NoteBlockInstrument instrument, int pitch) {
    }

    // Note Block Studio numbers keys from this offset below the note block range.
    private static final int KEY_OFFSET = 33;

    public static final int LOWEST = 0;
    public static final int HIGHEST = 24;

    private static final NoteBlockInstrument[] INSTRUMENTS = {
        NoteBlockInstrument.HARP, NoteBlockInstrument.BASS, NoteBlockInstrument.BASEDRUM,
        NoteBlockInstrument.SNARE, NoteBlockInstrument.HAT, NoteBlockInstrument.GUITAR,
        NoteBlockInstrument.FLUTE, NoteBlockInstrument.BELL, NoteBlockInstrument.CHIME,
        NoteBlockInstrument.XYLOPHONE, NoteBlockInstrument.IRON_XYLOPHONE,
        NoteBlockInstrument.COW_BELL, NoteBlockInstrument.DIDGERIDOO, NoteBlockInstrument.BIT,
        NoteBlockInstrument.BANJO, NoteBlockInstrument.PLING
    };

    private final String title;
    private final String author;
    private final TreeMap<Integer, List<Note>> ticks;
    private final Set<Note> needed;

    private NoteSong(String title, String author, TreeMap<Integer, List<Note>> ticks) {
        this.title = title;
        this.author = author;
        this.ticks = ticks;
        Set<Note> unique = new LinkedHashSet<>();
        for (List<Note> notes : ticks.values()) {
            unique.addAll(notes);
        }
        this.needed = Collections.unmodifiableSet(unique);
    }

    public String title() {
        return title;
    }

    public String author() {
        return author;
    }

    // Every distinct note the song plays. One note block is needed for each.
    public Set<Note> needed() {
        return needed;
    }

    public List<Note> notesAt(int tick) {
        return ticks.getOrDefault(tick, List.of());
    }

    public int lastTick() {
        return ticks.isEmpty() ? 0 : ticks.lastKey();
    }

    /**
     * Notes outside the note block range are moved an octave at a time until
     * they fit. Off means such notes are dropped instead.
     */
    public NoteSong foldedIntoRange(boolean fold) {
        TreeMap<Integer, List<Note>> result = new TreeMap<>();
        for (Map.Entry<Integer, List<Note>> entry : ticks.entrySet()) {
            List<Note> kept = new ArrayList<>();
            for (Note note : entry.getValue()) {
                int pitch = note.pitch();
                if (fold) {
                    while (pitch < LOWEST) {
                        pitch += 12;
                    }
                    while (pitch > HIGHEST) {
                        pitch -= 12;
                    }
                }
                if (pitch >= LOWEST && pitch <= HIGHEST) {
                    kept.add(new Note(note.instrument(), pitch));
                }
            }
            if (!kept.isEmpty()) {
                result.put(entry.getKey(), kept);
            }
        }
        return new NoteSong(title, author, result);
    }

    public static NoteSong read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(new DataInputStream(in), file.getFileName().toString());
        }
    }

    private static NoteSong read(DataInputStream in, String fileName) throws IOException {
        int version = 0;
        short length = readShort(in);
        // The new format starts with a zero length and then a version byte.
        if (length == 0) {
            version = in.readByte();
            in.readByte();
            if (version >= 3) {
                readShort(in);
            }
        }
        readShort(in);
        String title = readString(in);
        String author = readString(in);
        readString(in);
        readString(in);
        float tempo = readShort(in) / 100f;
        if (tempo <= 0) {
            throw new IOException("Bad tempo " + tempo);
        }
        in.readBoolean();
        in.readByte();
        in.readByte();
        readInt(in);
        readInt(in);
        readInt(in);
        readInt(in);
        readInt(in);
        readString(in);
        if (version >= 4) {
            in.readByte();
            in.readByte();
            readShort(in);
        }

        TreeMap<Integer, List<Note>> ticks = new TreeMap<>();
        double position = -1;
        while (true) {
            short jump = readShort(in);
            if (jump == 0) {
                break;
            }
            // Song ticks run at the tempo. Game ticks run at twenty a second.
            position += jump * (20f / tempo);
            while (true) {
                short layerJump = readShort(in);
                if (layerJump == 0) {
                    break;
                }
                byte instrument = in.readByte();
                byte key = in.readByte();
                if (version >= 4) {
                    in.readUnsignedByte();
                    in.readUnsignedByte();
                    readShort(in);
                }
                // Custom instruments have no note block and are left out.
                if (instrument >= 0 && instrument < INSTRUMENTS.length) {
                    Note note = new Note(INSTRUMENTS[instrument], key - KEY_OFFSET);
                    ticks.computeIfAbsent((int) Math.round(position), k -> new ArrayList<>()).add(note);
                }
            }
        }
        return new NoteSong(title.isBlank() ? fileName : title, author, ticks);
    }

    private static short readShort(DataInputStream in) throws IOException {
        int low = in.readUnsignedByte();
        int high = in.readUnsignedByte();
        return (short) (low | high << 8);
    }

    private static int readInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value |= in.readUnsignedByte() << (8 * i);
        }
        return value;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readInt(in);
        if (length < 0 || length > in.available()) {
            throw new EOFException("Bad string length " + length);
        }
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = (char) in.readUnsignedByte();
            text.append(c == '\r' ? ' ' : c);
        }
        return text.toString();
    }
}
