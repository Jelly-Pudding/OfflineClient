package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatSender;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

// The commands a click runs for BetterChat.
final class ClickGuard {

    private static final int LONGEST_SHOWN_COMMAND = 120;

    private final BoolSetting showClicks = new BoolSetting("Show click commands",
        "The hover text of a clickable chat line says which command a click on it runs.", false);
    private final BoolSetting blockClicks = new BoolSetting("Block click commands",
        "A click in chat or in a book or in a window the server opens never runs a command that holds one of"
            + " your blocked words.", false);
    private final TextLines blockedWords = TextLines.named("Blocked words", "Blocked word",
        "How many words are blocked.",
        "A clicked command that holds this as a word of its own is not run. Click to type it.",
        "pay", "epay", "tpaccept", "etpaccept", "tpyes", "etpyes", "tpahere", "etpahere", "op", "trust",
        "accesstrust", "containertrust", "permissiontrust")
        .under(blockClicks);

    private final BooleanSupplier enabled;

    ClickGuard(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    Setting<?>[] settings() {
        List<Setting<?>> rows = new ArrayList<>(List.of(showClicks, blockClicks));
        rows.addAll(List.of(blockedWords.settings()));
        return rows.toArray(Setting<?>[]::new);
    }

    // ScreenMixin asks for a click in chat or in a book and ClientPacketListenerMixin for a button
    // in a window the server opens.
    boolean blocksClick(String command) {
        if (!enabled.getAsBoolean() || !blockClicks.isOn()) {
            return false;
        }
        for (String word : blockedWords.all()) {
            if (ChatSender.holds(command, word)) {
                ChatUtil.tell("BetterChat", "did not run §f" + shownCommand(command) + "§7. It holds §f"
                    + word + "§7 which you block.");
                return true;
            }
        }
        return false;
    }

    // A server may leave the slash off and a command of the client keeps its own prefix.
    private static String shownCommand(String command) {
        String typed = command.strip();
        if (!typed.startsWith(OfflineClient.INSTANCE.getCommandManager().getPrefix())) {
            typed = SentLine.asCommand(typed);
        }
        return StringUtil.truncateStringIfNecessary(StringUtil.filterText(typed), LONGEST_SHOWN_COMMAND, true);
    }

    Component withClickCommands(Component message) {
        if (!showClicks.isOn()) {
            return message;
        }
        boolean runs = message.visit((style, part) -> style.getClickEvent() instanceof ClickEvent.RunCommand
            ? Optional.of(part) : Optional.empty(), Style.EMPTY).isPresent();
        if (!runs) {
            return message;
        }
        MutableComponent rebuilt = Component.empty();
        message.visit((style, part) -> {
            Style shown = style.getClickEvent() instanceof ClickEvent.RunCommand click
                ? style.withHoverEvent(hoverWith(style.getHoverEvent(), click.command())) : style;
            rebuilt.append(Component.literal(part).setStyle(shown));
            return Optional.empty();
        }, Style.EMPTY);
        return rebuilt;
    }

    // A hover that showed an item or an entity makes way for the command. One that showed
    // text keeps it above.
    private static HoverEvent hoverWith(HoverEvent hover, String command) {
        MutableComponent text = Component.empty();
        if (hover instanceof HoverEvent.ShowText shown) {
            text.append(shown.value()).append("\n");
        }
        return new HoverEvent.ShowText(text.append(Component.literal("Click runs " + shownCommand(command))
            .withStyle(ChatFormatting.YELLOW)));
    }
}
