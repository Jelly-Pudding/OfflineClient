package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.NameStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.ChatFormatting;
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

    // Every rename in force by real name and the same renames with the longest names first. A
    // name that starts with another one is then never half swapped.
    private record Renames(Map<String, Rename> byName, List<Rename> longestFirst) {
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
        0, 0, SLOTS, 1).max(SLOTS);
    private final Slot[] slots = new Slot[SLOTS];
    private final BoolSetting renameAll = new BoolSetting("Rename everyone",
        "Gives every other player on your screen a numbered name like Player1.", false);

    // The number handed to each player the blanket rename has met.
    private final Map<String, String> numbered = new HashMap<>();
    // Built on first use. Every name drawn in a frame asks for it. A tick drops it to take in the
    // settings and a tab list packet drops it to take in who is online.
    private Renames renames;

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
        renames = null;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        renames = null;
    }

    // ClientPacketListenerMixin calls this once the game has applied a tab list packet. Rename
    // everyone numbers whoever is on that list.
    public void onTabListChange() {
        renames = null;
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
        Rename rename = renames().byName().get(name);
        return rename == null ? name : rename.to();
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

    private static boolean isOwn(String name) {
        return NameStore.ownName().equals(name);
    }

    private String ownAlias() {
        return alias.isBlank() ? DEFAULT_ALIAS : alias.getValue().trim();
    }

    private Renames renames() {
        if (renames == null) {
            renames = build();
        }
        return renames;
    }

    // The first rename of a name wins. Yours comes first and then the rows in order.
    private Renames build() {
        Map<String, Rename> byName = new HashMap<>();
        if (renameSelf.isOn()) {
            String own = NameStore.ownName();
            byName.put(own, new Rename(own, ownAlias(), colored.isOn() ? color.getColor() : null));
        }
        for (Slot slot : slots) {
            Rename rename = slot.rename();
            if (rename != null && !isOwn(rename.from())) {
                byName.putIfAbsent(rename.from(), rename);
            }
        }
        if (renameAll.isOn() && mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!isOwn(name) && !byName.containsKey(name)) {
                    byName.put(name, new Rename(name, numberFor(name), null));
                }
            }
        }
        List<Rename> longestFirst = new ArrayList<>(byName.values());
        longestFirst.sort(Comparator.comparingInt((Rename rename) -> rename.from().length()).reversed());
        return new Renames(byName, longestFirst);
    }

    // Styles and click actions survive. A painted rename takes its colour and nothing else.
    public Component filter(Component message) {
        if (!isEnabled()) {
            return message;
        }
        List<Rename> renames = renames().longestFirst();
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

    // Copies the text across with every whole word rename swapped in. A colour code acts only
    // inside the piece of text that holds it. Each piece cut off starts in the style the codes
    // before it left in force.
    private static void append(MutableComponent result, String text, Style style,
                               List<Rename> renames) {
        int from = 0;
        Style inForce = style;
        while (true) {
            Rename hit = null;
            int at = -1;
            for (Rename rename : renames) {
                int found = ChatUtil.wholeWordIndex(text, rename.from(), from);
                if (found != -1 && (at == -1 || found < at)) {
                    at = found;
                    hit = rename;
                }
            }
            if (hit == null) {
                break;
            }
            inForce = appendPiece(result, text, from, at, inForce, style);
            Style named = hit.colour() == null ? inForce
                : inForce.withColor(TextColor.fromRgb(hit.colour() & 0xFFFFFF));
            result.append(Component.literal(hit.to()).setStyle(named));
            from = at + hit.from().length();
        }
        appendPiece(result, text, from, text.length(), inForce, style);
    }

    // Copies the text from one index to the other across and hands back the style its codes
    // leave in force. A reset code goes back to the style of the whole part as it does uncut.
    private static Style appendPiece(MutableComponent result, String text, int from, int to,
                                     Style startStyle, Style own) {
        Style base = startStyle;
        Style current = startStyle;
        int start = from;
        for (int i = from; i + 1 < to; i++) {
            if (text.charAt(i) != ChatFormatting.PREFIX_CODE) {
                continue;
            }
            ChatFormatting code = ChatFormatting.getByCode(text.charAt(++i));
            if (code == ChatFormatting.RESET) {
                if (!base.equals(own)) {
                    appendLiteral(result, text.substring(start, i - 1), base);
                    start = i - 1;
                    base = own;
                }
                current = own;
            } else if (code != null) {
                current = current.applyLegacyFormat(code);
            }
        }
        appendLiteral(result, text.substring(start, to), base);
        return current;
    }

    private static void appendLiteral(MutableComponent result, String text, Style style) {
        if (!text.isEmpty()) {
            result.append(Component.literal(text).setStyle(style));
        }
    }
}
