package com.jellypudding.offlineclient.command.commands;

import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.render.FreeLook;
import com.jellypudding.offlineclient.modules.render.Zoom;
import com.jellypudding.offlineclient.util.AwayView;
import com.jellypudding.offlineclient.util.Background;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.SharedConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.packs.PackType;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public final class PanoramaCommand extends Command {

    private static final ExecutorService WORKER = Background.worker("OfflineClient panorama");

    private static final int FACES = 6;
    private static final String FACE = "panorama_%d.png";
    private static final String BACKGROUND = "assets/minecraft/textures/gui/title/background";
    // The game writes each face into a folder of this name inside the one it is handed.
    private static final String SHOTS = "screenshots";
    private static final String STAGING = "panorama";
    private static final String PACK = "Panorama";
    private static final String ICON = "pack.png";
    private static final int ICON_SIZE = 128;
    // A pack folder per run fills the disk in the end. Past this many the player clears some first.
    private static final int MOST_PACKS = 50;
    // The game saves the faces on its file thread after the frame. They are waited for this long.
    private static final long WAIT_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long POLL_MILLIS = 100;
    private static final byte[] PNG_END = {0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    private static volatile boolean busy;

    public PanoramaCommand() {
        super("panorama", "Saves a panorama of where you stand as a resource pack for the title screen.",
            "panorama");
    }

    @Override
    public void execute(String[] args) {
        Minecraft mc = OfflineClient.MC;
        if (mc.player == null || mc.level == null) {
            ChatUtil.error("A panorama can only be taken in a world.");
            return;
        }
        if (args.length > 0) {
            usage();
            return;
        }
        String turning = viewChanger();
        if (turning != null) {
            ChatUtil.error("A panorama is taken from your own eyes. Turn off " + turning + " first.");
            return;
        }
        if (busy) {
            ChatUtil.error("The last panorama is still being saved.");
            return;
        }
        Path pack = freshPack(mc.getResourcePackDirectory());
        if (pack == null) {
            ChatUtil.error("There are " + MOST_PACKS + " panorama packs already. Remove some from the resource pack"
                + " folder first.");
            return;
        }
        Path staging = DataFiles.path(STAGING);
        Path shots = staging.resolve(SHOTS);
        if (!prepare(shots)) {
            ChatUtil.error("The panorama could not be taken. The log says why.");
            return;
        }
        busy = true;
        String spot = BlockUtil.text(mc.player.blockPosition());
        // The chat that ran the command closes first and the shot shows only the world.
        mc.schedule(() -> take(staging, shots, pack, spot));
    }

    // A module that moves or turns or narrows the view would leave the six faces askew.
    private static String viewChanger() {
        if (AwayView.active() instanceof Module away) {
            return away.getName();
        }
        FreeLook freeLook = Modules.get(FreeLook.class);
        if (freeLook != null && freeLook.isActive()) {
            return freeLook.getName();
        }
        Zoom zoom = Modules.active(Zoom.class);
        return zoom == null ? null : zoom.getName();
    }

    private static Path freshPack(Path folder) {
        for (int number = 1; number <= MOST_PACKS; number++) {
            String name = number == 1 ? PACK : PACK + " " + number;
            if (!Files.exists(folder.resolve(name)) && !Files.exists(folder.resolve(name + ".zip"))) {
                return folder.resolve(name);
            }
        }
        return null;
    }

    // The game makes only the last folder of the path it saves the faces to. Faces left over
    // from a shot that never finished would be taken for the new ones.
    private static boolean prepare(Path shots) {
        try {
            Files.createDirectories(shots);
            for (int face = 0; face < FACES; face++) {
                Files.deleteIfExists(shots.resolve(FACE.formatted(face)));
            }
            return true;
        } catch (IOException e) {
            OfflineClient.LOG.error("Cannot prepare the panorama folder", e);
            return false;
        }
    }

    private static void take(Path staging, Path shots, Path pack, String spot) {
        boolean handedOver = false;
        try {
            handedOver = shoot(staging, shots, pack, spot);
        } finally {
            if (!handedOver) {
                busy = false;
            }
        }
    }

    private static boolean shoot(Path staging, Path shots, Path pack, String spot) {
        Minecraft mc = OfflineClient.MC;
        if (mc.player == null || mc.level == null) {
            return false;
        }
        // Behind your back the shot would show your own body.
        CameraType view = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        Component result;
        try {
            result = mc.grabPanoramixScreenshot(staging.toFile());
        } finally {
            mc.options.setCameraType(view);
        }
        if (result.getContents() instanceof TranslatableContents words && words.getKey().equals("screenshot.failure")) {
            ChatUtil.error("The panorama could not be taken. The log says why.");
            return false;
        }
        String where = ServerInfo.address() == null ? "single player" : ServerInfo.address();
        WORKER.execute(() -> {
            try {
                build(shots, pack, "Taken at " + spot + " on " + where);
                ClientLines.inChatLater(() -> ChatUtil.component(Component.literal("§7Saved the panorama as the pack ")
                    .append(ChatUtil.folderLink(pack))
                    .append("§7. Turn it on under Resource Packs to see it behind the title screen.")));
            } catch (IOException e) {
                OfflineClient.LOG.error("Cannot save the panorama pack", e);
                removeHalfBuilt(pack);
                ClientLines.inChatLater(() -> ChatUtil.error("The panorama could not be saved. The log says why."));
            } finally {
                busy = false;
            }
        });
        return true;
    }

    private static void build(Path shots, Path pack, String description) throws IOException {
        long giveUp = System.currentTimeMillis() + WAIT_MILLIS;
        for (int face = 0; face < FACES; face++) {
            Path file = shots.resolve(FACE.formatted(face));
            while (!written(file)) {
                if (System.currentTimeMillis() > giveUp) {
                    throw new IOException("The game never finished writing " + file.getFileName());
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Stopped waiting for the panorama", e);
                }
            }
        }
        Path faces = pack.resolve(BACKGROUND);
        Files.createDirectories(faces);
        for (int face = 0; face < FACES; face++) {
            Files.move(shots.resolve(FACE.formatted(face)), faces.resolve(FACE.formatted(face)));
        }
        writeIcon(faces.resolve(FACE.formatted(0)), pack.resolve(ICON));
        JsonObject about = new JsonObject();
        about.addProperty("description", description);
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        about.addProperty("min_format", format);
        about.addProperty("max_format", format);
        JsonObject meta = new JsonObject();
        meta.add("pack", about);
        if (!DataFiles.writeJson(pack.resolve("pack.mcmeta"), meta)) {
            throw new IOException("Cannot write the pack description");
        }
    }

    // The pack folder was made by this run and a pack without all its faces would show nothing.
    private static void removeHalfBuilt(Path pack) {
        if (!Files.exists(pack)) {
            return;
        }
        try (Stream<Path> files = Files.walk(pack)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            OfflineClient.LOG.warn("Cannot remove the unfinished pack {}", pack.getFileName(), e);
        }
    }

    // The game writes the end chunk last.
    private static boolean written(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) < PNG_END.length) {
            return false;
        }
        byte[] tail = new byte[PNG_END.length];
        try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
            reader.seek(reader.length() - tail.length);
            reader.readFully(tail);
        }
        return Arrays.equals(tail, PNG_END);
    }

    private static void writeIcon(Path front, Path icon) throws IOException {
        try (InputStream in = Files.newInputStream(front); NativeImage face = NativeImage.read(in);
             NativeImage small = new NativeImage(ICON_SIZE, ICON_SIZE, false)) {
            face.resizeSubRectTo(0, 0, face.getWidth(), face.getHeight(), small);
            small.writeToFile(icon);
        }
    }
}
