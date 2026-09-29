package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Walks to a spot with the pathfinder. The walk carries on until it arrives or
// until goto stop is typed.
public final class GotoCommand extends Command {

    public GotoCommand() {
        super("goto", "Walks to a spot or to the block you are pointing at.",
            "goto [x y z] or goto stop", "walkto");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            if (!Travel.walking()) {
                ChatUtil.error("You are not walking anywhere.");
            } else {
                Travel.stop();
                ChatUtil.message("§bGoto §7stopped.");
            }
            return;
        }
        BlockPos target = args.length == 0 ? pointedAt() : parse(args, player);
        if (target != null) {
            Travel.to(target);
        }
    }

    private BlockPos pointedAt() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit != null) {
            return hit.getBlockPos().above();
        }
        ChatUtil.error("Point at a block or type the coordinates.");
        return null;
    }

    private BlockPos parse(String[] args, LocalPlayer player) {
        if (args.length != 3) {
            usage();
            return null;
        }
        Vec3 spot = coordinates(args, 0, Vec3.atLowerCornerOf(player.blockPosition()));
        return spot == null ? null : BlockPos.containing(spot);
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || index < 1 || index > 3) {
            return List.of();
        }
        BlockPos pos = player.blockPosition();
        List<String> options = new ArrayList<>();
        if (index == 1) {
            options.add("stop");
        }
        options.add("~");
        options.add(String.valueOf(index == 1 ? pos.getX() : index == 2 ? pos.getY() : pos.getZ()));
        return CommandManager.filter(current, options);
    }
}
