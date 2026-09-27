package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.modules.misc.AutoLogin;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;

import java.util.List;
import java.util.Locale;

// The bare name toggles the module like any other. This keeps the passwords it answers with.
public final class AutoLoginCommand extends Command {

    public AutoLoginCommand() {
        super("autologin", "Saves the password AutoLogin types for the server you are on.",
            "autologin <set|remove|list> [password|server]");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set" -> set(args);
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
        ServerStore.logins().set(ServerInfo.key(), password);
        ChatUtil.message("§7Saved a password for §b" + ServerInfo.address() + "§7.");
        forgetTypedLine(password);
        AutoLogin autoLogin = Modules.get(AutoLogin.class);
        if (autoLogin != null && !autoLogin.isEnabled()) {
            autoLogin.setEnabled(true);
            ChatUtil.toggled(autoLogin);
        }
    }

    // The typed line joins the chat history once this returns. It is taken out again
    // on the next pass of the game thread.
    private static void forgetTypedLine(String password) {
        String prefix = OfflineClient.INSTANCE.getCommandManager().getPrefix();
        OfflineClient.MC.schedule(() -> OfflineClient.MC.gui.hud.getChat().getRecentChat()
            .removeIf(line -> line.startsWith(prefix) && line.contains(password)));
    }

    private void remove(String[] args) {
        if (args.length > 2) {
            usage("autologin remove [server]");
            return;
        }
        String server = args.length == 2 ? args[1].toLowerCase(Locale.ROOT)
            : ServerInfo.address() == null ? null : ServerInfo.key();
        if (server == null) {
            usage("autologin remove <server>");
            return;
        }
        if (ServerStore.logins().remove(server)) {
            ChatUtil.message("§7Forgot the password for §b" + server + "§7.");
        } else {
            ChatUtil.error("No password is saved for " + server + ".");
        }
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
            return CommandManager.filter(current, List.of("set", "remove", "list"));
        }
        if (index == 2 && tokens[1].equalsIgnoreCase("remove")) {
            return CommandManager.filter(current, ServerStore.logins().servers());
        }
        return List.of();
    }
}
