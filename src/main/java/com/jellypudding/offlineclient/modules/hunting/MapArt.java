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
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Notice;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.ServerWatch;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// The client keeps every map the server has sent as one colour byte per pixel. The game
// turns each byte into a colour through MapColor when it paints the map texture and the
// saved picture uses the same colours.
public final class MapArt extends Module {

    private static final int SIZE = 128;
    private static final int CHECK_TICKS = 20;
    private static final String FOLDER = "maps";
    private static final String PNG = ".png";
    private static final float PAINT_SHARE = 0.5f;

    private final BoolSetting frames = new BoolSetting("Mark frames",
        "Draws a box around every item frame holding a map.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.LINES, 200).under(frames);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Draws a line to every item frame holding a map.", false).under(frames);
    private final BoolSetting markSaved = new BoolSetting("Mark saved maps",
        "Gives a frame its own colour when the picture of its map was in the maps folder before"
            + " you came across it this time.", false).under(frames);
    private final ColorSetting savedColor = new ColorSetting("Saved colour",
        "Colour of a frame whose map you had saved already.", 0, 0, 0.6f, false).under(markSaved);
    private final BoolSetting save = new BoolSetting("Save images",
        "Saves each map the server sends you as a picture in the maps folder of offlineclient."
            + " Each map is saved once.", true);
    private final EnumSetting<Notice.Where> messages = Notice.row(
        "Where it posts a line with a link to the folder whenever new maps are saved.", Notice.Where.CHAT)
        .under(save);
    private final BoolSetting duplicates = new BoolSetting("Mark duplicates",
        "Paints every map that an open container and your inventory hold more than one of.", false);
    private final ColorSetting duplicateColor = new ColorSetting("Duplicate colour",
        "Colour painted behind a map held more than once.", 0, false).under(duplicates);

    private final Set<Integer> checked = new HashSet<>();
    private final Set<Integer> saved = new HashSet<>();
    private final Set<Integer> savedBefore = new HashSet<>();
    private Set<MapId> held = Set.of();
    private int heldId;
    private int heldAt = -1;
    private final ServerWatch server = new ServerWatch();
    private int wait;
    private int framed;

    public MapArt() {
        super("MapArt", "Marks item frames holding maps and saves every map you see as a picture.",
            Category.HUNTING);
        addSettings(frames);
        addSettings(style.settings());
        addSettings(tracers, markSaved, savedColor, save, messages, duplicates, duplicateColor);
        searchTags("map art", "maps", "item frame", "map saver", "picture", "duplicate maps", "collected");
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

    // Zero unless the menu holds more than one of the map. AbstractContainerScreenMixin paints the
    // slot with it.
    public int duplicateColor(AbstractContainerMenu menu, ItemStack stack) {
        MapId id = isEnabled() && duplicates.isOn() ? stack.get(DataComponents.MAP_ID) : null;
        if (id == null) {
            return 0;
        }
        if (menu.containerId != heldId || mc.player.tickCount != heldAt) {
            heldId = menu.containerId;
            heldAt = mc.player.tickCount;
            held = heldTwice(menu);
        }
        return held.contains(id) ? ColorUtil.fade(duplicateColor.getColor(), PAINT_SHARE) : 0;
    }

    private static Set<MapId> heldTwice(AbstractContainerMenu menu) {
        Map<MapId, Integer> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            MapId id = slot.getItem().get(DataComponents.MAP_ID);
            if (id != null) {
                counts.merge(id, slot.getItem().getCount(), Integer::sum);
            }
        }
        counts.values().removeIf(count -> count < 2);
        return counts.keySet();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!save.isOn() && !marksSaved()) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        wait = CHECK_TICKS;
        if (server.changed()) {
            // Two servers hand out the same map numbers.
            checked.clear();
            saved.clear();
            savedBefore.clear();
        }
        Path folder = DataFiles.path(FOLDER, DataFiles.keyName(ServerInfo.key()));
        int fresh = 0;
        for (Map.Entry<MapId, MapItemSavedData> entry : mc.level.getAllMapData().entrySet()) {
            int id = entry.getKey().id();
            byte[] colors = entry.getValue().colors;
            if (saved.contains(id) || blank(colors)) {
                continue;
            }
            Path file = folder.resolve(id + PNG);
            if (checked.add(id) && Files.exists(file)) {
                saved.add(id);
                savedBefore.add(id);
                continue;
            }
            if (!save.isOn()) {
                continue;
            }
            saved.add(id);
            byte[] copy = colors.clone();
            Util.ioPool().execute(() -> write(file, copy));
            fresh++;
        }
        if (fresh > 0) {
            Notice.post(messages, this, Component.literal("§7saved §f" + fresh + " §7new "
                    + (fresh == 1 ? "map" : "maps") + " to ")
                .append(ChatUtil.folderLink(folder))
                .append(Component.literal("§7.")));
        }
    }

    private boolean marksSaved() {
        return frames.isOn() && markSaved.isOn();
    }

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
            MapId id = frame.getFramedMapId(frame.getItem());
            boolean before = markSaved.isOn() && id != null && savedBefore.contains(id.id());
            int color = before ? savedColor.getColor() : style.lineColor();
            if (before) {
                style.draw(batch, box, color, true);
            } else {
                style.draw(batch, box, true);
            }
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), color, true);
            }
        }
        framed = found;
    }
}
