package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class GetPosCommand extends Command {

    public GetPosCommand() {
        super("getpos", "Prints where you are and the matching spot in the other dimension.",
            "getpos", "pos", "coords");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        BlockPos pos = player.blockPosition();
        ChatUtil.message("§7You are at §b" + BlockUtil.text(pos));
        if (player.level().dimension() == Level.NETHER) {
            ChatUtil.message("§7Overworld §b" + pos.getX() * WaypointStore.NETHER_SCALE + " "
                + pos.getZ() * WaypointStore.NETHER_SCALE);
        } else if (player.level().dimension() == Level.OVERWORLD) {
            ChatUtil.message("§7Nether §b" + Math.floorDiv(pos.getX(), WaypointStore.NETHER_SCALE) + " "
                + Math.floorDiv(pos.getZ(), WaypointStore.NETHER_SCALE));
        }
    }
}
