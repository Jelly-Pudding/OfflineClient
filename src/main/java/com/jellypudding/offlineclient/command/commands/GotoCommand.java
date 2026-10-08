package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Walks to a spot with the pathfinder. The walk carries on until it arrives or
// until goto stop is typed.
public final class GotoCommand extends Command {

    public GotoCommand() {
        super("goto", "Walks to a spot or a player or a waypoint or the block you point at.",
            "goto [x y z|player|waypoint] or goto stop", "walkto");
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
        BlockPos target = switch (args.length) {
            case 0 -> pointedAt();
            case 1 -> named(args[0]);
            case 3 -> parse(args, player);
            default -> {
                usage();
                yield null;
            }
        };
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

    // A player in sight goes before a waypoint of the same name. The walk heads for the spot
    // the player stands on as it starts.
    private static BlockPos named(String name) {
        for (Player other : players()) {
            if (EntityUtil.nameOf(other).equalsIgnoreCase(name)) {
                return other.blockPosition();
            }
        }
        WaypointStore.Waypoint waypoint = WaypointStore.get().find(name);
        if (waypoint != null) {
            return waypoint.pos();
        }
        ChatUtil.error("No player nearby or waypoint here is called " + name + ".");
        return null;
    }

    private BlockPos parse(String[] args, LocalPlayer player) {
        Vec3 spot = coordinates(args, 0, Vec3.atLowerCornerOf(player.blockPosition()));
        return spot == null ? null : BlockPos.containing(spot);
    }

    private static List<Player> players() {
        List<Player> others = new ArrayList<>();
        for (Player other : OfflineClient.MC.level.players()) {
            if (EntityUtil.isOtherPlayer(other)) {
                others.add(other);
            }
        }
        return others;
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
            players().forEach(other -> options.add(EntityUtil.nameOf(other)));
            WaypointStore.get().here().forEach(waypoint -> options.add(waypoint.name()));
        }
        options.add("~");
        options.add(String.valueOf(index == 1 ? pos.getX() : index == 2 ? pos.getY() : pos.getZ()));
        return CommandManager.filter(current, options);
    }
}
