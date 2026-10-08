package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;

public final class ToggleCommand extends Command {

    private static final String ON = "on";
    private static final String OFF = "off";

    public ToggleCommand() {
        super("toggle", "Turns a module on or off.", "toggle <module> [on|off]", "t");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1 || args.length > 2) {
            usage();
            return;
        }
        Boolean wanted = args.length == 2 ? stateNamed(args[1]) : null;
        if (args.length == 2 && wanted == null) {
            usage();
            return;
        }
        Module module = module(args[0]);
        if (module != null) {
            switchTo(module, wanted == null ? !module.isEnabled() : wanted);
        }
    }

    // True for on and false for off. Null for any other word.
    public static Boolean stateNamed(String word) {
        if (word.equalsIgnoreCase(ON)) {
            return Boolean.TRUE;
        }
        if (word.equalsIgnoreCase(OFF)) {
            return Boolean.FALSE;
        }
        return null;
    }

    // A module only its bind opens cannot be switched.
    public static void switchTo(Module module, boolean on) {
        if (!module.isTogglable()) {
            ChatUtil.error(module.getName() + " cannot be toggled. The bind opens it.");
            return;
        }
        module.setEnabled(on);
        ChatUtil.toggled(module);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, CommandManager.moduleIds());
        }
        return index == 2 ? CommandManager.filter(current, List.of(ON, OFF)) : List.of();
    }
}
