package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Hop;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.OptionalDouble;

public final class HClipCommand extends Command {

    // The search for the far side of a wall steps a quarter block at a time.
    private static final double SEARCH_STEP = 0.25;

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

    // The first spot past the wall ahead where there is room again out of lava.
    private static void throughWall(Entity mover, Vec3 ahead) {
        boolean inWall = false;
        for (double reach = SEARCH_STEP; reach <= Hop.MAX_TRIP; reach += SEARCH_STEP) {
            Vec3 spot = mover.position().add(ahead.scale(reach));
            if (inWall && Hop.safeAt(mover, spot)) {
                hopTo(spot, "§7Moved §b" + Math.round(reach) + " §7blocks.");
                return;
            }
            inWall |= !Hop.fits(mover, spot);
        }
        ChatUtil.error("There is no wall ahead with room behind it.");
    }
}
