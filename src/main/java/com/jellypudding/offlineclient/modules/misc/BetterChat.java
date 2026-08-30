package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ChatSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

// ChatComponentMixin asks this about every line that comes in and about the history size.
public final class BetterChat extends Module {

    private static final int VANILLA_HISTORY = 100;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    // A number of three digits or more then one of two or more read as coordinates.
    private static final Pattern COORDINATES = Pattern.compile("-?\\d{3,}[\\s,;/]+-?\\d{2,}");

    private final BoolSetting timestamps = new BoolSetting("Timestamps",
        "Puts the time in front of every line.", true);
    private final BoolSetting longerHistory = new BoolSetting("Longer history",
        "Keeps more lines than the hundred vanilla scrolls back through.", true);
    private final NumberSetting historySize = new NumberSetting("History size",
        "Lines kept for scrolling back.", 1000, 100, 5000, 100, " lines")
        .under(longerHistory);
    private final BoolSetting keepHistory = new BoolSetting("Keep history",
        "Chat survives a disconnect and the debug clear key.", true);
    private final BoolSetting guardCoordinates = new BoolSetting("Guard coordinates",
        "Holds back a message that looks like coordinates until you send it a second time.",
        true);

    private String heldBack;

    public BetterChat() {
        super("BetterChat", "Small improvements to the chat box.", Category.MISC);
        addSettings(timestamps, longerHistory, historySize, keepHistory, guardCoordinates);
        searchTags("timestamp", "chat history", "coords");
    }

    public Component decorate(Component message) {
        if (!isEnabled() || !timestamps.isOn()) {
            return message;
        }
        MutableComponent stamp = Component.literal("[" + LocalTime.now().format(CLOCK) + "] ")
            .withStyle(ChatFormatting.DARK_GRAY);
        return stamp.append(message);
    }

    public int historyLimit(int vanilla) {
        if (!isEnabled() || !longerHistory.isOn()) {
            return vanilla;
        }
        return Math.max(VANILLA_HISTORY, historySize.getInt());
    }

    public boolean keepsHistory() {
        return isEnabled() && keepHistory.isOn();
    }

    @Subscribe
    private void onChatSend(ChatSendEvent event) {
        if (!guardCoordinates.isOn()) {
            return;
        }
        String message = event.getMessage();
        if (message.startsWith("/") || !COORDINATES.matcher(message).find()) {
            return;
        }
        // The same text sent twice goes through. That is the second look asked for.
        if (message.equals(heldBack)) {
            heldBack = null;
            return;
        }
        heldBack = message;
        event.cancel();
        ChatUtil.message("That looks like coordinates. Send it again if you mean it.");
    }

    @Override
    protected void onDisable() {
        heldBack = null;
    }
}
