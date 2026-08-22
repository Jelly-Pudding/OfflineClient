package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;

public final class GuiCommand extends Command {

    public GuiCommand() {
        super("gui", "Opens the ClickGUI.", "gui", "clickgui", "menu");
    }

    @Override
    public void execute(String[] args) {
        OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).open();
    }
}
