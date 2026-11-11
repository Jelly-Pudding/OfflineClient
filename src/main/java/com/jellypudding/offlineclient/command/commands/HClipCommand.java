package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;
import java.util.function.Consumer;

public final class HClipCommand extends Command {

    public HClipCommand() {
        super("hclip", "Moves you the way you look without touching your height. With no number it"
            + " goes through the wall in front of you.", "hclip [blocks]", "h");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || args.length > 1) {
            usage();
            return;
        }
        Entity mover = Hop.mover(player);
        Vec3 ahead = Vec3.directionFromRotation(0, player.getYRot());
        if (args.length == 0) {
            throughWall(mover, ahead);
            return;
        }
        OptionalDouble blocks = number(args[0]);
        if (blocks.isPresent()) {
            hopTo(mover.position().add(ahead.scale(blocks.getAsDouble())), "§7Moved §b" + args[0] + " §7blocks.");
        }
    }

    // Straight through the wall where the server lets that stand and over it elsewhere. A
    // hop the server pulls back after all is tried again over the wall.
    private static void throughWall(Entity mover, Vec3 ahead) {
        Vec3 spot = Hop.beyond(mover, ahead, Hop.MAX_TRIP);
        if (spot == null) {
            ChatUtil.error("There is no wall ahead with room behind it.");
            return;
        }
        Consumer<Hop.Result> told = Hop.telling(
            "§7Moved §b" + Math.round(spot.distanceTo(mover.position())) + " §7blocks.");
        Hop.Plan plan = Hop.plan();
        if (!plan.attempt(trip -> trip.through(spot))) {
            plan.to(spot).go(told);
            return;
        }
        plan.go(result -> {
            if (result == Hop.Result.PULLED_BACK) {
                ClientLines.inChat(() -> ChatUtil.error(result.problem()));
                Hop.travel(spot, told);
            } else {
                told.accept(result);
            }
        });
    }
}
