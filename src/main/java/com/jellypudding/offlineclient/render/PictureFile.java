package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.config.DataFiles;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.util.PngInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

// A PNG of the player's own in the pictures folder of the client. Whilst it is wanted it is looked at
// once a second off the game thread and read again whenever the file changes. A file past the bounds
// is turned down before it is decoded.
public final class PictureFile {

    public static final String FOLDER_WORDS = "offlineclient/pictures";

    private static final Path FOLDER = DataFiles.path("pictures");
    private static final String EXTENSION = ".png";

    // The texture of the largest picture let in takes sixteen megabytes.
    private static final int MOST_SIDE = 2048;
    private static final int MOST_MEGABYTES = 8;
    private static final int MOST_BYTES = MOST_MEGABYTES * 1024 * 1024;

    // The time of a file that has never been read.
    private static final long NEVER = Long.MIN_VALUE;

    // Hears on the game thread what a look found whenever that changed. The pixels belong to the
    // taker from then on. They are null when there is nothing to show and the problem then says why
    // or is null for an empty name.
    @FunctionalInterface
    public interface Taker {
        void take(NativeImage pixels, String problem);
    }

    // What one look at the file found. Same is true when the file is as it was at the last look.
    private record Look(String typed, long stamp, boolean same, NativeImage pixels, String problem) {

        private static Look unchanged(String typed, long stamp) {
            return new Look(typed, stamp, true, null, null);
        }

        private static Look failed(String typed, long stamp, String problem) {
            return new Look(typed, stamp, false, null, problem);
        }

        // A picture read for a file since let go is thrown away.
        private void drop() {
            if (pixels != null) {
                pixels.close();
            }
        }
    }

    private final Taker taker;
    private final FileLook<Look> looks = new FileLook<>(this::take, Look::drop);

    // The name and file time of what was last handed over and whether it was a picture.
    private String shownTyped = "";
    private long shownStamp = NEVER;
    private String shownProblem;
    private boolean showing;

    public PictureFile(Taker taker) {
        this.taker = taker;
    }

    // Looks at the named file once the last look is back and a second has passed. The reading and
    // the decoding happen on a worker and the game thread takes what it found.
    public void look(String name) {
        if (!looks.due()) {
            return;
        }
        String typed = name.trim();
        long known = typed.equals(shownTyped) ? shownStamp : NEVER;
        looks.start(() -> read(typed, known), () -> Look.failed(typed, NEVER, typed + " cannot be read as a PNG."));
    }

    // Forgets what was handed over. The next look reads the file afresh.
    public void release() {
        shownTyped = "";
        shownStamp = NEVER;
        shownProblem = null;
        showing = false;
        looks.release();
    }

    // Off the game thread. A name that leads out of the folder is turned down. A file turned down for
    // what it holds keeps its time and is read again only once it changes.
    private static Look read(String typed, long known) {
        if (typed.isEmpty()) {
            return Look.failed(typed, NEVER, null);
        }
        String name = typed.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? typed : typed + EXTENSION;
        long stamp = NEVER;
        try {
            Files.createDirectories(FOLDER);
            Path path = DataFiles.inside(FOLDER, name);
            if (path == null || !Files.isRegularFile(path)) {
                return Look.failed(typed, NEVER, "There is no " + name + " in " + FOLDER_WORDS + ".");
            }
            stamp = Files.getLastModifiedTime(path).toMillis();
            if (stamp == known) {
                return Look.unchanged(typed, stamp);
            }
            byte[] bytes = DataFiles.readAtMost(path, MOST_BYTES);
            PngInfo size = bytes == null ? null : PngInfo.fromBytes(bytes);
            if (size == null || size.width() > MOST_SIDE || size.height() > MOST_SIDE) {
                return Look.failed(typed, stamp, name + " is too big. The most is " + MOST_SIDE
                    + " pixels a side and " + MOST_MEGABYTES + " megabytes.");
            }
            return new Look(typed, stamp, false, NativeImage.read(bytes), null);
        } catch (IOException | RuntimeException e) {
            return Look.failed(typed, stamp, name + " cannot be read as a PNG.");
        }
    }

    // On the game thread. A failure that repeats the last one is not handed over again.
    private void take(Look found) {
        if (found.same()) {
            return;
        }
        boolean repeat = found.pixels() == null && !showing && Objects.equals(found.problem(), shownProblem);
        shownTyped = found.typed();
        shownStamp = found.stamp();
        shownProblem = found.problem();
        showing = found.pixels() != null;
        if (!repeat) {
            taker.take(found.pixels(), found.problem());
        }
    }
}
