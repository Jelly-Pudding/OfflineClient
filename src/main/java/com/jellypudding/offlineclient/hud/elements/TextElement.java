package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

// A line of your own with a handful of words swapped in for live numbers.
public final class TextElement extends HudElement {

    private static final double TICKS = 20;
    // A Minecraft day in ticks and the ticks in one of its hours. Day starts at six.
    private static final long DAY_TICKS = 24000;
    private static final long HOUR_TICKS = 1000;
    private static final long SIX_AM = 6 * HOUR_TICKS;
    private static final long MINUTES_PER_HOUR = 60;

    private final TextSetting text = new TextSetting("Text line",
        "The line to write. Words in braces are swapped for live numbers.",
        "{x} {y} {z} in {dimension}");
    private final ColorSetting color = new ColorSetting("Text colour",
        "Colour of the line.", 190, false);
    private final BoolSetting shadow = new BoolSetting("Text shadow",
        "Draw a shadow behind it.", true);

    public TextElement() {
        super("Text", "A line of your own with live numbers swapped in.", false, 50, 4);
        add(text, color, shadow);
    }

    @Override
    public boolean visible() {
        return isActive() && !text.getValue().isBlank();
    }

    private String line() {
        String out = text.getValue();
        if (out.indexOf('{') < 0) {
            return out;
        }
        for (String key : KEYS) {
            String token = "{" + key + "}";
            if (out.contains(token)) {
                out = out.replace(token, valueOf(key));
            }
        }
        return out;
    }

    private static final String[] KEYS = {
        "fps", "tps", "ping", "x", "y", "z", "dimension", "direction",
        "speed", "health", "server", "time", "username"
    };

    private static String valueOf(String key) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return "";
        }
        Vec3 pos = player.position();
        return switch (key) {
            case "fps" -> String.valueOf(OfflineClient.MC.getFps());
            case "tps" -> String.format(Locale.ROOT, "%.1f", TickRate.INSTANCE.tps());
            case "ping" -> String.valueOf(ping(player));
            case "x" -> String.valueOf(Math.round(pos.x));
            case "y" -> String.valueOf(Math.round(pos.y));
            case "z" -> String.valueOf(Math.round(pos.z));
            case "dimension" -> player.level().dimension().identifier().getPath();
            case "direction" -> player.getDirection().getName();
            case "speed" -> String.format(Locale.ROOT, "%.1f",
                player.getDeltaMovement().horizontalDistance() * TICKS);
            case "health" -> String.valueOf(Math.round(EntityUtil.totalHealth(player)));
            case "server" -> serverAddress();
            case "time" -> dayTime(player);
            case "username" -> player.getGameProfile().name();
            default -> "";
        };
    }

    private static int ping(LocalPlayer player) {
        PlayerInfo info = player.connection.getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    private static String serverAddress() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        return data == null ? "singleplayer" : data.ip;
    }

    // The world clock as a twenty four hour reading. Nought ticks is six in the morning.
    private static String dayTime(LocalPlayer player) {
        long ticks = Math.floorMod(player.level().getOverworldClockTime() + SIX_AM, DAY_TICKS);
        return String.format(Locale.ROOT, "%02d:%02d", ticks / HOUR_TICKS,
            ticks % HOUR_TICKS * MINUTES_PER_HOUR / HOUR_TICKS);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        context.text(font, line(), 0, 0, color.getColor(), shadow.isOn());
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
