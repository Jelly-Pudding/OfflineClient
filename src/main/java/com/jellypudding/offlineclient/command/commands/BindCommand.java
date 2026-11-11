package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;

import java.util.List;
import java.util.Locale;

public final class BindCommand extends Command {

    private static final String TOGGLE = "toggle";
    private static final String HOLD = "hold";
    private static final List<String> WAYS = List.of(TOGGLE, HOLD);

    // A few of what can be typed. Any key name and any combination works.
    private static final List<String> EXAMPLES = List.of("none", "mmb", "mouse4", "mouse5", "ctrl+", "alt+",
        "shift+", TOGGLE, HOLD);

    public BindCommand() {
        super("bind", "Binds a module to a key or a mouse button. Use none to unbind. Hold keeps the module on"
            + " only whilst the key is down.", "bind <module> <key|none> [toggle|hold]", "b");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 2 || args.length > 3) {
            usage();
            return;
        }
        Module module = module(args[0]);
        if (module == null) {
            return;
        }
        // A way on its own changes how the key acts and leaves the key where it is.
        boolean wayOnly = args.length == 2 && WAYS.contains(args[1].toLowerCase(Locale.ROOT));
        String way = wayOnly ? args[1] : args.length == 3 ? args[2] : null;
        Bind bind = wayOnly ? null : Bind.parse(args[1]);
        if (!wayOnly && bind == null) {
            ChatUtil.error(Bind.unknown(args[1]));
            return;
        }
        if (way != null && !setWay(module, way)) {
            return;
        }
        if (bind == null) {
            ChatUtil.message("§b" + module.getName() + (module.holdsKey()
                ? " §7stays on only whilst its key is held." : " §7is switched on and off by its key."));
        } else {
            setKey(module, bind);
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    // False after telling the player why the way cannot be set.
    private boolean setWay(Module module, String way) {
        boolean hold = way.equalsIgnoreCase(HOLD);
        if (!hold && !way.equalsIgnoreCase(TOGGLE)) {
            usage();
            return false;
        }
        if (!module.keySwitches()) {
            ChatUtil.error("The key of " + module.getName() + " does something of its own and cannot be a held one.");
            return false;
        }
        module.getKeybind().setHold(hold);
        return true;
    }

    private static void setKey(Module module, Bind bind) {
        KeybindSetting key = module.getKeybind();
        key.setValue(bind);
        if (!bind.isBound()) {
            ChatUtil.message("§b" + module.getName() + " §7unbound.");
            return;
        }
        ChatUtil.message("§b" + module.getName() + " §7bound to §b" + bind.name()
            + (module.holdsKey() ? "§7 and on only whilst it is held." : "§7."));
        KeyUse.warn(bind, key);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return switch (index) {
            case 1 -> CommandManager.filter(current, CommandManager.moduleIds());
            case 2 -> CommandManager.filter(current, EXAMPLES);
            case 3 -> CommandManager.filter(current, WAYS);
            default -> List.of();
        };
    }
}
