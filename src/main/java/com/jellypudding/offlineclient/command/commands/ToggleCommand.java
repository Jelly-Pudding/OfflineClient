package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class ToggleCommand extends Command {

    public ToggleCommand() {
        super("toggle", "Turns a module on or off.", "toggle <module>", "t");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 1) {
            usage();
            return;
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(args[0]);
        if (module == null) {
            ChatUtil.error("Unknown module: " + args[0]);
            return;
        }
        if (!module.isTogglable()) {
            ChatUtil.error(module.getName() + " cannot be toggled. The bind opens it.");
            return;
        }
        module.toggle();
        ChatUtil.toggled(module);
    }
}
