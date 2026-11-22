package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.modules.misc.AutoLogin;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.util.List;
import java.util.Locale;

public final class AutoLoginCommand extends Command {

    public AutoLoginCommand() {
        super("autologin", "Saves the password AutoLogin types for the server and name you joined with.",
            "autologin <set|save|remove|list> [password|server]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(args);
            case "save" -> save();
            case "remove" -> remove(args);
            case "list" -> list();
            default -> usage();
        }
    }

    private void set(String[] args) {
        if (args.length < 2) {
            usage("autologin set <password>");
            return;
        }
        if (args.length > 2) {
            ChatUtil.error("A password cannot contain spaces.");
            return;
        }
        if (ServerInfo.address() == null) {
            ChatUtil.error("You are not on a server.");
            return;
        }
        String password = args[1];
        String key = AutoLogin.loginKey();
        ServerStore.logins().set(key, password);
        ChatUtil.message("§7Saved a password for §b" + key + "§7.");
        forgetTypedLine(password);
        AutoLogin autoLogin = Modules.get(AutoLogin.class);
        if (autoLogin != null && !autoLogin.isEnabled()) {
            autoLogin.setEnabled(true);
            ChatUtil.toggled(autoLogin);
        }
    }

    private static void save() {
        AutoLogin autoLogin = Modules.get(AutoLogin.class);
        String key = autoLogin == null ? null : autoLogin.saveTyped();
        if (key == null) {
            ChatUtil.error("No password typed on this join is waiting to be saved.");
        } else {
            ChatUtil.message("§7Saved a password for §b" + key + "§7.");
        }
    }

    private void remove(String[] args) {
        if (args.length > 2) {
            usage("autologin remove [server]");
            return;
        }
        String server = args.length == 2 ? args[1].toLowerCase(Locale.ROOT)
            : ServerInfo.address() == null ? null : AutoLogin.loginKey();
        if (server == null) {
            usage("autologin remove <server>");
            return;
        }
        forget(ServerStore.logins(), server, "password");
    }

    private static void list() {
        List<String> servers = ServerStore.logins().servers();
        if (servers.isEmpty()) {
            ChatUtil.message("§7No passwords are saved.");
            return;
        }
        ChatUtil.message("§3Passwords saved for §b" + String.join("§7 §b", servers));
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, List.of("set", "save", "remove", "list"));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("remove")) {
            return CommandManager.filter(current, ServerStore.logins().servers());
        }
        return List.of();
    }
}
