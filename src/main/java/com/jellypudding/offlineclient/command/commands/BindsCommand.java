package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.KeyedStore;
import com.jellypudding.offlineclient.config.LoadoutStore;
import com.jellypudding.offlineclient.config.MacroStore;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Bind;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BindsCommand extends Command {

    private static final String FREE = "free";
    private static final String CLEAR = "clear";
    private static final List<String> WORDS = List.of(FREE, CLEAR);

    private final ActionSetting clearAll = new ActionSetting("Clear every bind",
        "Takes every module and key setting and macro and loadout off its key.", BindsCommand::clear).confirm();

    public BindsCommand() {
        super("binds", "Lists every key and mouse button the client uses and what else uses it.",
            "binds [free|clear]");
    }

    @Override
    public void execute(String[] args) {
        String word = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (args.length == 0) {
            listUsed();
        } else if (word.equals(FREE)) {
            listFree();
        } else if (word.equals(CLEAR)) {
            press(clearAll);
        } else {
            usage();
        }
    }

    private static void listUsed() {
        List<Bind> binds = List.copyOf(KeyUse.clientBinds());
        if (binds.isEmpty()) {
            ChatUtil.message("§7Nothing is bound.");
            return;
        }
        for (Bind bind : binds) {
            List<String> holders = KeyUse.holders(bind);
            ChatUtil.message((holders.size() > 1 ? "§e" : "§b") + bind.name() + " §f"
                + String.join("§7 and §f", holders));
        }
    }

    private static void listFree() {
        List<String> free = new ArrayList<>();
        addFree(free, InputConstants.KEY_A, InputConstants.KEY_0);
        addFree(free, InputConstants.KEY_F1, InputConstants.KEY_F12);
        if (free.isEmpty()) {
            ChatUtil.message("§7Every letter and digit and F key is in use.");
        } else {
            ChatUtil.message("§7Free keys §b" + String.join(" ", free));
        }
    }

    private static void addFree(List<String> free, int first, int last) {
        for (int key = first; key <= last; key++) {
            Bind bind = Bind.key(key);
            if (KeyUse.holders(bind).isEmpty()) {
                free.add(bind.name());
            }
        }
    }

    // The ClickGUI keeps its key. Without one the menu could only be opened by typing.
    private static void clear() {
        KeybindSetting menu = OfflineClient.INSTANCE.getModuleManager().get(ClickGuiModule.class).getKeybind();
        List<KeybindSetting> keys = KeyUse.keySettings().stream().filter(key -> key != menu).toList();
        boolean menuKept = menu.isBound();
        Defaults.Change change = Defaults.reset(() -> "every bind", keys, () -> {
            for (KeybindSetting key : keys) {
                key.setValue(Bind.NONE);
            }
        });
        Tally saved = new Tally();
        unbind(MacroStore.get(), "macro", saved);
        unbind(LoadoutStore.get(), "loadout", saved);
        if (change == null && saved.isEmpty()) {
            ChatUtil.message("§7There was nothing to take off a key.");
            return;
        }
        if (change != null) {
            ChatUtil.undoable("§7Took §f" + Tally.counted(change.size(), "bind") + "§7 off modules."
                + (menuKept ? " The ClickGUI keeps its key." : ""), change);
        }
        if (!saved.isEmpty()) {
            ChatUtil.message("§7Took the key off §f" + saved.words() + "§7."
                + (change != null ? " Undo leaves these off their keys." : ""));
        }
    }

    private static <T extends KeyedStore.Keyed<T>> void unbind(KeyedStore<T> store, String noun, Tally unbound) {
        for (T entry : store.all()) {
            if (entry.key().isBound()) {
                store.add(entry.withKey(Bind.NONE));
                unbound.add(noun);
            }
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, WORDS) : List.of();
    }
}
