package com.jellypudding.offlineclient.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.CountedPickList;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.Tally;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

// The settings of one block as text on the clipboard and back again. The text is the cut of the
// config that holds the chosen values under the name of the module or HUD piece. Keys and secrets
// stay out of it both ways. Text read back is treated as hostile.
final class SettingsCopy {

    private static final String SETTINGS = "settings";

    // No block comes near this many characters. More on the clipboard is something else.
    private static final int MOST_CHARS = 1 << 18;
    // No typed line comes near this. A longer one would stall the row that draws it.
    private static final int LONGEST_TEXT = 1024;

    private static final String NO_COPY = "The clipboard holds no copy of settings.";

    private SettingsCopy() {
    }

    // What a paste came to. The change is null when no setting moved and the line says why.
    record Pasted(Defaults.Change change, String line) {
    }

    // What a text held. The name is the module or HUD piece the copy says it was made of.
    record Copy(String from, JsonObject settings) {
    }

    // True for a setting a copy holds. It carries the values the player chose in the rows. Keys
    // and secrets stay behind with whatever the player made rather than chose such as a marker.
    private static boolean held(Setting<?> setting) {
        return setting.saves() && !setting.isInternal() && setting.hasDefault()
            && !(setting instanceof KeybindSetting) && !(setting instanceof TextSetting text && text.isSecret());
    }

    static List<Setting<?>> held(SettingBlock block) {
        List<Setting<?>> held = new ArrayList<>();
        for (Setting<?> setting : block.settings()) {
            if (held(setting)) {
                held.add(setting);
            }
        }
        return held;
    }

    // True for a block with anything to copy or paste. A copy is named after a module or a HUD
    // piece and the rows of anything else could never take one back.
    static boolean holds(SettingBlock block) {
        return (block.owner() instanceof Module || block.owner() instanceof HudElement)
            && block.settings().stream().anyMatch(SettingsCopy::held);
    }

    // Puts the block on the clipboard and hands back how many settings went.
    static int copy(SettingBlock block) {
        List<Setting<?>> held = held(block);
        OfflineClient.MC.keyboardHandler.setClipboard(text(block.name(), held));
        return held.size();
    }

    // The settings the way the config files them under the name of what they belong to.
    static String text(String name, List<Setting<?>> held) {
        JsonObject settings = new JsonObject();
        for (Setting<?> setting : held) {
            settings.add(setting.getName(), setting.toJson());
        }
        JsonObject owner = new JsonObject();
        owner.add(SETTINGS, settings);
        JsonObject root = new JsonObject();
        root.add(name, owner);
        return root.toString();
    }

    // Reads the clipboard into the block as one change. A value no setting of the block takes
    // is left out and counted.
    static Pasted paste(SettingBlock block) {
        Copy copy = parse(OfflineClient.MC.keyboardHandler.getClipboard());
        String from = copy == null ? null : spelled(copy.from());
        if (from == null) {
            return new Pasted(null, NO_COPY);
        }
        String name = block.name();
        if (!fits(block, from)) {
            return new Pasted(null, "That is a copy of " + from + " and does not fit " + name + ".");
        }
        JsonObject settings = copy.settings();
        List<Setting<?>> scope = scope(block, from, settings);
        int[] taken = new int[1];
        Defaults.Change change = Defaults.reset(() -> name, scope,
            () -> taken[0] = apply(settings, scope, setting -> nameIn(from, block, setting)));
        int left = settings.size() - taken[0];
        String misfits = left == 0 ? "" : " " + left + " did not fit.";
        if (change == null) {
            return new Pasted(null, taken[0] == 0 ? "Nothing in that copy fits " + name + "."
                : name + " already has those settings." + misfits);
        }
        return new Pasted(change, "Pasted " + Tally.counted(change.size(), "setting") + " into " + name + "."
            + misfits);
    }

