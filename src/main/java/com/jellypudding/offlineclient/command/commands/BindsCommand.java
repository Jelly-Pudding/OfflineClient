package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.KeyUse;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;

// A key held by more than one thing is shown in yellow.
public final class BindsCommand extends Command {

    private static final String FREE = "free";

    public BindsCommand() {
        super("binds", "Lists every key the client uses and what else uses it.", "binds [free]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            listUsed();
        } else if (args.length == 1 && args[0].equalsIgnoreCase(FREE)) {
            listFree();
        } else {
            usage();
        }
    }

    private static void listUsed() {
        List<Integer> keys = List.copyOf(KeyUse.clientKeys());
        if (keys.isEmpty()) {
            ChatUtil.message("§7Nothing is bound.");
            return;
        }
        for (int key : keys) {
            List<String> holders = KeyUse.holders(key);
            ChatUtil.message((holders.size() > 1 ? "§e" : "§b") + KeybindSetting.nameOfKey(key) + " §f"
                + String.join("§7 and §f", holders));
        }
    }

    // The letters and digits and F keys. The keys a player reaches for when picking one.
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
            if (KeyUse.holders(key).isEmpty()) {
                free.add(KeybindSetting.nameOfKey(key));
            }
        }
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of(FREE)) : List.of();
    }
}
