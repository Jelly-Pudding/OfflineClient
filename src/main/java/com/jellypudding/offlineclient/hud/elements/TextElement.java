package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

// Any text you like with a handful of words swapped in for live values.
public final class TextElement extends HudElement {

    // A Minecraft day in ticks and the ticks in one of its hours. Day starts at six.
    private static final long DAY_TICKS = 24000;
    private static final long HOUR_TICKS = 1000;
    private static final long SIX_AM = 6 * HOUR_TICKS;
    private static final long MINUTES_PER_HOUR = 60;

    // A text box has no enter key of its own. A typed backslash and n breaks the line.
    private static final String NEW_LINE = "\\n";
    private static final int LINE_GAP = 1;

    private final TextSetting text = new TextSetting("Text",
        "What it says. Type \\n to start a new line. You can use {fps} {tps} {ping} {x} {y} {z}"
            + " {dimension} {direction} {speed} {health} {server} {time} and {username}.",
        "Hello {username}");
    private final ColorSetting color = new ColorSetting("Text colour",
        "Colour of the text.", 190, false);
    private final BoolSetting shadow = new BoolSetting("Text shadow",
        "Draw a shadow behind it.", true);

    public TextElement() {
        super("Custom text", "Any text you type. Words in braces such as {fps} turn into live values.",
            false, 50, 4);
        add(text, color, shadow);
    }

    @Override
    public boolean visible() {
        return isActive() && !text.getValue().isBlank();
    }

    private List<String> lines() {
        String out = text.getValue();
        if (out.indexOf('{') >= 0) {
            for (String key : KEYS) {
                String token = "{" + key + "}";
                if (out.contains(token)) {
                    out = out.replace(token, valueOf(key));
                }
            }
        }
        return List.of(out.split(Pattern.quote(NEW_LINE)));
    }

    private static int widest(Font font, List<String> lines) {
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
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
            case "tps" -> ServerInfo.tps();
            case "ping" -> String.valueOf(ServerInfo.ping());
            case "x" -> String.valueOf(Math.round(pos.x));
            case "y" -> String.valueOf(Math.round(pos.y));
            case "z" -> String.valueOf(Math.round(pos.z));
            case "dimension" -> player.level().dimension().identifier().getPath();
            case "direction" -> player.getDirection().getName();
            case "speed" -> String.format(Locale.ROOT, "%.1f",
                player.getDeltaMovement().horizontalDistance() * SharedConstants.TICKS_PER_SECOND);
            case "health" -> String.valueOf(Math.round(EntityUtil.totalHealth(player)));
            case "server" -> Objects.requireNonNullElse(ServerInfo.address(), "singleplayer");
            case "time" -> dayTime(player);
            case "username" -> player.getGameProfile().name();
            default -> "";
        };
    }

    // The world clock as a twenty four hour reading. Nought ticks is six in the morning.
    private static String dayTime(LocalPlayer player) {
        long ticks = Math.floorMod(player.level().getOverworldClockTime() + SIX_AM, DAY_TICKS);
        return String.format(Locale.ROOT, "%02d:%02d", ticks / HOUR_TICKS,
            ticks % HOUR_TICKS * MINUTES_PER_HOUR / HOUR_TICKS);
    }

    // Each line lines up with the side of the screen the element is anchored to.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<String> lines = lines();
        int widest = widest(font, lines);
        int y = 0;
        for (String line : lines) {
            int x = (int) Math.round((widest - font.width(line)) * alignment());
            context.text(font, line, x, y, color.getColor(), shadow.isOn());
            y += font.lineHeight + LINE_GAP;
        }
    }

    @Override
    public int width(Font font) {
        return Math.max(1, widest(font, lines()));
    }

    @Override
    public int height(Font font) {
        // Text made only of breaks splits into nothing and still takes a line.
        int count = Math.max(1, lines().size());
        return count * font.lineHeight + (count - 1) * LINE_GAP;
    }
}
