package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Tally;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

// The walk the goto command and the finds list start. There is one at a time.
public final class Travel {

    // A block nobody can stand in is walked up to. This close to it counts as there.
    private static final double BESIDE = 2;

    private static final Travel INSTANCE = new Travel();

    // A column is a place on the map whose height the walk finds out and its spot only says roughly
    // where it is.
    public record Stop(BlockPos spot, boolean column) {

        public Stop {
            spot = spot.immutable();
        }

        public static Stop at(BlockPos spot) {
            return new Stop(spot, false);
        }

        public static Stop column(BlockPos near) {
            return new Stop(near, true);
        }

        // Worked out as the walk there begins. The world round a far stop loads on the way.
        public PathGoal goal() {
            if (column) {
                return new PathGoal.Column(spot.getX(), spot.getZ());
            }
            return BlockUtil.isSolid(spot) ? new PathGoal.Around(spot, BESIDE) : new PathGoal.Spot(spot);
        }

        public String name() {
            return column ? spot.getX() + " " + spot.getZ() : BlockUtil.text(spot);
        }
    }

    private final Trip trip = new Trip();
    private final WorldWatch world = new WorldWatch();
    private List<Stop> stops = List.of();
    private int next;
    private PathGoal goal;
    private int waitTicks;
    private int waiting;
    private boolean loop;
    // True for a walk a command started. Its end goes in the chat beside the command.
    private boolean typed;

    private Travel() {
    }

    // A walk already going gives way. False when no search could start.
    public static boolean to(BlockPos target) {
        return along(List.of(Stop.at(target)), 0, false);
    }

    public static boolean along(List<Stop> stops, int waitTicks, boolean loop) {
        return INSTANCE.start(List.copyOf(stops), waitTicks, loop);
    }

    public static boolean walking() {
        return !INSTANCE.stops.isEmpty();
    }

    public static void stop() {
        INSTANCE.end();
    }

    public static BlockPos goal() {
        Travel travel = INSTANCE;
        return travel.stops.isEmpty() ? null : travel.stops.get(travel.next).spot();
    }

    // Minus one whilst no walk is going.
    public static double distanceLeft() {
        PathGoal goal = INSTANCE.goal;
        LocalPlayer player = OfflineClient.MC.player;
        return goal == null || player == null ? -1 : goal.distance(player.position());
    }

    private boolean start(List<Stop> route, int wait, boolean again) {
        end();
        stops = route;
        waitTicks = wait;
        loop = again;
        if (!walkTo(0)) {
            end();
            ChatUtil.error("Could not start the search.");
            return false;
        }
        world.accept();
        typed = ClientLines.replying();
        OfflineClient.INSTANCE.getEventBus().register(this);
        ChatUtil.tell("Goto", "walking " + describe() + "§7.");
        return true;
    }

    private String describe() {
        if (stops.size() == 1) {
            return "to §f" + stops.getFirst().name();
        }
        return "§f" + Tally.counted(stops.size(), "stop") + (loop ? " §7over and over" : "");
    }

    private boolean walkTo(int stop) {
        next = stop;
        waiting = 0;
        goal = stops.get(stop).goal();
        trip.walker().turn(PathWalker.Turn.CLIENT);
        return trip.start(goal);
    }

    private void end() {
        trip.stop();
        stops = List.of();
        goal = null;
        waiting = 0;
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    private void finish(String message) {
        end();
        Runnable line = () -> ChatUtil.tell("Goto", message);
        if (typed) {
            ClientLines.inChat(line);
        } else {
            line.run();
        }
    }

    // Nothing ticks outside a world. A walk still going when the next world or dimension
    // loads points at nothing there.
    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            finish("stopped.");
            return;
        }
        if (waiting > 0) {
            if (--waiting == 0 && !walkTo(next)) {
                finish("could not start the search.");
            }
            return;
        }
        switch (trip.tick()) {
            case ARRIVED -> arrived();
            case FAILED -> finish(stops.size() == 1 ? "could not find a way there."
                : "could not find a way to §f" + stops.get(next).name() + "§7.");
            case IDLE -> end();
            case WALKING -> {
            }
        }
    }

    private void arrived() {
        boolean last = next == stops.size() - 1;
        if (last && !loop) {
            finish("got there.");
            return;
        }
        next = last ? 0 : next + 1;
        goal = stops.get(next).goal();
        if (waitTicks > 0) {
            waiting = waitTicks;
        } else if (!walkTo(next)) {
            finish("could not start the search.");
        }
    }
}
