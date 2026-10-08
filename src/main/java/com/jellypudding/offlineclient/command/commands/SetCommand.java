package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.GridSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.RankSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.jellypudding.offlineclient.util.SearchRank;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Collection;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

public final class SetCommand extends Command {

    private static final String GRID_HINT = "§7Type one word for each row with §f#§7 for on and §f.§7 for off"
        + " such as §f.#. ### .#.§7. Three five seven or nine rows.";
    private static final String NUMBER_HINT = "§7Type a number or §fmore§7 or §fless§7.";
    private static final String LIST_HINT = "§7Type §fadd§7 or §fremove§7 and a word or type §fclear§7.";

    private static final String MORE = "more";
    private static final String LESS = "less";
    private static final String ADD = "add";
    private static final String REMOVE = "remove";
    private static final String CLEAR = "clear";

    // The namespace a registry id may be typed without.
    private static final String VANILLA = Identifier.DEFAULT_NAMESPACE + Identifier.NAMESPACE_SEPARATOR;

    public SetCommand() {
        super("set", "Lists or changes a module's settings.", "set <module> [setting] [value]", "settings");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            usage();
            return;
        }
        Module module = module(args[0]);
        if (module == null) {
            return;
        }

        if (args.length == 1) {
            listSettings(module);
            return;
        }

        Setting<?> setting = module.getSetting(args[1]);
        if (setting == null) {
            // The setting name may be left out when the module has only one.
            if (args.length == 2 && module.playerSettings().size() == 1) {
                apply(module, module.playerSettings().getFirst(), args[1]);
                return;
            }
            ChatUtil.error("§f" + module.getName() + "§c has no setting called §f" + args[1]
                + "§c. " + optionsHint(module));
            return;
        }

        if (args.length == 2) {
            if (setting instanceof ActionSetting action) {
                press(action);
            } else {
                describe(setting);
            }
            return;
        }

