package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

// Renames are client side only. Nobody else on the server sees them.
// Chat and the tab list and the name tags and tracers all ask this for the display name.
public final class NameProtect extends Module {

    private static final String DEFAULT_ALIAS = "Player";

    private final TextSetting alias = new TextSetting("Alias",
        "The name shown instead of yours.", DEFAULT_ALIAS);
    private final BoolSetting colored = new BoolSetting("Colour it",
        "Paints your name to make it easy to pick out.", false);
    private final ColorSetting color = new ColorSetting("Colour",
        "The colour your name is painted.", 140, false)
        .under(colored);
    private final TextSetting others = new TextSetting("Others",
        "Other players to rename. Pairs such as Notch=Steve separated by spaces.", "");
    private final BoolSetting renameAll = new BoolSetting("Rename everyone",
        "Renames every other player on your screen to Player1 and Player2 and so on.", false);

    // The pairs the text last parsed into. Rebuilt when the text changes.
    private String othersText;
    private Map<String, String> aliases = Map.of();

    // The number handed to each player the blanket rename has met.
    private final Map<String, String> numbered = new HashMap<>();

    public NameProtect() {
        super("NameProtect", "Hides your own name and renames other players on your screen.",
            Category.MISC);
        addSettings(alias, colored, color, others, renameAll);
        searchTags("hide name", "stream", "colour name", "rename", "alias");
    }

    @Override
    protected void onDisable() {
        numbered.clear();
    }

    // What the name reads as on this screen. Unchanged whilst the module is off.
    public String display(String name) {
        if (!isEnabled()) {
            return name;
        }
        if (isOwn(name)) {
            return ownAlias();
        }
        String set = aliases().get(name);
        if (set != null) {
            return set;
        }
        return renameAll.isOn() ? numberFor(name) : name;
    }

    // The same player keeps the same number for as long as the module is on.
    private String numberFor(String name) {
        String found = numbered.get(name);
        if (found == null) {
            found = DEFAULT_ALIAS + (numbered.size() + 1);
            numbered.put(name, found);
        }
        return found;
    }

    // Every rename to apply to a line of text. Longer names go first so a name
    // that starts with another one is not half swapped.
    private Map<String, String> renames() {
        Map<String, String> all = new LinkedHashMap<>(aliases());
        if (renameAll.isOn() && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!isOwn(name)) {
                    all.putIfAbsent(name, numberFor(name));
                }
            }
        }
        Map<String, String> sorted = new LinkedHashMap<>();
        all.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed())
            .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private boolean isOwn(String name) {
        return mc.getUser() != null && mc.getUser().getName().equals(name);
    }

    private String ownAlias() {
        return alias.isBlank() ? DEFAULT_ALIAS : alias.getValue().trim();
    }

    private Map<String, String> aliases() {
        String text = others.getValue();
        if (text.equals(othersText)) {
            return aliases;
        }
        othersText = text;
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : text.trim().split("\\s+")) {
            int at = pair.indexOf('=');
            if (at > 0 && at < pair.length() - 1) {
                parsed.put(pair.substring(0, at), pair.substring(at + 1));
            }
        }
        aliases = parsed;
        return aliases;
    }

    // Styles and click actions survive. Only the own name takes the colour.
    public Component filter(Component message) {
        if (!isEnabled()) {
            return message;
        }
        String text = message.getString();
        String own = mc.getUser() == null ? "" : mc.getUser().getName();
        boolean ownFound = !own.isEmpty() && text.contains(own);
        Map<String, String> renames = renames();
        boolean otherFound = false;
        for (String name : renames.keySet()) {
            otherFound |= text.contains(name);
        }
        if (!ownFound && !otherFound) {
            return message;
        }
        MutableComponent result = Component.empty();
        message.visit((style, part) -> {
            append(result, renameOthers(part, renames), own, style);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    private static String renameOthers(String text, Map<String, String> renames) {
        for (Map.Entry<String, String> pair : renames.entrySet()) {
            text = text.replace(pair.getKey(), pair.getValue());
        }
        return text;
    }

    // Splits on the own name so it alone can take the colour.
    private void append(MutableComponent result, String text, String own, Style style) {
        if (own.isEmpty() || !text.contains(own)) {
            result.append(Component.literal(text).setStyle(style));
            return;
        }
        String replacement = ownAlias();
        if (!colored.isOn()) {
            result.append(Component.literal(text.replace(own, replacement)).setStyle(style));
            return;
        }
        Style painted = style.withColor(TextColor.fromRgb(color.getColor() & 0xFFFFFF));
        int from = 0;
        while (true) {
            int at = text.indexOf(own, from);
            if (at == -1) {
                break;
            }
            if (at > from) {
                result.append(Component.literal(text.substring(from, at)).setStyle(style));
            }
            result.append(Component.literal(replacement).setStyle(painted));
            from = at + own.length();
        }
        if (from < text.length()) {
            result.append(Component.literal(text.substring(from)).setStyle(style));
        }
    }
}
