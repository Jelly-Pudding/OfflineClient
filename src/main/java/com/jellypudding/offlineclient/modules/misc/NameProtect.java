package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.Optional;

/**
 * Swaps the local player's name for an alias in every chat line client
 * side. Nobody else sees the change.
 */
public final class NameProtect extends Module {

    private final TextSetting alias = new TextSetting("Alias",
        "The name shown instead of yours.", "Player");

    public NameProtect() {
        super("NameProtect", "Hides your own name in chat.", Category.MISC);
        addSettings(alias);
        searchTags("hide name", "stream");
    }

    /** Rewrites one incoming chat line. Styles and click actions survive. */
    public Component filter(Component message) {
        if (!isEnabled() || mc.getUser() == null) {
            return message;
        }
        String name = mc.getUser().getName();
        if (name.isEmpty() || !message.getString().contains(name)) {
            return message;
        }
        String replacement = alias.isBlank() ? "Player" : alias.getValue().trim();
        MutableComponent result = Component.empty();
        message.visit((style, text) -> {
            result.append(Component.literal(text.replace(name, replacement)).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }
}
