package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AntiSpam extends Module {

    private static final Pattern COUNTER = Pattern.compile(" x(\\d{1,8})$");

    private final NumberSetting depth = new NumberSetting("Depth",
        "How many recent lines are checked.", 4, 1, 20, 1).max(50);

    public AntiSpam() {
        super("AntiSpam", "Stacks repeated chat lines into one.", Category.MISC);
        addSettings(depth);
        searchTags("chat", "duplicate");
    }

    // Returns the line to add.
    public Component fold(ChatComponent chat, Component message) {
        if (!isEnabled()) {
            return message;
        }
        String incoming = message.getString();
        if (incoming.isBlank()) {
            return message;
        }
        List<GuiMessage> all = chat.allMessages;
        int max = Math.min(depth.getInt(), all.size());
        for (int i = 0; i < max; i++) {
            String existing = all.get(i).content().getString();
            int count = 0;
            if (existing.equals(incoming)) {
                count = 2;
            } else {
                Matcher matcher = COUNTER.matcher(existing);
                if (matcher.find() && existing.substring(0, matcher.start()).equals(incoming)) {
                    count = Integer.parseInt(matcher.group(1)) + 1;
                }
            }
            if (count > 0) {
                all.remove(i);
                chat.refreshTrimmedMessages();
                return message.copy()
                    .append(Component.literal(" x" + count).withStyle(ChatFormatting.GRAY));
            }
        }
        return message;
    }
}
