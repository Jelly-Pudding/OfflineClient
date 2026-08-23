package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;

// The swap is client side. Nobody else sees it.
public final class NameProtect extends Module {

    private static final String DEFAULT_ALIAS = "Player";

    private final TextSetting alias = new TextSetting("Alias",
        "The name shown instead of yours.", DEFAULT_ALIAS);
    private final BoolSetting colored = new BoolSetting("Colour it",
        "Paints your name so you can pick it out at a glance.", false);
    private final ColorSetting color = new ColorSetting("Colour",
        "The colour your name is painted.", 140, false)
        .visibleWhen(colored::isOn);

    public NameProtect() {
        super("NameProtect", "Hides your own name in chat.", Category.MISC);
        addSettings(alias, colored, color);
        searchTags("hide name", "stream", "colour name");
    }

    // Styles and click actions survive.
    public Component filter(Component message) {
        if (!isEnabled() || mc.getUser() == null) {
            return message;
        }
        String name = mc.getUser().getName();
        if (name.isEmpty() || !message.getString().contains(name)) {
            return message;
        }
        String replacement = alias.isBlank() ? DEFAULT_ALIAS : alias.getValue().trim();
        MutableComponent result = Component.empty();
        message.visit((style, text) -> {
            append(result, text, name, replacement, style);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    // Splits on the name. Only the name itself takes the colour.
    private void append(MutableComponent result, String text, String name,
                        String replacement, Style style) {
        if (!colored.isOn()) {
            result.append(Component.literal(text.replace(name, replacement)).setStyle(style));
            return;
        }
        Style painted = style.withColor(TextColor.fromRgb(color.getColor() & 0xFFFFFF));
        int from = 0;
        while (true) {
            int at = text.indexOf(name, from);
            if (at == -1) {
                break;
            }
            if (at > from) {
                result.append(Component.literal(text.substring(from, at)).setStyle(style));
            }
            result.append(Component.literal(replacement).setStyle(painted));
            from = at + name.length();
        }
        if (from < text.length()) {
            result.append(Component.literal(text.substring(from)).setStyle(style));
        }
    }
}
