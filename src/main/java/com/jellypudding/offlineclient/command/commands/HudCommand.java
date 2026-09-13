package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

// The bare name toggles the overlay like any module. This opens the drag editor.
public final class HudCommand extends Command {

    private static final String EDIT = "edit";

    public HudCommand() {
        super("hud", "Opens the editor where you drag the overlay pieces about.", "hud edit",
            "hudeditor");
    }

    @Override
    public void execute(String[] args) {
        HudModule hud = Modules.get(HudModule.class);
        if (hud == null) {
            ChatUtil.error("The overlay is not ready yet.");
            return;
        }
        if (args.length > 0 && !args[0].equalsIgnoreCase(EDIT)) {
            usage();
            return;
        }
        Screen editor = HudEditorScreen.open();
        // A screen opened mid key press receives that same press.
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(editor));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of(EDIT)) : List.of();
    }
}
