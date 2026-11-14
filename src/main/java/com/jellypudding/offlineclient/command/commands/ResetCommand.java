package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.jellypudding.offlineclient.util.Tally;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// Every reset goes through Defaults and its reply carries a link that takes it back. The
// words below are read before module names.
public final class ResetCommand extends Command {

    private static final String UNDO = "undo";
    private static final String ALL = "all";
    private static final String BINDS = "binds";
    private static final String BIND = "bind";
    private static final List<String> WORDS = List.of(UNDO, ALL, BINDS);

    private final ActionSetting everyModule = new ActionSetting("Reset every module",
        "Puts every module setting back to its default. Binds and on or off states stay.",
        ResetCommand::resetModules).confirm();
    private final ActionSetting everyBind = new ActionSetting("Reset every bind",
        "Puts every bind back to how it started.", ResetCommand::resetBinds).confirm();

    public ResetCommand() {
        super("reset", "Puts a module or a setting or a bind back to how it started.",
            "reset [module|all|binds|undo] [setting|bind]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            listChanged();
            return;
        }
        if (args.length > 2) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case UNDO -> undo(args);
            case ALL -> pressAlone(everyModule, args);
            case BINDS -> pressAlone(everyBind, args);
            default -> reset(args);
        }
    }

    private void pressAlone(ActionSetting action, String[] args) {
        if (args.length == 1) {
            press(action);
        } else {
            usage();
        }
    }

    // The binds are left out because they have their own reset.
    private static void listChanged() {
        List<String> names = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (Defaults.changed(settingsOf(module)) > 0) {
                names.add("§b" + module.getName());
            }
        }
        if (names.isEmpty()) {
            ChatUtil.message("§7Every module is at its defaults.");
            return;
        }
        ChatUtil.message("§3Modules with changes " + String.join("§7 ", names));
        ChatUtil.message("§7Type §f" + OfflineClient.INSTANCE.getCommandManager().getPrefix()
            + "reset <module>§7 to put one back.");
    }

    // A link carries the id of its own reset and can never take back a different one.
    private static void undo(String[] args) {
        if (args.length == 1) {
            ChatUtil.undone(Defaults.undoNewest());
            return;
        }
        OptionalInt id = wholeNumber(args[1]);
        if (id.isEmpty()) {
            return;
        }
        Defaults.Change change = Defaults.find(id.getAsInt());
        if (change == null) {
            ChatUtil.error("That reset can no longer be undone.");
            return;
        }
        ChatUtil.undone(Defaults.undo(change));
    }

    private void reset(String[] args) {
        Module module = module(args[0]);
        if (module == null) {
            return;
        }
        if (args.length == 1) {
            resetAll(module.getName(), module.allSettings());
            return;
        }
        Setting<?> setting = module.getSetting(args[1]);
        if (setting != null) {
            resetSetting(setting);
        } else if (args[1].equalsIgnoreCase(BIND)) {
            resetBind(module);
        } else {
            ChatUtil.error(module.getName() + " has no setting called " + args[1] + ".");
        }
    }

    private static void resetSetting(Setting<?> setting) {
        String name = setting.getName();
        if (setting instanceof ActionSetting) {
            ChatUtil.error(name + " runs something and has nothing to reset.");
            return;
        }
        if (!setting.hasDefault()) {
            ChatUtil.error(name + " has no default to go back to.");
            return;
        }
        Defaults.Change change = Defaults.reset(name, List.of(setting));
        if (change == null) {
            ChatUtil.message("§b" + name + " §7is already at its default.");
            return;
        }
        String start = setting.defaultText();
        ChatUtil.undoable(start == null ? "§b" + name + " §7is back to its default."
            : "§b" + name + " §7is back to §f" + start + "§7.", change);
    }

    private static void resetBind(Module module) {
        KeybindSetting bind = module.getKeybind();
        Defaults.Change change = Defaults.reset(module.getName() + " bind", List.of(bind));
        if (change == null) {
            ChatUtil.message("§b" + module.getName() + " §7bind is already §f" + bind.getValueString() + "§7.");
            return;
        }
        ChatUtil.undoable("§b" + module.getName() + " §7bind is back to §f" + bind.getValueString() + "§7.", change);
    }

    // Binds and on or off states stay. Everything put back is one change.
    private static void resetModules() {
        List<Setting<?>> scope = new ArrayList<>();
        int modules = 0;
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            List<Setting<?>> settings = settingsOf(module);
            if (Defaults.changed(settings) > 0) {
                modules++;
            }
            scope.addAll(settings);
        }
        Defaults.Change change = Defaults.reset("every module", scope);
        if (change == null) {
            ChatUtil.message("§7Every module is already at its defaults.");
            return;
        }
        ChatUtil.undoable("§7Put back §f" + Tally.counted(change.size(), "setting") + "§7 in §f"
            + Tally.counted(modules, "module") + "§7.", change);
    }

    // A key row inside a module is a bind whichever module holds it.
    private static List<Setting<?>> settingsOf(Module module) {
        return module.allSettings().stream().filter(setting -> !(setting instanceof KeybindSetting)).toList();
    }

    private static void resetBinds() {
        Defaults.Change change = Defaults.reset("every bind", KeyUse.keySettings());
        if (change == null) {
            ChatUtil.message("§7Every bind is already at its default.");
            return;
        }
        ChatUtil.undoable("§7Put back §f" + Tally.counted(change.size(), "bind") + "§7.", change);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            List<String> options = new ArrayList<>(WORDS);
            options.addAll(CommandManager.moduleIds());
            return CommandManager.filter(current, options);
        }
        if (index != 2 || WORDS.contains(tokens[1].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(tokens[1]);
        if (module == null) {
            return List.of();
        }
        List<String> resettable = new ArrayList<>();
        resettable.add(BIND);
        for (Setting<?> setting : module.playerSettings()) {
            if (!(setting instanceof ActionSetting) && setting.hasDefault()) {
                resettable.add(setting.id());
            }
        }
        return CommandManager.filter(current, resettable);
    }
}