    // The block a text holds under the name it gives. Null for text that is no copy.
    static Copy parse(String text) {
        if (text == null || text.isBlank() || text.length() > MOST_CHARS) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (root.size() != 1) {
                return null;
            }
            Map.Entry<String, JsonElement> owner = root.entrySet().iterator().next();
            JsonObject settings = owner.getValue().getAsJsonObject().getAsJsonObject(SETTINGS);
            return settings == null ? null : new Copy(owner.getKey(), settings);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // The settings of the block a paste reaches. A HUD piece pasted from another piece stays where
    // it is. The place of the other piece is taken out of the copy and is not counted.
    static List<Setting<?>> scope(SettingBlock block, String from, JsonObject settings) {
        List<Setting<?>> scope = held(block);
        if (!from.equals(block.name()) && block.owner() instanceof HudElement piece) {
            for (Setting<?> place : piece.placeSettings()) {
                scope.remove(place);
                settings.remove(nameIn(from, block, place));
            }
        }
        return scope;
    }

    // Hands each setting the value saved under its name and counts the ones that took theirs.
    // A value that is not sound never reaches a setting.
    static int apply(JsonObject settings, List<Setting<?>> scope, Function<Setting<?>, String> names) {
        int taken = 0;
        for (Setting<?> setting : scope) {
            JsonElement value = settings.get(names.apply(setting));
            if (value != null && sound(value) && take(setting, value)) {
                taken++;
            }
        }
        return taken;
    }

    // The name the way the client spells it. Null when it names no HUD piece and no module.
    private static String spelled(String name) {
        if (piece(name) != null) {
            return name;
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
        return module == null ? null : module.getName();
    }

    // Null when the overlay has no piece of that name.
    private static HudElement piece(String name) {
        HudModule hud = Modules.get(HudModule.class);
        if (hud != null) {
            for (HudElement element : hud.getManager().all()) {
                if (element.getName().equals(name)) {
                    return element;
                }
            }
        }
        return null;
    }

    // A copy fits the block it was made of and a HUD piece of the same kind such as another
    // custom text.
    private static boolean fits(SettingBlock block, String from) {
        if (from.equals(block.name())) {
            return true;
        }
        HudElement source = block.owner() instanceof HudElement ? piece(from) : null;
        return source != null && source.getClass() == block.owner().getClass();
    }

    // What the copy calls a setting. A HUD piece names its settings after itself and the copy
    // of another piece has that piece's name in the same place.
    private static String nameIn(String from, SettingBlock block, Setting<?> setting) {
        String name = setting.getName();
        return name.startsWith(block.name()) ? from + name.substring(block.name().length()) : name;
    }

    // A value the setting chokes on leaves it as it was. The same goes for one it read
    // something unsound out of such as a number written as the text NaN. A value the setting
    // passed over such as text for a switch is not taken.
    private static boolean take(Setting<?> setting, JsonElement value) {
        JsonElement before = setting.toJson();
        Set<?> offered = setting instanceof PickList<?> list ? new HashSet<>(list.options()) : Set.of();
        try {
            setting.fromJson(value);
            if (setting instanceof PickList<?> list) {
                keepOffered(list, offered);
            }
            JsonElement after = setting.toJson();
            if (sound(after)) {
                return !after.equals(before) || sameValue(after, value);
            }
        } catch (RuntimeException e) {
            // Put back below like a value that read unsound.
        }
        setting.fromJson(before);
        return false;
    }

    // True when a setting already holds the value a copy gives it. The setting's own value is read
    // back the way the copy was. A float and the text it was written as then compare alike.
    private static boolean sameValue(JsonElement own, JsonElement copied) {
        return JsonParser.parseString(own.toString()).equals(copied);
    }

    // A pasted list keeps what its picker offered before the paste. Nothing else could have been
    // picked by hand. An entry this game does not know goes as well and every count stays.
    private static <T> void keepOffered(PickList<T> list, Set<?> offered) {
        List<T> kept = new ArrayList<>(list.chosen());
        kept.removeIf(entry -> !offered.contains(entry));
        if (kept.size() == list.size()) {
            return;
        }
        Map<T, Integer> counts = new HashMap<>();
        if (list instanceof CountedPickList<T> counted) {
            kept.forEach(entry -> counts.put(entry, counted.count(entry)));
        }
        list.clear();
        list.addAll(kept);
        if (list instanceof CountedPickList<T> counted) {
            counts.forEach(counted::setCount);
        }
    }

    // True for a value that holds only what a player could have set by hand. Every number is
    // finite and every text is one a text box would have taken.
    private static boolean sound(JsonElement value) {
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (!sound(element)) {
                    return false;
                }
            }
            return true;
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (!typed(entry.getKey()) || !sound(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (!(value instanceof JsonPrimitive primitive)) {
            return true;
        }
        if (primitive.isNumber()) {
            return Double.isFinite(primitive.getAsDouble());
        }
        return !primitive.isString() || typed(primitive.getAsString());
    }

    private static boolean typed(String text) {
        return text.length() <= LONGEST_TEXT && text.chars().allMatch(c -> TextField.ANY.accepts((char) c));
    }
}
