package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.Consumer;

// A text file of lines the player keeps in the offlineclient folder such as a list of words.
// Blank lines are left out. A file far larger than any list a person writes is never read.
public final class ListFile {

    // Two million letters. A list of every English word fits with room to spare.
    private static final long LARGEST_BYTES = 2_000_000;

    // A file that is asked for again and again is looked at on disk this often at most.
    private static final long RECHECK_MS = 1000;

    // The lines of a file. The problem says why there are none and is null when there are some.
    public record Read(List<String> lines, String problem) {
    }

    // The file last read and when it was last changed then. A file changed since is read again.
    private Path readFrom;
    private FileTime readAt;
    private List<String> kept = List.of();
    // The name last asked for and when the disk was last looked at for it.
    private String askedFor;
    private long checkedAt;
    // A name already told to be missing. It is not told again until the file has been found.
    private String missing;

    // Reads the whole of the named file in the offlineclient folder once.
    public static Read read(String fileName) {
        String name = fileName.strip();
        Path path = DataFiles.inside(DataFiles.path(), name);
        return path == null ? new Read(List.of(), missingText(name)) : read(path, name);
    }

    private static Read read(Path path, String name) {
        try {
            if (!Files.isRegularFile(path)) {
                return new Read(List.of(), missingText(name));
            }
            if (Files.size(path) > LARGEST_BYTES) {
                return new Read(List.of(), name + " is too large to be a list of lines.");
            }
            List<String> lines = Files.readString(path).lines().map(String::strip)
                .filter(line -> !line.isEmpty()).toList();
            return new Read(lines, lines.isEmpty() ? name + " holds no lines." : null);
        } catch (IOException error) {
            OfflineClient.LOG.error("Failed to read {}", name, error);
            return new Read(List.of(), name + " could not be read from the offlineclient folder.");
        }
    }

    // The lines of the named file. Empty whilst there is no such file. The file is read again
    // whenever it changes and a problem with it is told each time it is read. A missing file is
    // told of once. The same list comes back until then.
    public List<String> lines(String fileName, Consumer<String> problems) {
        long now = Util.getMillis();
        if (fileName.equals(askedFor) && now - checkedAt < RECHECK_MS) {
            return kept;
        }
        askedFor = fileName;
        checkedAt = now;
        Path path = fileName.isBlank() ? null : DataFiles.inside(DataFiles.path(), fileName.strip());
        FileTime changed = path == null ? null : changedAt(path);
        if (changed == null) {
            if (!fileName.isBlank() && !fileName.equals(missing)) {
                missing = fileName;
                problems.accept(missingText(fileName.strip()));
            }
            return forget();
        }
        missing = null;
        if (!path.equals(readFrom) || !changed.equals(readAt)) {
            readFrom = path;
            readAt = changed;
            Read read = read(path, fileName.strip());
            kept = read.lines();
            if (read.problem() != null) {
                problems.accept(read.problem());
            }
        }
        return kept;
    }

    // Null when there is no such file to read.
    private static FileTime changedAt(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path) : null;
        } catch (IOException error) {
            return null;
        }
    }

    private static String missingText(String name) {
        return name + " is not in the offlineclient folder.";
    }

    private List<String> forget() {
        readFrom = null;
        readAt = null;
        kept = List.of();
        return kept;
    }
}
