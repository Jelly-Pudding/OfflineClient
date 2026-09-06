package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.screens.Screen;

public final class HudCommand extends Command {

    public HudCommand() {
        super("hud", "Opens the editor where you drag the overlay about.", "hud", "hudeditor");
    }

    @Override
    public void execute(String[] args) {
        Screen editor = HudEditorScreen.open();
        if (editor == null) {
            ChatUtil.error("The overlay is not ready yet.");
            return;
        }
        // A screen opened mid key press receives that same press.
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(editor));
    }
}
