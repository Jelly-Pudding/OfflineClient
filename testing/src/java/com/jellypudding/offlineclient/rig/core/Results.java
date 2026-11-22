package com.jellypudding.offlineclient.rig.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

// Writes each case to disk the moment it ends. A game that dies mid run leaves every finished
// case behind and the name of the one it died in.
public final class Results {

    public static final String FILE = "results.jsonl";
    public static final String RUNNING = "running.json";
    public static final String SHOTS = "shots";
    public static final String SUITE = "suite";
    public static final String NAME = "case";

    private final Path out;

    // A run under a name used before would add its cases to the old ones.
    public Results(Path out) {
        this.out = out;
        try {
            Files.deleteIfExists(out.resolve(FILE));
            Files.deleteIfExists(out.resolve(RUNNING));
            Files.createDirectories(out.resolve(SHOTS));
            try (Stream<Path> old = Files.list(out.resolve(SHOTS))) {
                for (Path shot : old.filter(Files::isRegularFile).toList()) {
                    Files.delete(shot);
                }
            }
        } catch (IOException e) {
            throw new RigFailure("The result folder could not be made ready at " + out, e);
        }
    }

    public Path out() {
        return out;
    }

    public Path shots() {
        return out.resolve(SHOTS);
    }

    public void begin(String suite, String name) {
        JsonObject running = new JsonObject();
        running.addProperty(SUITE, suite);
        running.addProperty(NAME, name);
        write(out.resolve(RUNNING), running.toString(), false);
    }

    public void end(Outcome outcome) {
        write(out.resolve(FILE), outcome.toJson() + "\n", true);
        try {
            Files.deleteIfExists(out.resolve(RUNNING));
        } catch (IOException e) {
            throw new RigFailure("The mark of the running case could not be removed", e);
        }
    }

    private static void write(Path file, String text, boolean append) {
        try {
            if (append) {
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RigFailure("A result could not be written to " + file, e);
        }
    }
}
