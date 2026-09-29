package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public final class ChatUtil {

    private static final String TAG = "§b[§3Offline§b]";
    private static final String PREFIX = TAG + "§r ";

    private static final long LONGEST_IN_SECONDS = 90;
    private static final double SECONDS_PER_MINUTE = 60;
    // Under two days the time since reads in hours.
    private static final long LONGEST_IN_HOURS = TimeUnit.DAYS.toHours(2);

    // A noun starting with one of these takes an rather than a.
    private static final String VOWELS = "AEIOU";

    // About a screen of chat. Longer text is best shown folded behind a click that copies it.
    public static final int LONGEST_SHOWN = 1000;

    private ChatUtil() {
    }

    public static void message(String message) {
        component(Component.literal(message));
    }

    public static void error(String message) {
        message("§c" + message);
    }

    // A label and its value on one line such as Ping 40 ms.
    public static void row(String label, String value) {
        row(label, value, null);
    }

    // The same with a link after the value. A null link leaves it out.
    public static void row(String label, String value, Component link) {
        MutableComponent line = Component.literal("§7" + label + " §b" + value);
        component(link == null ? line : line.append(" ").append(link));
    }

    // A value that brings its own colours such as a server's MOTD.
    public static void row(String label, Component value) {
        component(Component.literal("§7" + label + " §r").append(value));
    }

    // Leaves the server. The disconnect screen shows the message under the client tag.
    public static void leaveServer(String message) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection != null) {
            connection.getConnection().disconnect(Component.literal(TAG + " §f" + message));
        }
    }

    // Takes the newest line the test accepts out of the last few in chat and hands back
    // its text. Null when none of them matched.
    public static String removeRecent(ChatComponent chat, int depth, Predicate<String> text) {
        List<GuiMessage> all = chat.allMessages;
        for (int i = 0; i < Math.min(depth, all.size()); i++) {
            String line = all.get(i).content().getString();
            if (text.test(line)) {
                all.remove(i);
                chat.refreshTrimmedMessages();
                return line;
            }
        }
        return null;
    }

    // Sends a line as the player. A line starting with a slash runs as a server
    // command the way the chat screen runs it. Chat would show it as text.
    public static void say(String text) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null || text.isBlank()) {
            return;
        }
        if (text.startsWith("/")) {
            connection.sendCommand(text.substring(1));
        } else {
            connection.sendChat(text);
        }
    }

    // Coordinates that walk there with the goto command when clicked.
    public static Component walkLink(BlockPos pos) {
        String spot = BlockUtil.text(pos);
        String command = OfflineClient.INSTANCE.getCommandManager().getPrefix() + "goto " + spot;
        return Component.literal(spot).withStyle(style -> style.withColor(ChatFormatting.WHITE)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to walk there"))));
    }

    // Clicking the shown text copies the other text.
    public static MutableComponent copyOnClick(MutableComponent shown, String copied) {
        return shown.withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(copied))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
    }

    // A saved file named from the game folder. Clicking it opens the folder that holds it.
    public static Component fileLink(Path file) {
        return pathLink(file, file.getParent());
    }

    // A folder named from the game folder. Clicking it opens that folder.
    public static Component folderLink(Path folder) {
        return pathLink(folder, folder);
    }

    private static Component pathLink(Path shown, Path opens) {
        Path game = OfflineClient.MC.gameDirectory.toPath();
        return Component.literal(game.relativize(shown).toString().replace('\\', '/'))
            .withStyle(style -> style.withColor(ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent.OpenFile(opens)));
    }

    // A span of time as a chat phrase such as 84 seconds or 12 minutes. Seconds stop
    // reading well past a minute and a half.
    public static String duration(double seconds) {
        long whole = Math.round(seconds);
        if (whole >= LONGEST_IN_SECONDS) {
            return Tally.counted((int) Math.round(whole / SECONDS_PER_MINUTE), "minute");
        }
        return Tally.counted((int) whole, "second");
    }

    // A span of time short enough for a HUD row such as 40 s or 12 min or 3 h.
    public static String shortAge(long millis) {
        long seconds = TimeUnit.MILLISECONDS.toSeconds(Math.max(0, millis));
        if (seconds < TimeUnit.MINUTES.toSeconds(1)) {
            return seconds + " s";
        }
        if (seconds < TimeUnit.HOURS.toSeconds(1)) {
            return TimeUnit.SECONDS.toMinutes(seconds) + " min";
        }
        if (seconds < TimeUnit.DAYS.toSeconds(1)) {
            return TimeUnit.SECONDS.toHours(seconds) + " h";
        }
        return TimeUnit.SECONDS.toDays(seconds) + " d";
    }

    // How long ago in the largest whole unit such as 5 minutes ago. Under a minute is just now.
    public static String ago(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(Math.max(0, millis));
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < TimeUnit.HOURS.toMinutes(1)) {
            return Tally.counted((int) minutes, "minute") + " ago";
        }
        long hours = TimeUnit.MINUTES.toHours(minutes);
        if (hours < LONGEST_IN_HOURS) {
            return Tally.counted((int) hours, "hour") + " ago";
        }
        return Tally.counted((int) TimeUnit.HOURS.toDays(hours), "day") + " ago";
    }

    // The noun with a or an before it such as an Enderman or a Zombie.
    public static String withArticle(String noun) {
        if (noun.isEmpty()) {
            return noun;
        }
        return (VOWELS.indexOf(Character.toUpperCase(noun.charAt(0))) >= 0 ? "an " : "a ") + noun;
    }

    // The path of a game id in words such as oak door. It reads the id alone and is safe on any thread.
    public static String words(Identifier id) {
        return id == null ? "unknown" : id.getPath().replace('_', ' ');
    }

    // A kind of mob or block or item in the same English words whatever language the game is in.
    public static String words(EntityType<?> type) {
        return words(BuiltInRegistries.ENTITY_TYPE.getKey(type));
    }

    public static String words(Block block) {
        return words(BuiltInRegistries.BLOCK.getKey(block));
    }

    public static String words(Item item) {
        return words(BuiltInRegistries.ITEM.getKey(item));
    }

    public static void toggled(Module module) {
        message("§b" + module.getName() + " §7is now "
            + (module.isEnabled() ? "§aenabled" : "§cdisabled") + "§7.");
    }

    // Where the name next sits as a word of its own or minus one. Sam inside
    // Samuel is not Sam.
    public static int wholeWordIndex(String text, String name, int from) {
        int at = text.indexOf(name, from);
        while (at != -1) {
            boolean startClear = at == 0 || !isNameChar(text.charAt(at - 1));
            int end = at + name.length();
            boolean endClear = end >= text.length() || !isNameChar(text.charAt(end));
            if (startClear && endClear) {
                return at;
            }
            at = text.indexOf(name, at + 1);
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // The newest line in chat or null when chat is empty. Reading it before and after some
    // work tells whether the work wrote a line.
    public static GuiMessage newestLine() {
        List<GuiMessage> all = OfflineClient.MC.gui.hud.getChat().allMessages;
        return all.isEmpty() ? null : all.getFirst();
    }

    public static void component(Component component) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        OfflineClient.MC.gui.hud.getChat()
            .addClientSystemMessage(Component.literal(PREFIX).append(component));
    }
}
