package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import org.lwjgl.glfw.GLFW;

public final class BindCommand extends Command {

    public BindCommand() {
        super("bind", "Binds a module to a key. Use none to unbind.",
            "bind <module> <key|none>", "b");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 2) {
            ChatUtil.error("Usage: " + getUsage());
            return;
        }
        Module module = OfflineClient.INSTANCE.getModuleManager().get(args[0]);
        if (module == null) {
            ChatUtil.error("Unknown module: " + args[0]);
            return;
        }

        String key = args[1].toUpperCase();
        if (key.equals("NONE")) {
            module.getKeybind().setValue(KeybindSetting.UNBOUND);
            ChatUtil.message("§b" + module.getName() + " §7unbound.");
        } else if (key.length() == 1 && (Character.isLetterOrDigit(key.charAt(0)))) {
            // GLFW key codes for letters and digits match their ASCII codes.
            module.getKeybind().setValue((int) key.charAt(0));
            ChatUtil.message("§b" + module.getName() + " §7bound to §b" + key + "§7.");
        } else if (key.startsWith("F") && key.length() <= 3) {
            int f = Integer.parseInt(key.substring(1));
            module.getKeybind().setValue(GLFW.GLFW_KEY_F1 + f - 1);
            ChatUtil.message("§b" + module.getName() + " §7bound to §b" + key + "§7.");
        } else {
            ChatUtil.error("Can't parse key '" + args[1] + "'. Use the ClickGUI for special keys.");
            return;
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }
}
