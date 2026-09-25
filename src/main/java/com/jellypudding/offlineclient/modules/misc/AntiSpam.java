package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;

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

    // A repeat of a recent line takes that line out and comes back with a count on the end.
    public Component fold(ChatComponent chat, Component message) {
        if (!isEnabled()) {
            return message;
        }
        String incoming = BetterChat.withoutStamp(message.getString());
        if (incoming.isBlank()) {
            return message;
        }
        String removed = ChatUtil.removeRecent(chat, depth.getInt(), line -> countAfter(line, incoming) > 0);
        if (removed == null) {
            return message;
        }
        return message.copy()
            .append(Component.literal(" x" + countAfter(removed, incoming)).withStyle(ChatFormatting.GRAY));
    }

    // The count a stored line reaches once the incoming one joins it. Zero when they differ.
    private static int countAfter(String line, String incoming) {
        String existing = BetterChat.withoutStamp(line);
        if (existing.equals(incoming)) {
            return 2;
        }
        Matcher matcher = COUNTER.matcher(existing);
        if (matcher.find() && existing.substring(0, matcher.start()).equals(incoming)) {
            return Integer.parseInt(matcher.group(1)) + 1;
        }
        return 0;
    }
}
