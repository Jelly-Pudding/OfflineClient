package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.gui.HudEditorScreen;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.modules.misc.HudModule;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;
import java.util.Locale;

// The bare name toggles the overlay like any module. This opens the drag editor or puts every
// option of one piece back. The piece switch stays as it is.
public final class HudCommand extends Command {

    private static final String EDIT = "edit";
    private static final String RESET = "reset";

    public HudCommand() {
        super("hud", "Opens the editor where you drag the overlay pieces about or puts one piece back.",
            "hud [edit|reset <piece>]", "hudeditor");
    }

    @Override
    public void execute(String[] args) {
        String word = args.length == 0 ? EDIT : args[0].toLowerCase(Locale.ROOT);
        if (word.equals(EDIT)) {
            Screen editor = HudEditorScreen.open();
            // A screen opened mid key press receives that same press.
            OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.setScreen(editor));
        } else if (word.equals(RESET) && args.length > 1) {
            reset(words(args, 1));
        } else {
            usage();
        }
    }

    private static void reset(String typed) {
        HudElement piece = piece(typed);
        if (piece == null) {
            ChatUtil.error("There is no HUD piece called " + typed + ".");
            return;
        }
        resetAll(piece.getName(), piece.getOptions());
    }

    // Spaces and case are ignored. Null when no piece has the name.
    private static HudElement piece(String typed) {
        String wanted = Setting.idFor(typed);
        for (HudElement element : pieces()) {
            if (Setting.idFor(element.getName()).equals(wanted)) {
                return element;
            }
        }
        return null;
    }

    // Empty whilst the HUD module has not been built yet.
    private static List<HudElement> pieces() {
        HudModule hud = Modules.get(HudModule.class);
        return hud == null ? List.of() : hud.getManager().all();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of(EDIT, RESET));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase(RESET)) {
            return CommandManager.filter(current,
                pieces().stream().map(element -> Setting.idFor(element.getName())).toList());
        }
        return List.of();
    }
}
