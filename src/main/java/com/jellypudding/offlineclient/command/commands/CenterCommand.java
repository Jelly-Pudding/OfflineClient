package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// At an edge your middle can hang over air. The block holding you up is the one centred on.
public final class CenterCommand extends Command {

    public CenterCommand() {
        super("center", "Puts you on the middle of the block you stand on.", "center", "centre");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (player.isPassenger()) {
            ChatUtil.error("The server keeps a rider on the mount. Get off first.");
            return;
        }
        BlockPos block = player.getOnPos();
        Vec3 middle = Vec3.atBottomCenterOf(block);
        AABB moved = player.getBoundingBox().move(middle.x - player.getX(), 0, middle.z - player.getZ());
        if (!player.level().noCollision(player, moved)) {
            ChatUtil.error("Something is in the way of the middle of the block.");
            return;
        }
        BlockUtil.centerPlayer(block);
        ChatUtil.message("§7Centred on §b" + BlockUtil.text(block));
    }
}
