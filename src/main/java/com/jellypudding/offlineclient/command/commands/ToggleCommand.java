package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.command.CommandManager;
import java.util.List;

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
        Module module = module(args[0]);
        if (module == null) {
            return;
        }
        if (!module.isTogglable()) {
            ChatUtil.error(module.getName() + " cannot be toggled. The bind opens it.");
            return;
        }
        module.toggle();
        ChatUtil.toggled(module);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, CommandManager.moduleIds()) : List.of();
    }
}
