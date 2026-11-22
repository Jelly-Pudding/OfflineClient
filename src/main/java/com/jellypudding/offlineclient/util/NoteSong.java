package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.config.DataFiles;
import net.minecraft.SharedConstants;
import net.minecraft.sounds.SoundEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

// A song read from a Note Block Studio file. Notes are keyed by the game
// tick they play on. The file format is little endian throughout.
public final class NoteSong {

    // One note block pitch. Zero to twenty four.
    public record Note(NoteBlockInstrument instrument, int pitch) {
    }

    // Note Block Studio numbers keys from this offset below the note block range.
    private static final int KEY_OFFSET = 33;

    private static final String NAME_HEADER = "// Name:";
    private static final String AUTHOR_HEADER = "// Author:";

    private static final int LOWEST = 0;
    private static final int HIGHEST = 24;
    private static final int OCTAVE = 12;

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

    public Set<Note> needed() {
        return needed;
    }

    public List<Note> notesAt(int tick) {
        return ticks.getOrDefault(tick, List.of());
    }

    public int lastTick() {
        return ticks.isEmpty() ? 0 : ticks.lastKey();
    }

    // Notes outside the note block range are moved an octave at a time until
    // they fit. Off means such notes are dropped instead.
    public NoteSong foldedIntoRange(boolean fold) {
        TreeMap<Integer, List<Note>> result = new TreeMap<>();
        for (Map.Entry<Integer, List<Note>> entry : ticks.entrySet()) {
            List<Note> kept = new ArrayList<>();
            for (Note note : entry.getValue()) {
                int pitch = note.pitch();
                if (fold) {
                    while (pitch < LOWEST) {
                        pitch += OCTAVE;
                    }
                    while (pitch > HIGHEST) {
                        pitch -= OCTAVE;
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

    // A null in the map drops the instrument.
    public NoteSong remapped(Map<NoteBlockInstrument, NoteBlockInstrument> map) {
        if (map.isEmpty()) {
            return this;
        }
        TreeMap<Integer, List<Note>> result = new TreeMap<>();
        for (Map.Entry<Integer, List<Note>> entry : ticks.entrySet()) {
            List<Note> kept = new ArrayList<>();
            for (Note note : entry.getValue()) {
                if (note.instrument() == null || !map.containsKey(note.instrument())) {
                    kept.add(note);
                    continue;
                }
                NoteBlockInstrument swapped = map.get(note.instrument());
                if (swapped != null) {
                    kept.add(new Note(swapped, note.pitch()));
                }
            }
            if (!kept.isEmpty()) {
                result.put(entry.getKey(), kept);
            }
        }
        return new NoteSong(title, author, result);
    }

    // Null for any other sound and for a mob head which plays no notes.
    public static NoteBlockInstrument instrumentOf(SoundEvent sound) {
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            if (instrument.isTunable() && instrument.getSoundEvent().value() == sound) {
                return instrument;
            }
        }
        return null;
    }

    // The note a note block sound of that pitch was struck on. Each step up multiplies the pitch
    // by the twelfth root of two and the middle note of the twenty five plays at one.
    public static int pitchOf(float soundPitch) {
        double steps = OCTAVE * Math.log(soundPitch) / Math.log(2);
        return Math.clamp(Math.round(steps) + OCTAVE, LOWEST, HIGHEST);
    }

    // Each line holds the tick and the pitch and the number of the instrument. False after logging
    // why it could not be written.
    public static boolean write(Path file, String title, String author, SortedMap<Integer, List<Note>> ticks) {
        List<String> lines = new ArrayList<>();
        lines.add(NAME_HEADER + " " + title);
        lines.add(AUTHOR_HEADER + " " + author);
        ticks.forEach((tick, notes) -> {
            for (Note note : notes) {
                lines.add(tick + ":" + note.pitch() + ":" + note.instrument().ordinal());
            }
        });
        return DataFiles.writeSafely(file, temp -> Files.write(temp, lines));
    }

    public static NoteSong read(Path file) throws IOException {
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")) {
            return readText(file);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return read(new DataInputStream(in), file.getFileName().toString());
        }
    }

    // A plain list of lines. Each holds a tick and a note with an optional instrument
    // number on the end.
    private static NoteSong readText(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String title = fileName.substring(0, fileName.length() - 4);
        String author = "";
        TreeMap<Integer, List<Note>> ticks = new TreeMap<>();
        NoteBlockInstrument[] all = NoteBlockInstrument.values();
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.startsWith(NAME_HEADER)) {
                title = line.substring(NAME_HEADER.length()).trim();
                continue;
            }
            if (line.startsWith(AUTHOR_HEADER)) {
                author = line.substring(AUTHOR_HEADER.length()).trim();
                continue;
            }
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String[] parts = line.split(":");
            if (parts.length < 2) {
                continue;
            }
            try {
                int at = Integer.parseInt(parts[0].trim());
                int pitch = Integer.parseInt(parts[1].trim());
                int kind = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
                if (kind < 0 || kind >= all.length) {
                    continue;
                }
                ticks.computeIfAbsent(at, k -> new ArrayList<>()).add(new Note(all[kind], pitch));
            } catch (NumberFormatException ignored) {
                // A line that is not two numbers is not a note.
            }
        }
        return new NoteSong(title, author, ticks);
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
            // The tempo counts song ticks a second.
            position += jump * (SharedConstants.TICKS_PER_SECOND / tempo);
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
