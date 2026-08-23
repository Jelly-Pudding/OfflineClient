package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.Arrays;
import java.util.Locale;

public final class SetCommand extends Command {

    public SetCommand() {
        super("set", "Lists or changes a module's settings.", "set <module> [setting] [value]", "settings");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1) {
            ChatUtil.error("Usage: " + getUsage());
            return;
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(args[0]);
        if (module == null) {
            ChatUtil.error("Unknown module: §f" + args[0]);
            return;
        }

        if (args.length == 1) {
            listSettings(module);
            return;
        }

        Setting<?> setting = module.getSetting(args[1]);
        if (setting == null) {
            // The setting name may be left out when the module has only one.
            if (args.length == 2 && module.getSettings().size() == 1) {
                apply(module, module.getSettings().get(0), args[1]);
                return;
            }
            ChatUtil.error("§f" + module.getName() + "§c has no setting called §f" + args[1]
                + "§c. " + optionsHint(module));
            return;
        }

        if (args.length == 2) {
            describe(setting);
            return;
        }

        // Text settings take the rest of the line. Everything else takes one word.
        apply(module, setting, String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
    }

    private static void listSettings(Module module) {
        if (module.getSettings().isEmpty()) {
            ChatUtil.message("§b" + module.getName() + "§7 has no settings.");
            return;
        }
        ChatUtil.message("§3" + module.getName() + " settings:");
        for (Setting<?> setting : module.getSettings()) {
            ChatUtil.message("§b" + CommandManager.settingId(setting) + " §7= §f"
                + valueString(setting) + rangeHint(setting));
        }
    }

    private static void describe(Setting<?> setting) {
        ChatUtil.message("§b" + CommandManager.settingId(setting) + " §7= §f" + valueString(setting)
            + rangeHint(setting) + " §8(" + setting.getDescription() + ")");
        if (setting instanceof EnumSetting<?> e) {
            ChatUtil.message("§7Options: §f" + enumOptions(e));
        }
    }

    private static String optionsHint(Module module) {
        if (module.getSettings().isEmpty()) {
            return "It has no settings.";
        }
        StringBuilder names = new StringBuilder();
        for (Setting<?> setting : module.getSettings()) {
            if (!names.isEmpty()) {
                names.append("§c/§f");
            }
            names.append(CommandManager.settingId(setting));
        }
        return "Its settings are §f" + names + "§c.";
    }

    private static void apply(Module module, Setting<?> setting, String value) {
        switch (setting) {
            case BoolSetting b -> {
                if (!value.matches("(?i)true|false|on|off")) {
                    ChatUtil.error("§f" + CommandManager.settingId(setting)
                        + "§c needs §ftrue §cor §ffalse§c.");
                    return;
                }
                b.setValue(value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on"));
            }
            case NumberSetting n -> {
                double parsed;
                try {
                    parsed = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    ChatUtil.error("§f" + value + "§c is not a number.");
                    return;
                }
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
                    float hue;
                    try {
                        hue = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        ChatUtil.error("§f" + value + "§c is not a hue. Use a number from 0 to 360 or §frainbow§c.");
                        return;
                    }
                    c.setRainbow(false);
                    c.setHue(hue);
                }
            }
            case EnumSetting<?> e -> {
                if (!trySetEnum(e, value)) {
                    ChatUtil.error("§f" + value + "§c is not an option here. Options: §f" + enumOptions(e));
                    return;
                }
            }
            case TextSetting t -> t.setValue(value.trim());
            case KeybindSetting k -> {
                int key = KeybindSetting.keyFromName(value);
                if (key == KeybindSetting.UNKNOWN) {
                    ChatUtil.error("§f" + value + "§c is not a key. Try a letter or §fnone§c.");
                    return;
                }
                k.setValue(key);
            }
            default -> {
                ChatUtil.error("This setting can only be changed in the ClickGUI.");
                return;
            }
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
        ChatUtil.message("§b" + module.getName() + " " + CommandManager.settingId(setting)
            + " §7set to §f" + valueString(setting));
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
        StringBuilder options = new StringBuilder();
        for (Object constant : setting.getValue().getDeclaringClass().getEnumConstants()) {
            if (!options.isEmpty()) {
                options.append(" §7/ §f");
            }
            options.append(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
        }
        return options.toString();
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
        return setting.getValueString();
    }
}
