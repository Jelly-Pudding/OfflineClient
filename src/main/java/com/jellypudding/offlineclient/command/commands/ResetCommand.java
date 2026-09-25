package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;

import java.util.List;

public final class ResetCommand extends Command {

    public ResetCommand() {
        super("reset", "Puts a module or one of its settings back to how it started.",
            "reset <module> [setting]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length < 1 || args.length > 2) {
            usage();
            return;
        }
        Module module = module(args[0]);
        if (module == null) {
            return;
        }
        if (args.length == 1) {
            for (Setting<?> setting : module.getSettings()) {
                setting.reset();
            }
            ChatUtil.message("§b" + module.getName() + " §7is back to its defaults.");
        } else {
            Setting<?> setting = module.getSetting(args[1]);
            if (setting == null) {
                ChatUtil.error(module.getName() + " has no setting called " + args[1] + ".");
                return;
            }
            setting.reset();
            ChatUtil.message("§b" + setting.getName() + " §7is back to its default.");
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, CommandManager.moduleIds());
        }
        if (index != 2) {
            return List.of();
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(tokens[1]);
        if (module == null) {
            return List.of();
        }
        return CommandManager.filter(current, module.settingIds());
    }
}
