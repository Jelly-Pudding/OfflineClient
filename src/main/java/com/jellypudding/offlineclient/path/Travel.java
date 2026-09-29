package com.jellypudding.offlineclient.path;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;

// The walk the goto command and the finds list start. There is one at a time and it says
// in chat how it went.
public final class Travel {

    // A block nobody can stand in is walked up to. This close to it counts as there.
    private static final double BESIDE = 2;

    private static final Travel INSTANCE = new Travel();

    private final Trip trip = new Trip();
    private final WorldWatch world = new WorldWatch();

    private Travel() {
    }

    // Walks to the spot and stands beside a solid block. A walk already going gives way.
    // False when no search could start.
    public static boolean to(BlockPos target) {
        return INSTANCE.start(target);
    }

    public static boolean walking() {
        return INSTANCE.trip.active();
    }

    public static void stop() {
        INSTANCE.end();
    }

    private boolean start(BlockPos target) {
        end();
        trip.walker().turn(PathWalker.Turn.CLIENT);
        if (!trip.start(target, BlockUtil.isSolid(target) ? BESIDE : 0)) {
            trip.stop();
            ChatUtil.error("Could not start the search.");
            return false;
        }
        world.accept();
        OfflineClient.INSTANCE.getEventBus().register(this);
        ChatUtil.message("§bGoto §7walking to §f" + BlockUtil.text(target) + "§7.");
        return true;
    }

    private void end() {
        trip.stop();
        OfflineClient.INSTANCE.getEventBus().unregister(this);
    }

    private void finish(String message) {
        end();
        ChatUtil.message("§bGoto §7" + message);
    }

    // Nothing ticks outside a world. A walk still going when the next world or dimension
    // loads points at nothing there.
    @Subscribe
    private void onTick(TickEvent event) {
        if (world.changed()) {
            finish("stopped.");
            return;
        }
        switch (trip.tick()) {
            case ARRIVED -> finish("got there.");
            case FAILED -> finish("could not find a way there.");
            case IDLE -> end();
            case WALKING -> {
            }
        }
    }
}
