package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.world.LavaCast;
import com.jellypudding.offlineclient.modules.world.Staircase;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.LavaReach;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.TickRate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

// How long lava takes to stop flowing. With nothing typed it reports a running cast or
// follows the lava over the world from the block you look at. Heights give a staircase
// that drops a block for every block across. The flow slows with the server.
public final class FlowTimeCommand extends Command {

    // Below this rate the server is slow enough to mention.
    private static final float SLOW_TPS = 19.5f;

    public FlowTimeCommand() {
        super("flowtime", "Says how long lava takes to flow down a staircase or from the block you look at.",
            "flowtime [top] [bottom] | last", "lavatime");
    }

    @Override
    public void execute(String[] args) {
        if (OfflineClient.MC.player == null || args.length > 2) {
            usage();
            return;
        }
        if (args.length == 0) {
            here();
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("last")) {
            lastStaircase();
            return;
        }
        OptionalInt top = wholeNumber(args[0]);
        if (top.isEmpty()) {
            return;
        }
        OptionalInt bottom = args.length == 2 ? wholeNumber(args[1]) : OptionalInt.of(bottomFor(top.getAsInt()));
        if (bottom.isEmpty()) {
            return;
        }
        if (bottom.getAsInt() >= top.getAsInt()) {
            ChatUtil.error("The bottom has to be lower than the top.");
            return;
        }
        say(LavaReach.stairSteps(top.getAsInt() - bottom.getAsInt()),
            "to run down stairs from Y §f" + top.getAsInt() + "§7 to Y §f" + bottom.getAsInt());
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of("last")) : List.of();
    }

    // A running cast first. Then the flow from the top of the block you look at and
    // otherwise stairs from your feet down.
    private void here() {
        LavaCast cast = Modules.active(LavaCast.class);
        LavaCast.Progress progress = cast == null ? null : cast.progress();
        if (progress != null) {
            castProgress(progress);
            return;
        }
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null || hit.getDirection() != Direction.UP) {
            int feet = OfflineClient.MC.player.getBlockY();
            int bottom = bottomFor(feet);
            say(LavaReach.stairSteps(feet - bottom), "to run down stairs from your feet to Y §f" + bottom);
            return;
        }
        BlockPos spot = hit.getBlockPos().above();
        int steps = LavaReach.flowSteps(spot);
        if (steps >= 0) {
            say(steps, "to stop flowing from the top of that block");
            return;
        }
        int bottom = bottomFor(spot.getY());
        say(LavaReach.stairSteps(spot.getY() - bottom),
            "to run down stairs from that block to Y §f" + bottom + "§7. That flow is too big to follow");
    }

    private static void lastStaircase() {
        Staircase stairs = Modules.get(Staircase.class);
        Staircase.Run run = stairs == null ? null : stairs.lastRun();
        if (run == null) {
            ChatUtil.error("Build a staircase with Staircase first.");
            return;
        }
        say(run.flowSteps(), "to run from the top of your last staircase to the ground");
    }

    private static void castProgress(LavaCast.Progress progress) {
        String began = progress.layerSeconds() < 0 ? ""
            : " Its lava went in §f" + ChatUtil.duration(progress.layerSeconds()) + "§7 ago.";
        String flow = progress.flowEstimate() < 0 ? ""
            : " It should stop about §f" + ChatUtil.duration(progress.flowEstimate()) + "§7 after that.";
        ChatUtil.message("§bLavaCast §7is on layer §f" + progress.layer() + "§7 of §f" + progress.layers()
            + "§7 and is " + progress.doing() + "." + began + flow);
    }

    // Sea level for stairs that start above it and the bottom of the world below it.
    private static int bottomFor(int top) {
        ClientLevel level = OfflineClient.MC.level;
        return top > level.getSeaLevel() ? level.getSeaLevel() : level.getMinY();
    }

    private static void say(int steps, String what) {
        float tps = TickRate.INSTANCE.tps();
        String rate = tps < SLOW_TPS ? " at the current §f" + String.format(Locale.ROOT, "%.1f", tps) + "§7 TPS" : "";
        ChatUtil.message("§7Lava takes about §f" + ChatUtil.duration(LavaReach.seconds(steps)) + "§7 " + what
            + rate + ".");
    }
}
