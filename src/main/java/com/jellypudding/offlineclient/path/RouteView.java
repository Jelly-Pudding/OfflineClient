package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.modules.movement.PathfinderModule;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.MoveGate;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Draws routes as lines through the world. The one a walk is on whilst the Pathfinder
// row asks for it and the one the path command worked out for the player to walk
// themselves. It only listens whilst it has one of them to draw.
public final class RouteView {

    private static final RouteView INSTANCE = new RouteView();

    // How far the line floats above the floor of each block.
    private static final double LIFT = 0.15;

    // A walk that has not ticked for this long is over.
    private static final int FRESH_TICKS = 2;

    // Nodes ahead in which the player is looked for to drop the part already walked.
    private static final int LOOK_AHEAD = 8;

    private final PathFinder finder = new PathFinder();
    private final WorldWatch world = new WorldWatch();

    // What is left of the route a walk follows and the tick that walk last ran on.
    private List<BlockPos> walked = List.of();
    private int walkedAt;

    // The route the command asked for and where it leads.
    private List<BlockPos> shown = List.of();
    private PathGoal goal;
    private boolean listening;

    private RouteView() {
    }

    // Called by a walker on every tick it walks.
    static void walking(List<BlockPos> path, int next) {
        INSTANCE.walked = path.subList(next - 1, path.size());
        INSTANCE.walkedAt = MoveGate.tick();
        if (PathfinderModule.showsRoute()) {
            INSTANCE.listen();
        }
    }

    // Works out the way to a goal and draws it once found. A route already shown gives
    // way. False when no search could start.
    public static boolean show(PathGoal goal) {
        LocalPlayer player = OfflineClient.MC.player;
        RouteView view = INSTANCE;
        view.finder.cancel();
        view.shown = List.of();
        if (player == null || !view.finder.search(PathFinder.standingAt(player), goal)) {
            view.goal = null;
            return false;
        }
        view.goal = goal;
        view.world.accept();
        view.listen();
        return true;
    }

    // Takes the route the command drew away. False when there was none.
    public static boolean clear() {
        RouteView view = INSTANCE;
        boolean had = view.goal != null;
        view.finder.cancel();
        view.shown = List.of();
        view.goal = null;
        return had;
    }

    private void listen() {
        if (!listening) {
            listening = true;
            OfflineClient.INSTANCE.getEventBus().register(this);
        }
    }

    private boolean walkIsFresh() {
        return MoveGate.tick() - walkedAt <= FRESH_TICKS && PathfinderModule.showsRoute();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (goal != null && world.changed()) {
            clear();
        }
        PathFinder.Result found = finder.poll();
        if (found != null) {
            found(found.nodes());
        }
        trim();
        if (goal == null && !walkIsFresh()) {
            listening = false;
            OfflineClient.INSTANCE.getEventBus().unregister(this);
        }
    }

    private void found(List<BlockPos> nodes) {
        if (nodes.size() < 2) {
            goal = null;
            tell("found no way there.");
            return;
        }
        shown = nodes;
        boolean whole = goal.reached(nodes.getLast());
        tell("drew §f" + Tally.counted(nodes.size() - 1, "block") + "§7"
            + (whole ? "." : " and stops short of the spot."));
    }

    // The search ends long after its command returned.
    private static void tell(String words) {
        ClientLines.inChat(() -> ChatUtil.tell("Path", words));
    }

    // The part of the shown route the player has walked past is drawn no more.
    private void trim() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || shown.size() < 2) {
            return;
        }
        BlockPos here = PathFinder.standingAt(player);
        for (int i = Math.min(shown.size(), LOOK_AHEAD) - 1; i > 0; i--) {
            if (shown.get(i).equals(here)) {
                shown = shown.subList(i, shown.size());
                return;
            }
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        int color = PathfinderModule.routeColor();
        if (walkIsFresh()) {
            draw(event.getBatch(), walked, color);
        }
        draw(event.getBatch(), shown, color);
    }

    private static void draw(DrawBatch batch, List<BlockPos> nodes, int color) {
        for (int i = 1; i < nodes.size(); i++) {
            batch.line(point(nodes.get(i - 1)), point(nodes.get(i)), color, true);
        }
    }

    private static Vec3 point(BlockPos node) {
        return Vec3.atBottomCenterOf(node).add(0, LIFT, 0);
    }
}
