package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.path.Trip;
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

    private final Trip trip = new Trip();

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
            if (!trip.active()) {
                ChatUtil.error("You are not walking anywhere.");
            } else {
                stop();
                ChatUtil.message("§bGoto §7stopped.");
            }
            return;
        }
        BlockPos target = args.length == 0 ? pointedAt() : parse(args, player);
        if (target == null) {
            return;
        }
        stop();
        trip.walker().turn(PathWalker.Turn.CLIENT);
        if (!trip.start(target, 0)) {
            trip.stop();
            ChatUtil.error("Could not start the search.");
            return;
        }
        OfflineClient.INSTANCE.getEventBus().register(this);
        ChatUtil.message("§bGoto §7walking to §f" + BlockUtil.text(target) + "§7.");
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

    private void stop() {
        trip.stop();
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    private void finish(String message) {
        stop();
        ChatUtil.message("§bGoto §7" + message);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        switch (trip.tick()) {
            case ARRIVED -> finish("got there.");
            case FAILED -> finish("could not find a way there.");
            case IDLE -> stop();
            case WALKING -> {
            }
        }
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
