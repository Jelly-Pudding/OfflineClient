package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.LocalTime;
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
        "fps", "tps", "ping", "online", "server", "x", "y", "z", "otherx", "otherz", "dimension",
        "biome", "direction", "yaw", "pitch", "speed", "health", "hunger", "level", "totems",
        "durability", "gamemode", "lookingat", "username", "time", "clock"
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
                out = out.replace(token, value(key));
            }
        }
        return out;
    }

    // The filled text cut into lines at each typed backslash and n.
    public static List<String> lines(String text) {
        return List.of(TextMarkup.LINE_BREAK.split(fill(text)));
    }

    // The live value of one word without its braces such as biome. Empty for a word it does not know.
    public static String value(String key) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return "";
        }
        BlockPos block = player.blockPosition();
        return switch (key) {
            case "fps" -> String.valueOf(OfflineClient.MC.getFps());
            case "tps" -> ServerInfo.tps();
            case "ping" -> String.valueOf(ServerInfo.ping());
            case "online" -> String.valueOf(ServerInfo.online());
            case "server" -> Objects.requireNonNullElse(ServerInfo.address(), "singleplayer");
            case "x" -> String.valueOf(block.getX());
            case "y" -> String.valueOf(block.getY());
            case "z" -> String.valueOf(block.getZ());
            case "otherx" -> otherSide(player, false);
            case "otherz" -> otherSide(player, true);
            case "dimension" -> player.level().dimension().identifier().getPath();
            case "biome" -> biome(player, block);
            case "direction" -> player.getDirection().getName();
            case "yaw" -> oneDecimal(Mth.wrapDegrees(player.getYRot()));
            case "pitch" -> oneDecimal(Mth.wrapDegrees(player.getXRot()));
            case "speed" -> oneDecimal(EntityUtil.ownSpeed());
            case "health" -> String.valueOf(Math.round(EntityUtil.totalHealth(player)));
            case "hunger" -> String.valueOf(player.getFoodData().getFoodLevel());
            case "level" -> String.valueOf(player.experienceLevel);
            case "totems" -> String.valueOf(Totems.carried());
            case "durability" -> durability(player.getMainHandItem());
            case "gamemode" -> gameMode();
            case "lookingat" -> lookingAt();
            case "username" -> player.getGameProfile().name();
            case "time" -> dayTime(player);
            case "clock" -> computerTime();
            default -> "";
        };
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // One coordinate of the matching spot in the other dimension. Empty in the End.
    private static String otherSide(LocalPlayer player, boolean z) {
        BlockPos other = WaypointStore.acrossPortal(player.position(), ServerInfo.dimension());
        if (other == null) {
            return "";
        }
        return String.valueOf(z ? other.getZ() : other.getX());
    }

    // The biome at your feet by the name the game gives it.
    private static String biome(LocalPlayer player, BlockPos block) {
        return player.level().getBiome(block).unwrapKey()
            .map(key -> Component.translatableWithFallback(Util.makeDescriptionId("biome", key.identifier()),
                ChatUtil.words(key.identifier())).getString())
            .orElse("");
    }

    // The share of its durability the item has left. Empty for an item that never wears.
    private static String durability(ItemStack stack) {
        return stack.isDamageableItem() ? Math.round(ItemUtil.durabilityPercent(stack)) + "%" : "";
    }

    private static String gameMode() {
        MultiPlayerGameMode mode = OfflineClient.MC.gameMode;
        return mode == null ? "" : mode.getPlayerMode().getShortDisplayName().getString();
    }

    // The name of the block or entity under the crosshair. Empty when it points at nothing.
    private static String lookingAt() {
        Minecraft mc = OfflineClient.MC;
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            return EntityUtil.displayNameOf(entityHit.getEntity());
        }
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return mc.level.getBlockState(blockHit.getBlockPos()).getBlock().getName().getString();
        }
        return "";
    }

    // The world clock as a twenty four hour reading. Nought ticks is six in the morning.
    private static String dayTime(LocalPlayer player) {
        long ticks = Math.floorMod(player.level().getOverworldClockTime() + SIX_AM, DAY_TICKS);
        return clock(ticks / HOUR_TICKS, ticks % HOUR_TICKS * MINUTES_PER_HOUR / HOUR_TICKS);
    }

    // The time on the computer you play on.
    private static String computerTime() {
        LocalTime now = LocalTime.now();
        return clock(now.getHour(), now.getMinute());
    }

    private static String clock(long hours, long minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }
}
