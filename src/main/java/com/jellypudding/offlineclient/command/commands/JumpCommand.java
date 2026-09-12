package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

public final class JumpCommand extends Command {

    public JumpCommand() {
        super("jump", "Makes you jump once.", "jump");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (!player.onGround()) {
            ChatUtil.error("You are already in the air.");
            return;
        }
        player.jumpFromGround();
    }
}
