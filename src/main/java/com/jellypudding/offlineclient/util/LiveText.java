package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

// Words in braces such as {fps} that turn into live values. The HUD custom text and
// AutoSign read the same words.
public final class LiveText {

    // A Minecraft day in ticks and the ticks in one of its hours. Day starts at six.
    private static final long DAY_TICKS = 24000;
    private static final long HOUR_TICKS = 1000;
    private static final long SIX_AM = 6 * HOUR_TICKS;
    private static final long MINUTES_PER_HOUR = 60;

    private static final String[] KEYS = {
        "fps", "tps", "ping", "x", "y", "z", "dimension", "direction",
        "speed", "health", "server", "time", "username"
    };

    // Every word on offer in its braces with an and before the last. For a description.
    public static final String WORDS = listed();

    private LiveText() {
    }

    private static String listed() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) {
                out.append(i == KEYS.length - 1 ? " and " : " ");
            }
            out.append('{').append(KEYS[i]).append('}');
        }
        return out.toString();
    }

    // The text with every word in braces swapped for its value right now.
    public static String fill(String text) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        String out = text;
        for (String key : KEYS) {
            String token = "{" + key + "}";
            if (out.contains(token)) {
                out = out.replace(token, valueOf(key));
            }
        }
        return out;
    }

    // The filled text cut into lines at each typed backslash and n.
    public static List<String> lines(String text) {
        return List.of(TextMarkup.LINE_BREAK.split(fill(text)));
    }

    private static String valueOf(String key) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return "";
        }
        BlockPos block = player.blockPosition();
        return switch (key) {
            case "fps" -> String.valueOf(OfflineClient.MC.getFps());
            case "tps" -> ServerInfo.tps();
            case "ping" -> String.valueOf(ServerInfo.ping());
            case "x" -> String.valueOf(block.getX());
            case "y" -> String.valueOf(block.getY());
            case "z" -> String.valueOf(block.getZ());
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
}
