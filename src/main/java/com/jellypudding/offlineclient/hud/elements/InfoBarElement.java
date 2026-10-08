package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.modules.render.Freecam;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CompassPoint;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.LiveText;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// A line of readouts about where you are and how you move.
public final class InfoBarElement extends HudElement {

    // Stands in for the distance left whilst the editor is open and no goto walk is going.
    private static final double SAMPLE_GOTO = 120;

    private final BoolSetting coords = new BoolSetting("Info bar coordinates",
        "Writes the block you stand in.", true);
    private final BoolSetting otherDimension = new BoolSetting("Info bar other dimension",
        "Adds the matching spot in the other dimension.", true).under(coords);
    private final BoolSetting camera = new BoolSetting("Info bar camera",
        "Shows where the camera is whilst Freecam moves it.", true);
    private final BoolSetting direction = new BoolSetting("Info bar direction",
        "Writes the way you face and the axis it points along.", true);
    private final BoolSetting rotation = new BoolSetting("Info bar rotation",
        "Writes your yaw and pitch the way the debug screen does.", false);
    private final BoolSetting biome = new BoolSetting("Info bar biome",
        "Writes the biome you stand in.", false);
    private final BoolSetting speed = new BoolSetting("Info bar speed",
        "Writes how fast you go along the ground in blocks a second.", true);
    private final BoolSetting walk = new BoolSetting("Info bar goto",
        "Writes how far a goto walk or an autopilot flight has left whilst one is going.", true);
    private final BoolSetting fps = new BoolSetting("Info bar frames",
        "Writes how many frames a second the game draws.", true);
    private final BoolSetting clock = new BoolSetting("Info bar clock",
        "Writes the time on your computer.", false);
    private final BoolSetting hideInChat = new BoolSetting("Hide whilst typing",
        "Takes the bar away whilst the chat box is open.", true);
    // The line is measured and drawn several times a frame.
    private final RowCache<String> parts = new RowCache<>(() -> build(false));

    public InfoBarElement() {
        super("Info bar", "One line with where you are and the way you face and how fast you go.", false, 0, 100,
            0.5);
        add(coords, otherDimension, camera, direction, rotation, biome, speed, walk, fps, clock, hideInChat);
    }

    @Override
    public boolean visible() {
        if (!isActive() || OfflineClient.MC.player == null) {
            return false;
        }
        if (hideInChat.isOn() && OfflineClient.MC.gui.screen() instanceof ChatScreen) {
            return false;
        }
        return !parts().isEmpty();
    }

    // The camera and the goto parts only show whilst they have something to say. In the editor
    // they stand in for a bar that would otherwise be empty.
    private List<String> parts() {
        List<String> built = parts.get();
        return built.isEmpty() && editing() ? build(true) : built;
    }

    private List<String> build(boolean sample) {
        Player player = OfflineClient.MC.player;
        List<String> built = new ArrayList<>();
        if (player == null) {
            return built;
        }
        BlockPos pos = player.blockPosition();
        if (coords.isOn()) {
            built.add(figures("XYZ", BlockUtil.text(pos)));
            String dimension = ServerInfo.dimension();
            BlockPos there = otherDimension.isOn() ? WaypointStore.acrossPortal(player.position(), dimension) : null;
            if (there != null) {
                built.add(figures(ServerInfo.dimensionName(WaypointStore.otherSide(dimension)),
                    there.getX() + " " + there.getZ()));
            }
        }
        if (camera.isOn() && (sample || Modules.enabled(Freecam.class))) {
            BlockPos seen = sample ? pos : OfflineClient.MC.gameRenderer.mainCamera().blockPosition();
            built.add(figures("Camera", BlockUtil.text(seen)));
        }
        if (direction.isOn()) {
            built.add(facing(player.getDirection()));
        }
        if (rotation.isOn()) {
            built.add(figures("Yaw", LiveText.value("yaw")) + " " + figures("Pitch", LiveText.value("pitch")));
        }
        if (biome.isOn()) {
            built.add(LiveText.value("biome"));
        }
        if (speed.isOn()) {
            built.add(String.format(Locale.ROOT, "%.1f m/s", EntityUtil.ownSpeed()));
        }
        if (walk.isOn() && (sample || Travel.goal() != null)) {
            built.add(figures("Goto", String.format(Locale.ROOT, "%.0fm",
                sample ? SAMPLE_GOTO : Travel.distanceLeft())));
        }
        if (walk.isOn()) {
            Modules.autopilotLeft().ifPresent(left -> built.add(figures("Autopilot", left)));
        }
        if (fps.isOn()) {
            built.add(OfflineClient.MC.getFps() + " fps");
        }
        if (clock.isOn()) {
            built.add(LiveText.value("clock"));
        }
        return built;
    }

    // A grey label and white figures after it.
    private static String figures(String label, String values) {
        return label + " §f" + values + "§7";
    }

    // The compass point and which way it runs along its axis.
    private static String facing(Direction way) {
        CompassPoint point = CompassPoint.nearest(way.toYRot());
        return ChatUtil.capitalised(point.words()) + " " + point.axis();
    }

    private String line() {
        return String.join("§8 | §7", parts());
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        write(context, font, line(), 0, 0, RenderUtil.MUTED_TEXT);
    }

    @Override
    public int width(Font font) {
        return Math.max(1, font.width(line()));
    }

    @Override
    public int height(Font font) {
        return font.lineHeight;
    }
}
