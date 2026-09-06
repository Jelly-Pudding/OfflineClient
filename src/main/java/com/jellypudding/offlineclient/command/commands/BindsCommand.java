package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;

public final class BindsCommand extends Command {

    public BindsCommand() {
        super("binds", "Lists every module with a key on it.", "binds");
    }

    @Override
    public void execute(String[] args) {
        int found = 0;
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (!module.getKeybind().isBound()) {
                continue;
            }
            found++;
            ChatUtil.message("§b" + module.getName() + " §7on §b"
                + module.getKeybind().getKeyName());
        }
        if (found == 0) {
            ChatUtil.message("§7Nothing is bound.");
        }
    }
}
