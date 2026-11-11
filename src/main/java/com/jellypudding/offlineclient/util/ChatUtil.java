package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.Defaults;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    private static int written;

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

    // Coordinates that walk there with the goto command when clicked.
    public static Component walkLink(BlockPos pos) {
        String spot = BlockUtil.text(pos);
        return commandLink("§f" + spot, "goto " + spot, "Click to walk there");
    }

    // Coordinates that walk there whilst the spot is in the dimension you are in. Elsewhere they
    // are plain text.
    public static Component walkLink(BlockPos pos, String dimension) {
        return dimension.equals(ServerInfo.dimension()) ? walkLink(pos) : Component.literal("§f" + BlockUtil.text(pos));
    }

    // Text that runs one of our own commands when clicked. The command is written without the prefix.
    public static Component commandLink(String shown, String command, String hover) {
        String typed = OfflineClient.INSTANCE.getCommandManager().offer(command);
        return Component.literal(shown).withStyle(style -> style
            .withClickEvent(new ClickEvent.RunCommand(typed))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }

    // Text that types one of our own commands into the chat box when clicked. The player finishes
    // it and sends it. The command is written without the prefix.
    public static MutableComponent typeLink(MutableComponent shown, String command) {
        String typed = OfflineClient.INSTANCE.getCommandManager().getPrefix() + command;
        return shown.withStyle(style -> style.withClickEvent(new ClickEvent.SuggestCommand(typed)));
    }

    // A line about a reset with a link after it that takes the reset back.
    public static void undoable(String line, Defaults.Change change) {
        component(Component.literal(line + " ").append(
            commandLink("§8[§7undo§8]", "reset undo " + change.id(), "Click to undo")));
    }

    // What an undo did. Null stands for nothing left to undo.
    public static void undone(Defaults.Undone undone) {
        if (undone == null) {
            message("§7There is no reset to undo.");
        } else if (undone.restored() == 0) {
            message("§7Nothing came back. Each one has changed since.");
        } else {
            String kept = undone.kept() == 0 ? ""
                : " §8Kept " + Tally.counted(undone.kept(), "newer change") + ".";
            message("§7Brought back what §b" + undone.name() + "§7 had." + kept);
        }
    }

    // Clicking the shown text copies the other text.
    public static MutableComponent copyOnClick(MutableComponent shown, String copied) {
        return shown.withStyle(style -> style.withClickEvent(new ClickEvent.CopyToClipboard(copied))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy"))));
    }

    // The start of a text up to that many characters. Each part keeps its own style. Reading stops
    // at the limit however long the text is.
    public static MutableComponent head(Component text, int characters) {
        MutableComponent head = Component.empty();
        int[] left = {characters};
        text.visit((style, words) -> {
            String part = words.length() > left[0] ? words.substring(0, left[0]) : words;
            head.append(Component.literal(part).setStyle(style));
            left[0] -= part.length();
            return left[0] > 0 ? Optional.empty() : FormattedText.STOP_ITERATION;
        }, Style.EMPTY);
        return head;
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

    // A number to at most that many places with no zeros at the end such as 2.5 or 3. A value that
    // is no number at all is written as it came.
    public static String decimal(double value, int places) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
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

    // The words with a capital first letter such as A zombie.
    public static String capitalised(String words) {
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
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

    public static String words(BlockEntityType<?> type) {
        return words(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
    }

    // A locked module never changed and has already said why.
    public static void toggled(Module module) {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        if (gui != null && !gui.showsToggleMessages(module) || Modules.lockHolds(module)) {
            return;
        }
        message(toggledText(module.getName(), module.isEnabled()));
    }

    // What the client says when a module of this name is switched on or off.
    public static String toggledText(String name, boolean on) {
        return from(name, "is now " + (on ? "§aenabled" : "§cdisabled") + "§7.");
    }

    // A line in the voice of a module. Its name comes first in aqua and the words follow in grey.
    public static String from(String who, String words) {
        return "§b" + who + " §7" + words;
    }

    public static void tell(String who, String words) {
        message(from(who, words));
    }

    // Where the name next sits as a word of its own or minus one. Sam inside
    // Samuel is not Sam. A colour code straight before the name counts as a gap.
    public static int wholeWordIndex(String text, String name, int from) {
        int at = text.indexOf(name, from);
        while (at != -1) {
            boolean startClear = at == 0 || !isNameChar(text.charAt(at - 1))
                || at >= 2 && text.charAt(at - 2) == ChatFormatting.PREFIX_CODE;
            int end = at + name.length();
            boolean endClear = end >= text.length() || !isNameChar(text.charAt(end));
            if (startClear && endClear) {
                return at;
            }
            at = text.indexOf(name, at + 1);
        }
        return -1;
    }

    // True for a letter or a digit or an underscore. Anything else ends a word.
    public static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // How many lines the client has written to the chat or its own panel. Reading it before and
    // after some work tells whether the work wrote a line.
    public static int written() {
        return written;
    }

    public static void component(Component component) {
        if (OfflineClient.MC.player == null) {
            return;
        }
        written++;
        Component line = tagged(component);
        // The Client lines piece of the HUD takes the client's own lines whilst it is on.
        if (!ClientLines.take(line)) {
            OfflineClient.MC.gui.hud.getChat().addClientSystemMessage(line);
        }
    }

    // A line the way the client writes it with its tag in front.
    public static Component tagged(Component component) {
        return Component.literal(PREFIX).append(component);
    }
}
