package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;

// Walks to a spot with the pathfinder. The walk carries on until it arrives or
// until goto stop is typed.
public final class GotoCommand extends Command {

    // How many times the search may start again before it gives up.
    private static final int MAX_SEARCHES = 32;

    // How close the player has to get for the walk to count as finished.
    private static final double ARRIVED = 1.5;

    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker();

    private BlockPos goal;
    private int searches;

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
            if (goal == null) {
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
        goal = target;
        searches = 0;
        OfflineClient.INSTANCE.getEventBus().register(this);
        if (!search(player)) {
            stop();
            ChatUtil.error("Could not start the search.");
            return;
        }
        ChatUtil.message("§bGoto §7walking to §f" + text(target) + "§7.");
    }

    private BlockPos pointedAt() {
        if (OfflineClient.MC.hitResult instanceof BlockHitResult hit
            && hit.getType() == HitResult.Type.BLOCK) {
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
        BlockPos from = player.blockPosition();
        Integer x = coordinate(args[0], from.getX());
        Integer y = coordinate(args[1], from.getY());
        Integer z = coordinate(args[2], from.getZ());
        if (x == null || y == null || z == null) {
            ChatUtil.error("Those are not coordinates.");
            return null;
        }
        return new BlockPos(x, y, z);
    }

    // A tilde means the spot you are standing on already.
    private static Integer coordinate(String text, int origin) {
        try {
            if (text.startsWith("~")) {
                return origin + (text.length() == 1 ? 0 : Integer.parseInt(text.substring(1)));
            }
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean search(LocalPlayer player) {
        searches++;
        return finder.search(PathFinder.standingAt(player), new PathGoal.Spot(goal));
    }

    private void stop() {
        if (goal == null) {
            return;
        }
        goal = null;
        finder.cancel();
        walker.stop();
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    private void finish(String message) {
        stop();
        ChatUtil.message("§bGoto §7" + message);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || goal == null) {
            stop();
            return;
        }
        PathFinder.Result result = finder.poll();
        if (result != null) {
            if (result.nodes().size() < 2) {
                finish("cannot find a way there.");
                return;
            }
            walker.follow(result.nodes());
        }
        if (finder.busy()) {
            return;
        }
        if (walker.arrived() || walker.lost()) {
            if (player.distanceToSqr(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5)
                <= ARRIVED * ARRIVED) {
                finish("got there.");
                return;
            }
            if (searches >= MAX_SEARCHES) {
                finish("gave up before it got there.");
                return;
            }
            search(player);
            return;
        }
        walker.turn(PathWalker.Turn.CLIENT);
        walker.tick(finder.liveRules());
    }

    private static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
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
