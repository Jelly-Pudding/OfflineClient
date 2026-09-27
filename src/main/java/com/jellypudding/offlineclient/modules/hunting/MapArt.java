package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// The client keeps every map the server has sent as one colour byte per pixel. The game
// turns each byte into a colour through MapColor when it paints the map texture and the
// saved picture uses the same colours.
public final class MapArt extends Module {

    // Every map is this many pixels wide and high.
    private static final int SIZE = 128;
    // The maps the client holds are looked over once a second.
    private static final int CHECK_TICKS = 20;
    private static final String FOLDER = "maps";
    private static final String PNG = ".png";

    private final BoolSetting frames = new BoolSetting("Mark frames",
        "Draws a box around every item frame holding a map.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 200).under(frames);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line to every item frame holding a map.", false).under(frames);
    private final BoolSetting save = new BoolSetting("Save images",
        "Saves each map the server sends you as a picture in the maps folder of offlineclient."
            + " Each map is saved once.", true);
    private final BoolSetting chat = new BoolSetting("Chat",
        "Posts a line in chat with a link to the folder whenever new maps are saved.", true).under(save);

    // The maps of the current server already on disk or on their way there.
    private final Set<Integer> saved = new HashSet<>();
    private final ServerWatch server = new ServerWatch();
    private int wait;
    private int framed;

    public MapArt() {
        super("MapArt", "Marks item frames holding maps and saves every map you see as a picture.",
            Category.HUNTING);
        addSettings(frames);
        addSettings(style.settings());
        addSettings(tracers, save, chat);
        searchTags("map art", "maps", "item frame", "map saver", "picture");
    }

    @Override
    public String getSuffix() {
        return frames.isOn() ? count(framed) : null;
    }

    @Override
    protected void onEnable() {
        wait = 0;
    }

    @Override
    protected void onDisable() {
        framed = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || !save.isOn()) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        wait = CHECK_TICKS;
        if (server.changed()) {
            // Two servers hand out the same map numbers.
            saved.clear();
        }
        Path folder = DataFiles.path(FOLDER, DataFiles.safeName(ServerInfo.key()));
        int fresh = 0;
        for (Map.Entry<MapId, MapItemSavedData> entry : mc.level.getAllMapData().entrySet()) {
            int id = entry.getKey().id();
            byte[] colors = entry.getValue().colors;
            if (saved.contains(id) || blank(colors)) {
                continue;
            }
            saved.add(id);
            Path file = folder.resolve(id + PNG);
            if (Files.exists(file)) {
                continue;
            }
            byte[] copy = colors.clone();
            Util.ioPool().execute(() -> write(file, copy));
            fresh++;
        }
        if (fresh > 0 && chat.isOn()) {
            ChatUtil.component(Component.literal("§bMapArt §7saved §f" + fresh + " §7new "
                    + (fresh == 1 ? "map" : "maps") + " to ")
                .append(ChatUtil.folderLink(folder))
                .append(Component.literal("§7.")));
        }
    }

    // A map nobody has drawn on yet has nothing worth keeping.
    private static boolean blank(byte[] colors) {
        for (byte color : colors) {
            if (color != 0) {
                return false;
            }
        }
        return true;
    }

    // A crash mid write cannot leave a broken picture that would count as saved.
    private static void write(Path file, byte[] colors) {
        try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    image.setPixel(x, y, MapColor.getColorFromPackedId(colors[x + y * SIZE]));
                }
            }
            DataFiles.writeSafely(file, image::writeToFile);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || !frames.isOn()) {
            framed = 0;
            return;
        }
        DrawBatch batch = event.getBatch();
        int found = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ItemFrame frame) || !frame.hasFramedMap()) {
                continue;
            }
            found++;
            AABB box = EntityUtil.lerpedBox(frame, event.getPartialTicks());
            style.draw(batch, box, true);
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), style.lineColor(), true);
            }
        }
        framed = found;
    }
}
