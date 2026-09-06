package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InfoBarElement extends HudElement {

    // Ticks a second. Speed reads better a second than a tick.
    private static final double TICKS = 20;

    private final BoolSetting coords = new BoolSetting("Coordinates",
        "Show where you are.", true);
    private final BoolSetting direction = new BoolSetting("Direction",
        "Show the way you face.", true);
    private final BoolSetting speed = new BoolSetting("Speed",
        "Show how fast you move.", true);
    private final BoolSetting fps = new BoolSetting("Frames",
        "Show the frames a second.", true);
    private final BoolSetting hideInChat = new BoolSetting("Hide whilst typing",
        "Takes the bar away whilst the chat box is open.", true);

    public InfoBarElement() {
        super("Info bar", "Coordinates and direction and speed and frames.", false, 0, 100);
        add(coords, direction, speed, fps, hideInChat);
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

    private List<String> parts() {
        Player player = OfflineClient.MC.player;
        List<String> parts = new ArrayList<>(4);
        if (player == null) {
            return parts;
        }
        if (coords.isOn()) {
            Vec3 pos = player.position();
            parts.add(String.format(Locale.ROOT, "XYZ \u00a7f%.0f %.0f %.0f\u00a77", pos.x, pos.y, pos.z));
        }
        if (direction.isOn()) {
            parts.add(player.getDirection().getName().toUpperCase(Locale.ROOT));
        }
        if (speed.isOn()) {
            Vec3 velocity = player.getDeltaMovement();
            parts.add(String.format(Locale.ROOT, "%.1f m/s",
                velocity.horizontalDistance() * TICKS));
        }
        if (fps.isOn()) {
            parts.add(OfflineClient.MC.getFps() + " fps");
        }
        return parts;
    }

    private String line() {
        return String.join("\u00a78 | \u00a77", parts());
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        context.text(font, line(), 0, 0, RenderUtil.MUTED_TEXT, true);
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
