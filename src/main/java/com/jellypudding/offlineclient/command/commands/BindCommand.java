package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class BindCommand extends Command {

    public BindCommand() {
        super("bind", "Binds a module to a key. Use none to unbind.",
            "bind <module> <key|none>", "b");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 2) {
            usage();
            return;
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(args[0]);
        if (module == null) {
            ChatUtil.error("Unknown module: " + args[0]);
            return;
        }

        int key = KeybindSetting.keyFromName(args[1]);
        if (key == KeybindSetting.UNKNOWN) {
            ChatUtil.error("Can't parse key '" + args[1] + "'. Use the ClickGUI for special keys.");
            return;
        }
        module.getKeybind().setValue(key);
        if (key == KeybindSetting.UNBOUND) {
            ChatUtil.message("§b" + module.getName() + " §7unbound.");
        } else {
            ChatUtil.message("§b" + module.getName() + " §7bound to §b"
                + module.getKeybind().getKeyName() + "§7.");
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }
}
