package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Renames are client side only. Nobody else on the server sees them.
// Chat and the tab list and the name tags and tracers all ask this for the display name.
public final class NameProtect extends Module {

    private static final String DEFAULT_ALIAS = "Player";

    // How many other players can be renamed one by one.
    private static final int SLOTS = 16;

    // One name swapped for another. A null colour keeps whatever style the text had.
    private record Rename(String from, String to, Integer colour) {
    }

    // The settings for one renamed player. The name row folds the rest away.
    private static final class Slot {

        final TextSetting player;
        final TextSetting shownAs;
        final BoolSetting paint;
        final ColorSetting colour;

        Slot(int number, NumberSetting count) {
            int slot = number - 1;
            player = new TextSetting("Player " + number, "Their real name. Click to type it.", "")
                .visibleWhen(() -> slot < count.getInt());
            shownAs = new TextSetting("Shown as " + number, "What their name becomes. Click to type it.",
                "")
                .under(player, () -> slot < count.getInt());
            paint = new BoolSetting("Paint " + number, "Gives the new name a colour of its own.",
                false)
                .under(player, () -> slot < count.getInt());
            colour = new ColorSetting("Colour " + number, "The colour that name is painted.",
                140, false)
                .under(paint, () -> slot < count.getInt() && paint.isOn());
        }

        // Null whilst either box is blank.
        Rename rename() {
            if (player.isBlank() || shownAs.isBlank()) {
                return null;
            }
            return new Rename(player.getValue().trim(), shownAs.getValue().trim(),
                paint.isOn() ? colour.getColor() : null);
        }
    }

    private final BoolSetting renameSelf = new BoolSetting("Rename yourself",
        "Swaps your own name for the alias.", true);
    private final TextSetting alias = new TextSetting("Alias",
        "The name shown instead of yours.", DEFAULT_ALIAS).under(renameSelf);
    private final BoolSetting colored = new BoolSetting("Colour it",
        "Paints your name to make it easy to pick out.", false).under(renameSelf);
    private final ColorSetting color = new ColorSetting("Colour",
        "The colour your name is painted.", 140, false)
        .under(colored);
    private final BoolSetting hideSkins = new BoolSetting("Hide skins",
        "Draws everyone with the default skin. Nobody is recognised on a stream.", false);
    private final NumberSetting renameCount = new NumberSetting("Renames",
        "How many other players get a name of your choosing. A row opens for each.",
        0, 0, SLOTS, 1).min(0).max(SLOTS);
    private final Slot[] slots = new Slot[SLOTS];
    private final BoolSetting renameAll = new BoolSetting("Rename everyone",
        "Renames every other player on your screen to Player1 and Player2 and so on.", false);

    // The number handed to each player the blanket rename has met.
    private final Map<String, String> numbered = new HashMap<>();

    public NameProtect() {
        super("NameProtect", "Hides your own name and renames other players on your screen.",
            Category.MISC);
        addSettings(renameSelf, alias, colored, color, hideSkins, renameCount);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Slot(i + 1, renameCount);
            addSettings(slots[i].player, slots[i].shownAs, slots[i].paint, slots[i].colour);
        }
        addSettings(renameAll);
        searchTags("hide name", "stream", "colour name", "rename", "alias");
    }

    @Override
    protected void onDisable() {
        numbered.clear();
    }

    // Read by AbstractClientPlayerMixin for every skin lookup.
    public boolean hidesSkins() {
        return hideSkins.isOn();
    }

    // What the name reads as on this screen. Unchanged whilst the module is off.
    public String display(String name) {
        if (!isEnabled()) {
            return name;
        }
        for (Rename rename : renames()) {
            if (rename.from().equals(name)) {
                return rename.to();
            }
        }
        return name;
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

    private boolean isOwn(String name) {
        return mc.getUser() != null && mc.getUser().getName().equals(name);
    }

    private String ownAlias() {
        return alias.isBlank() ? DEFAULT_ALIAS : alias.getValue().trim();
    }

    // Every rename to apply. Longer names go first. A name that starts with
    // another one is then never half swapped.
    private List<Rename> renames() {
        List<Rename> all = new ArrayList<>();
        if (renameSelf.isOn() && mc.getUser() != null) {
            all.add(new Rename(mc.getUser().getName(), ownAlias(),
                colored.isOn() ? color.getColor() : null));
        }
        for (Slot slot : slots) {
            Rename rename = slot.rename();
            if (rename != null && !isOwn(rename.from())) {
                all.add(rename);
            }
        }
        if (renameAll.isOn() && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!isOwn(name) && all.stream().noneMatch(rename -> rename.from().equals(name))) {
                    all.add(new Rename(name, numberFor(name), null));
                }
            }
        }
        all.sort(Comparator.comparingInt((Rename rename) -> rename.from().length()).reversed());
        return all;
    }

    // Styles and click actions survive. A painted rename takes its colour and nothing else.
    public Component filter(Component message) {
        if (!isEnabled()) {
            return message;
        }
        List<Rename> renames = renames();
        String text = message.getString();
        if (renames.stream().noneMatch(rename -> text.contains(rename.from()))) {
            return message;
        }
        MutableComponent result = Component.empty();
        message.visit((style, part) -> {
            append(result, part, style, renames);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    // Copies the text across with every whole word rename swapped in.
    private static void append(MutableComponent result, String text, Style style,
                               List<Rename> renames) {
        int from = 0;
        while (true) {
            Rename hit = null;
            int at = -1;
            for (Rename rename : renames) {
                int found = wholeWord(text, rename.from(), from);
                if (found != -1 && (at == -1 || found < at)) {
                    at = found;
                    hit = rename;
                }
            }
            if (hit == null) {
                break;
            }
            if (at > from) {
                result.append(Component.literal(text.substring(from, at)).setStyle(style));
            }
            Style painted = hit.colour() == null ? style
                : style.withColor(TextColor.fromRgb(hit.colour() & 0xFFFFFF));
            result.append(Component.literal(hit.to()).setStyle(painted));
            from = at + hit.from().length();
        }
        if (from < text.length()) {
            result.append(Component.literal(text.substring(from)).setStyle(style));
        }
    }

    // Where the name next appears on its own. Sam inside Samuel is not Sam.
    private static int wholeWord(String text, String name, int from) {
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
}
