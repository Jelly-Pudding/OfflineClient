package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.misc.BetterChat;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;

// The muted players are a list in BetterChat. A name already on it comes off again.
public final class MuteCommand extends Command {

    public MuteCommand() {
        super("mute", "Hides the chat of a player or shows it again.", "mute <name|list>");
    }

    @Override
    public void execute(String[] args) {
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (args.length != 1 || betterChat == null) {
            usage();
            return;
        }
        if (args[0].equalsIgnoreCase("list")) {
            List<String> muted = betterChat.mutedPlayers();
            ChatUtil.message(muted.isEmpty() ? "§7Nobody is muted." : "§3Muted §b" + String.join("§7 §b", muted));
            return;
        }
        String name = args[0];
        if (betterChat.unmute(name)) {
            ChatUtil.message("§7The chat of §b" + name + "§7 shows again.");
        } else if (betterChat.mute(name)) {
            ChatUtil.message("§7Hid the chat of §b" + name + "§7."
                + (betterChat.isEnabled() ? "" : " §7It shows until BetterChat is on."));
        } else {
            ChatUtil.error("Every muted player row is taken.");
            return;
        }
        OfflineClient.INSTANCE.getConfigManager().saveSoon();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index != 1) {
            return List.of();
        }
        List<String> names = new ArrayList<>(List.of("list"));
        BetterChat betterChat = Modules.get(BetterChat.class);
        if (betterChat != null) {
            names.addAll(betterChat.mutedPlayers());
        }
        // Chat comes from the whole server and not only from the players in view.
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection != null) {
            String self = connection.getLocalGameProfile().name();
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!name.equals(self) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return CommandManager.filter(current, names);
    }
}
