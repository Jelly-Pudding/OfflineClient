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

public final class HudCommand extends Command {

    private static final String EDIT = "edit";
    private static final String RESET = "reset";
    private static final String ADD = "add";
    private static final String REMOVE = "remove";

    public HudCommand() {
        super("hud", "Puts one overlay piece back or adds and removes a piece. Edit and hudeditor open the editor"
            + " where you drag the pieces about.", "hud <edit|reset <piece>|add <kind>|remove <piece>> or hudeditor",
            "hudeditor");
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
        } else if (word.equals(ADD) && args.length > 1) {
            add(words(args, 1));
        } else if (word.equals(REMOVE) && args.length > 1) {
            remove(words(args, 1));
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

    private static void add(String typed) {
        HudModule hud = Modules.get(HudModule.class);
        if (hud != null && !hud.families().add(typed)) {
            ChatUtil.error("The overlay cannot add " + ChatUtil.withArticle(typed) + ". Try "
                + String.join(" or ", hud.families().nouns()) + ".");
        }
    }

    private static void remove(String typed) {
        HudModule hud = Modules.get(HudModule.class);
        HudElement piece = piece(typed);
        if (hud == null || piece == null) {
            ChatUtil.error("There is no HUD piece called " + typed + ".");
        } else if (!hud.families().remove(piece)) {
            ChatUtil.error(piece.getName() + " always stays on the list. Switch it off to hide it.");
        }
    }

    private static HudElement piece(String typed) {
        String wanted = Setting.idFor(typed);
        for (HudElement element : pieces()) {
            if (Setting.idFor(element.getName()).equals(wanted)) {
                return element;
            }
        }
        return null;
    }

    private static List<HudElement> pieces() {
        HudModule hud = Modules.get(HudModule.class);
        return hud == null ? List.of() : hud.getManager().all();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of(EDIT, RESET, ADD, REMOVE));
        }
        HudModule hud = Modules.get(HudModule.class);
        if (index != 2 || hud == null) {
            return List.of();
        }
        List<String> offered = switch (tokens[1].toLowerCase(Locale.ROOT)) {
            case RESET -> ids(pieces());
            case REMOVE -> ids(hud.families().removable());
            case ADD -> hud.families().nouns().stream().map(Setting::idFor).toList();
            default -> List.of();
        };
        return CommandManager.filter(current, offered);
    }

    private static List<String> ids(List<HudElement> pieces) {
        return pieces.stream().map(element -> Setting.idFor(element.getName())).toList();
    }
}
