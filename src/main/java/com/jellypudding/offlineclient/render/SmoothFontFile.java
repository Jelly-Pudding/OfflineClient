package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

// The bytes the smooth font is made from. The player's own font.ttf in the client folder wins whilst
// the game's TrueType loader takes it and the built in font stands in otherwise. The files are looked
// at once a second off the game thread and chat says once what went wrong.
final class SmoothFontFile {

    // Inter 4.1 by the Inter Project Authors under the SIL Open Font Licence. The licence lies beside it.
    private static final String BUILT_IN = "/assets/offlineclient/font/inter.ttf";
    private static final String OWN_NAME = "font.ttf";
    // The em in GUI pixels. Capitals stand seven pixels tall like the game's own at every scale from one to four.
    private static final float EM = 9.3f;
    // No font made for reading comes near this.
    private static final int MOST_MEGABYTES = 32;
    private static final int MOST_BYTES = MOST_MEGABYTES * 1024 * 1024;
    // File times of a font that is not there and of one never looked at.
    private static final long ABSENT = Long.MIN_VALUE;
    private static final long UNREAD = Long.MIN_VALUE + 1;
    // Only the loader sees this name. The bytes it reads are always the chosen ones.
    private static final Identifier FILE = Identifier.fromNamespaceAndPath("offlineclient", "smooth.ttf");

    private static final String BUILT_IN_FAILED =
        "The smooth font cannot be loaded. Text stays in the game's own font.";
    private static final String OWN_TOO_BIG = OWN_NAME + " in the offlineclient folder is over " + MOST_MEGABYTES
        + " megabytes. The built in smooth font is used.";
    private static final String OWN_UNREADABLE = OWN_NAME
        + " in the offlineclient folder cannot be read. The built in smooth font is used.";
    private static final String OWN_NOT_TRUETYPE = OWN_NAME
        + " in the offlineclient folder is not a TrueType font the game can read."
        + " The built in smooth font is used.";
    private static final String OWN_UNDRAWABLE = OWN_NAME
        + " in the offlineclient folder has letters the game cannot draw. The built in smooth font is used.";

    // The bytes of a font file. Stamp is the file time of the player's own and absent for the built in one.
    record Source(byte[] bytes, long stamp) {

        boolean own() {
            return stamp != ABSENT;
        }
    }

    // What one look found. Same is true whilst the player's file is as it was. Source is null when no
    // font can be used and problem says why for chat or is null when all went well.
    private record Look(boolean same, long stamp, Source source, String problem) {

        private static final Look SAME = new Look(true, UNREAD, null, null);
    }

    // A late look holds nothing that needs closing.
    private final FileLook<Look> looks = new FileLook<>(this::take, found -> {
    });

    private Source source;
    private long ownStamp = UNREAD;
    // A file of the player's own that failed after its check stays unused until it changes.
    private long badStamp = ABSENT;
    private boolean builtInBad;
    private String said;
    private String pending;

    // The bytes in use. Null whilst the first look is out or no font can be used.
    Source current() {
        return source;
    }

    // The font made from these bytes failed after its check passed. It stays unused until its file
    // changes and the next look picks again.
    void failed(Source from, Throwable cause) {
        OfflineClient.LOG.error("The smooth font cannot be drawn", cause);
        if (from.own()) {
            badStamp = from.stamp();
        } else {
            builtInBad = true;
        }
        if (source == from) {
            source = null;
            ownStamp = UNREAD;
            looks.hurry();
        }
    }

    // Once a tick whilst the client is awake. A line waiting for chat is said and the folder looked at.
    void tick() {
        if (pending != null && OfflineClient.MC.player != null) {
            ChatUtil.error(pending);
            pending = null;
        }
        look();
    }

    // Forgets the files. The next look reads them afresh and tries again what failed.
    void release() {
        source = null;
        ownStamp = UNREAD;
        badStamp = ABSENT;
        builtInBad = false;
        said = null;
        pending = null;
        looks.release();
    }

    // The files are read and checked on a worker and the game thread takes what was found.
    void look() {
        if (!looks.due()) {
            return;
        }
        Path own = DataFiles.path(OWN_NAME);
        long known = ownStamp;
        long bad = badStamp;
        boolean builtIn = !builtInBad;
        looks.start(() -> read(own, known, bad, builtIn), () -> new Look(false, known, null, BUILT_IN_FAILED));
    }

    // Off the game thread. The player's own file wins whilst the game's loader takes it.
    private static Look read(Path own, long known, long bad, boolean builtInUsable) {
        long stamp = stampOf(own);
        if (stamp == known) {
            return Look.SAME;
        }
        String problem = null;
        if (stamp != ABSENT && stamp == bad) {
            problem = OWN_UNDRAWABLE;
        } else if (stamp != ABSENT) {
            try {
                byte[] bytes = DataFiles.readAtMost(own, MOST_BYTES);
                if (bytes == null) {
                    problem = OWN_TOO_BIG;
                } else if (loads(bytes)) {
                    return new Look(false, stamp, new Source(bytes, stamp), null);
                } else {
                    problem = OWN_NOT_TRUETYPE;
                }
            } catch (IOException e) {
                problem = OWN_UNREADABLE;
            }
        }
        byte[] builtIn = builtInUsable ? builtIn() : null;
        if (builtIn == null || !loads(builtIn)) {
            return new Look(false, stamp, null, BUILT_IN_FAILED);
        }
        return new Look(false, stamp, new Source(builtIn, ABSENT), problem);
    }

    private static long stampOf(Path own) {
        try {
            return Files.isRegularFile(own) ? Files.getLastModifiedTime(own).toMillis() : ABSENT;
        } catch (IOException e) {
            return ABSENT;
        }
    }

    private static byte[] builtIn() {
        try (InputStream in = SmoothFontFile.class.getResourceAsStream(BUILT_IN)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    // Whether the game's TrueType loader takes the file. It is opened and closed straight away.
    private static boolean loads(byte[] bytes) {
        try (GlyphProvider glyphs = load(bytes, 1)) {
            return glyphs != null;
        } catch (IOException | RuntimeException | LinkageError e) {
            OfflineClient.LOG.warn("The game's TrueType loader turned a smooth font file down with {}", e.toString());
            return false;
        }
    }

    // The steps a font file of a resource pack takes with these bytes in place of the pack's file.
    static GlyphProvider load(byte[] bytes, int scale) throws IOException {
        return new TrueTypeGlyphProviderDefinition(FILE, EM, scale, TrueTypeGlyphProviderDefinition.Shift.NONE, "")
            .unpack().left().orElseThrow().load(new OneFile(bytes));
    }

    // On the game thread. A look that finished early is taken straight away and that may be in the
    // middle of a frame. SmoothFont lets go of the sizes made from the bytes it replaces.
    private void take(Look found) {
        if (found.same()) {
            return;
        }
        ownStamp = found.stamp();
        source = found.source();
        if (found.problem() != null && !found.problem().equals(said)) {
            pending = found.problem();
        }
        said = found.problem();
    }

    // The game's TrueType loader reads its file through a resource manager. This one hands it the
    // chosen bytes and holds nothing else.
    private record OneFile(byte[] bytes) implements ResourceManager {

        @Override
        public InputStream open(Identifier id) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public Optional<Resource> getResource(Identifier id) {
            return Optional.empty();
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of();
        }

        @Override
        public List<Resource> getResourceStack(Identifier id) {
            return List.of();
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Selector filter) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Selector filter) {
            return Map.of();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }
}
