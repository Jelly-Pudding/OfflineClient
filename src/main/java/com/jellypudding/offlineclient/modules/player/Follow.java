package com.jellypudding.offlineclient.modules.player;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.path.PathFinder;
import com.jellypudding.offlineclient.path.PathGoal;
import com.jellypudding.offlineclient.path.PathWalker;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

// Walks after another player using the pathfinder.
public final class Follow extends Module {

    // Ticks between two searches. A moving target must not thrash the worker.
    private static final int SEARCH_DELAY = 20;

    // How far the target has to move before the path is worked out again.
    private static final double MOVED = 3;

    public enum Target { NEAREST, NAME }

    private final EnumSetting<Target> target = new EnumSetting<>("Target",
        "Who to walk after.", Target.NEAREST)
        .describe(Target.NEAREST, "Walks after the closest player in range.")
        .describe(Target.NAME, "Walks after the player whose name you type in.");
    private final TextSetting name = new TextSetting("Name",
        "The account name of the player to follow.", "")
        .under(target, Target.NAME);
    private final NumberSetting distance = new NumberSetting("Distance to keep",
        "How close to get before you stop walking.", 3, 1, 10, 0.5, " blocks").min(1).max(32);
    private final NumberSetting range = new NumberSetting("Range to give up at",
        "How far the target may be before you stop following.", 48, 8, 128, 1, " blocks")
        .min(4).max(256);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns your view towards the way you are walking.", true);

    private final PathFinder finder = new PathFinder();
    private final PathWalker walker = new PathWalker();

    private Player following;
    private BlockPos searchedFor;
    private int delay;

    public Follow() {
        super("Follow", "Walks after another player.", Category.PLAYER);
        addSettings(target, name, distance, range, rotate);
        searchTags("path", "chase", "stalk");
    }

    @Override
    public String getSuffix() {
        return following == null ? null : EntityUtil.displayNameOf(following);
    }

    @Override
    protected void onEnable() {
        following = null;
        searchedFor = null;
        delay = 0;
    }

    @Override
    protected void onDisable() {
        finder.cancel();
        walker.stop();
        following = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        following = pick();
        if (following == null) {
            finder.cancel();
            walker.stop();
            return;
        }

        PathFinder.Result result = finder.poll();
        if (result != null) {
            walker.follow(result.nodes());
        }
        if (mc.player.distanceTo(following) <= distance.getValue()) {
            walker.releaseKeys();
            return;
        }

        BlockPos goal = PathFinder.standingAt(following);
        if (delay > 0) {
            delay--;
        } else if (wantsSearch(goal) && finder.search(
            PathFinder.standingAt(mc.player), new PathGoal.Around(goal, distance.getValue()))) {
            searchedFor = goal;
            delay = SEARCH_DELAY;
        }

        walker.turn(rotate.isOn() ? PathWalker.Turn.CLIENT : PathWalker.Turn.NONE);
        walker.tick(finder.liveRules());
    }

    private boolean wantsSearch(BlockPos goal) {
        if (finder.busy()) {
            return false;
        }
        if (walker.arrived() || walker.lost()) {
            return true;
        }
        return searchedFor == null || Math.sqrt(searchedFor.distSqr(goal)) > MOVED;
    }

    private Player pick() {
        if (target.is(Target.NAME)) {
            if (name.isBlank()) {
                return null;
            }
            for (Player other : mc.level.players()) {
                if (other != mc.player && name.getValue().equalsIgnoreCase(EntityUtil.nameOf(other))
                    && inRange(other)) {
                    return other;
                }
            }
            return null;
        }
        return (Player) EntityUtil.nearest(range.getValue(),
            entity -> entity instanceof Player other && other.isAlive() && !other.isSpectator());
    }

    private boolean inRange(Player other) {
        return other.isAlive() && !other.isSpectator()
            && mc.player.distanceTo(other) <= range.getValue();
    }
}