        if (setting instanceof PickList<?> list) {
            editList(module, list, args);
            return;
        }
        // Text settings and ranked lists and grids take the rest of the line. Everything else takes one word.
        apply(module, setting, words(args, 2));
    }

    private static void listSettings(Module module) {
        List<Setting<?>> settings = module.playerSettings();
        if (settings.isEmpty()) {
            ChatUtil.message("§b" + module.getName() + "§7 has no settings.");
            return;
        }
        ChatUtil.message("§3" + module.getName() + " settings");
        boolean changed = false;
        for (Setting<?> setting : settings) {
            // An action has no value. What it does is the useful part.
            ChatUtil.message(setting instanceof ActionSetting
                ? "§b" + setting.id() + " §8» §7" + setting.getDescription()
                : "§b" + setting.id() + " §7= §f" + valueString(setting) + rangeHint(setting)
                    + defaultHint(setting));
            changed |= setting.isChanged();
        }
        if (changed) {
            ChatUtil.message("§7Type §f" + OfflineClient.INSTANCE.getCommandManager().getPrefix() + "reset "
                + Setting.idFor(module.getName()) + "§7 to put the changed ones back.");
        }
    }

    private static void describe(Setting<?> setting) {
        ChatUtil.message("§b" + setting.id() + " §7= §f" + valueString(setting)
            + rangeHint(setting) + defaultHint(setting) + " §8(" + setting.getDescription() + ")");
        if (setting instanceof EnumSetting<?> e) {
            ChatUtil.message("§7Pick from §f" + enumOptions(e));
        }
        if (setting instanceof RankSetting<?> r) {
            ChatUtil.message("§7Name the ones you want in order from §f" + rankOptions(r));
        }
        if (setting instanceof GridSetting) {
            ChatUtil.message(GRID_HINT);
        }
        if (setting instanceof NumberSetting) {
            ChatUtil.message(NUMBER_HINT);
        }
        if (setting instanceof PickList<?> list) {
            showChosen(list);
            ChatUtil.message(LIST_HINT);
        }
    }

    // What a list holds. A long one folds into a count that copies the names when clicked.
    private static <T> void showChosen(PickList<T> list) {
        List<String> names = list.chosen().stream().map(list::displayName).toList();
        if (names.isEmpty()) {
            ChatUtil.message("§7It holds nothing.");
            return;
        }
        String joined = String.join(" §7/ §f", names);
        if (joined.length() <= ChatUtil.LONGEST_SHOWN) {
            ChatUtil.message("§7It holds §f" + joined);
            return;
        }
        ChatUtil.component(ChatUtil.copyOnClick(
            Component.literal("§7It holds §f" + names.size() + " entries"), String.join("\n", names)));
    }

    // A word adds or takes off every entry whose name or id holds it as the picker's add shown
    // does. A word that is an entry's id names that entry alone. Each edit can be taken back.
    private static <T> void editList(Module module, PickList<T> list, String[] args) {
        String verb = args[2].toLowerCase(Locale.ROOT);
        String name = module.getName() + " " + list.getName();
        if (verb.equals(CLEAR) && args.length == 3) {
            edited(Defaults.record(name, list.asSetting(), list::clear), "§7Cleared §b" + name + "§7.");
            return;
        }
        boolean adding = verb.equals(ADD);
        if ((!adding && !verb.equals(REMOVE)) || args.length < 4) {
            ChatUtil.message(LIST_HINT);
            return;
        }
        String word = words(args, 3);
        T exact = exact(list, word);
        if (exact != null && list.isChosen(exact) == adding) {
            ChatUtil.message("§f" + list.displayName(exact)
                + (adding ? "§7 is already on the list." : "§7 is not on the list."));
            return;
        }
        Collection<T> pool = adding ? list.options().stream().filter(entry -> !list.isChosen(entry)).toList()
            : list.chosen();
        List<T> matched = exact != null ? List.of(exact) : pool.stream()
            .filter(entry -> SearchRank.best(word, list.displayName(entry), list.idOf(entry)) != SearchRank.NO_MATCH)
            .toList();
        if (matched.isEmpty()) {
            ChatUtil.error("§f" + word + "§c matches nothing " + (adding ? "that could be added." : "on the list."));
            return;
        }
        String shown = matched.size() == 1 ? list.displayName(matched.getFirst())
            : matched.size() + " entries";
        Runnable edit = adding ? () -> list.addAll(matched) : () -> matched.forEach(list::remove);
        edited(Defaults.record(name, list.asSetting(), edit), (adding ? "§7Added §f" : "§7Took §f") + shown
            + (adding ? "§7 to §b" : "§7 off §b") + name + "§7.");
    }

    // The entry on offer whose id is the word. Null when there is none.
    private static <T> T exact(PickList<T> list, String word) {
        for (T entry : list.options()) {
            String id = list.idOf(entry);
            if (id.equalsIgnoreCase(word) || shortId(id).equalsIgnoreCase(word)) {
                return entry;
            }
        }
        return null;
    }

    private static String shortId(String id) {
        return id.startsWith(VANILLA) ? id.substring(VANILLA.length()) : id;
    }

    private static void edited(Defaults.Change change, String line) {
        if (change == null) {
            ChatUtil.message("§7Nothing changed.");
        } else {
            ChatUtil.undoable(line, change);
        }
    }

    private static String optionsHint(Module module) {
        if (module.playerSettings().isEmpty()) {
            return "It has no settings.";
        }
        return "Its settings are §f" + String.join("§c/§f", module.settingIds()) + "§c.";
    }

    private static void apply(Module module, Setting<?> setting, String value) {
        switch (setting) {
            case BoolSetting b -> {
                if (!value.matches("(?i)true|false|on|off")) {
                    ChatUtil.error("§f" + setting.id()
                        + "§c needs §ftrue §cor §ffalse§c.");
                    return;
                }
                b.setValue(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on"));
            }
            case NumberSetting n when value.equalsIgnoreCase(MORE) || value.equalsIgnoreCase(LESS) ->
                n.nudge(value.equalsIgnoreCase(MORE) ? 1 : -1);
            case NumberSetting n -> {
                OptionalDouble number = number(value);
                if (number.isEmpty()) {
                    return;
                }
                double parsed = number.getAsDouble();
                n.setValue(parsed);
                if (n.getValue() != parsed) {
                    ChatUtil.message("§7That is outside the allowed range. Using §f"
                        + n.getValueString() + "§7.");
                }
            }
            case ColorSetting c -> {
                if (value.equalsIgnoreCase("rainbow")) {
                    c.setRainbow(true);
                } else {
                    OptionalDouble hue = number(value);
                    if (hue.isEmpty()) {
                        return;
                    }
                    c.setRainbow(false);
                    c.setHue((float) hue.getAsDouble());
                }
            }
            case EnumSetting<?> e -> {
                if (!trySetEnum(e, value)) {
                    ChatUtil.error("§f" + value + "§c is not an option here. Pick one of §f" + enumOptions(e));
                    return;
                }
            }
            case TextSetting t -> t.setValue(value.trim());
            case RankSetting<?> r -> {
                if (!r.setFromWords(value)) {
                    ChatUtil.error("§f" + value + "§c names something not on the list. Pick from §f"
                        + rankOptions(r));
                    return;
                }
            }
            case GridSetting g -> {
                if (!g.setFromWords(value)) {
                    ChatUtil.error("§f" + value + "§c is not a square of rows.");
                    ChatUtil.message(GRID_HINT);
                    return;
                }
            }
            case KeybindSetting k -> {
                int key = KeybindSetting.keyFromName(value);
                if (key == KeybindSetting.UNKNOWN) {
                    ChatUtil.error("§f" + value + "§c is not a key. Try a letter or §fnone§c.");
                    return;
                }
                k.setValue(key);
            }
            case ActionSetting ignored -> {
                ChatUtil.error("§f" + setting.id() + "§c takes no value. Name it on its own to run it.");
                return;
            }
            default -> {
                ChatUtil.error("This setting can only be changed in the ClickGUI.");
                return;
            }
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        ChatUtil.message("§b" + module.getName() + " " + setting.id()
            + " §7set to §f" + valueString(setting));
        if (setting instanceof KeybindSetting bind) {
            KeyUse.warn(bind.getValue(), bind);
        }
    }

    private static <E extends Enum<E>> boolean trySetEnum(EnumSetting<E> setting, String value) {
        for (E constant : setting.getValue().getDeclaringClass().getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value) || constant.toString().equalsIgnoreCase(value)) {
                setting.setValue(constant);
                return true;
            }
        }
        return false;
    }

    private static String enumOptions(EnumSetting<?> setting) {
        return String.join(" §7/ §f", options(setting));
    }

    private static String rankOptions(RankSetting<?> setting) {
        return String.join(" §7/ §f", setting.ids());
    }

    private static String rangeHint(Setting<?> setting) {
        if (setting instanceof NumberSetting n) {
            String limit = Double.isInfinite(n.getHardMax())
                ? "anything from " + fmt(n.getHardMin()) + " up"
                : fmt(n.getHardMin()) + " to " + fmt(n.getHardMax());
            return " §8[slider " + fmt(n.getSliderMin()) + " to " + fmt(n.getSliderMax())
                + ". accepts " + limit + "]";
        }
        return "";
    }

    // A changed setting says what it started as where a few words can.
    private static String defaultHint(Setting<?> setting) {
        if (!setting.isChanged()) {
            return "";
        }
        String start = setting.defaultText();
        return start == null ? " §8(changed)" : " §8(default " + start + ")";
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String valueString(Setting<?> setting) {
        if (setting instanceof KeybindSetting k) {
            return k.getKeyName();
        }
        if (setting instanceof ColorSetting c) {
            return c.isRainbow() ? "rainbow" : "hue " + (int) c.getHue();
        }
        if (setting instanceof PickList<?> list) {
            return list.size() + " chosen";
        }
        return setting.getValueString();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, CommandManager.moduleIds());
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(tokens[1]);
        if (module == null) {
            return List.of();
        }
        if (index == 2) {
            return CommandManager.filter(current, module.settingIds());
        }
        Setting<?> setting = module.getSetting(tokens[2]);
        // A ranked list takes one word for each choice.
        if (setting instanceof RankSetting<?> r) {
            return CommandManager.filter(current, r.ids());
        }
        if (setting instanceof PickList<?> list) {
            return completeList(list, tokens, index, current);
        }
        if (index != 3) {
            return List.of();
        }
        return switch (setting) {
            case BoolSetting ignored -> CommandManager.filter(current, List.of("true", "false"));
            case NumberSetting ignored -> CommandManager.filter(current, List.of(MORE, LESS));
            case EnumSetting<?> choice -> CommandManager.filter(current, options(choice));
            case ColorSetting ignored -> CommandManager.filter(current, List.of("rainbow"));
            case null, default -> List.of();
        };
    }

    // The edits first and then the ids an add could take or a remove could drop.
    private static <T> List<String> completeList(PickList<T> list, String[] tokens, int index, String current) {
        if (index == 3) {
            return CommandManager.filter(current, List.of(ADD, REMOVE, CLEAR));
        }
        if (index != 4) {
            return List.of();
        }
        boolean adding = tokens[3].equalsIgnoreCase(ADD);
        if (!adding && !tokens[3].equalsIgnoreCase(REMOVE)) {
            return List.of();
        }
        Collection<T> pool = adding ? list.options() : list.chosen();
        List<String> ids = new ArrayList<>();
        for (T entry : pool) {
            if (list.isChosen(entry) != adding) {
                ids.add(shortId(list.idOf(entry)));
            }
        }
        return CommandManager.filter(current, ids);
    }

    private static List<String> options(EnumSetting<?> setting) {
        List<String> names = new ArrayList<>();
        for (Object constant : setting.getValue().getDeclaringClass().getEnumConstants()) {
            names.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
