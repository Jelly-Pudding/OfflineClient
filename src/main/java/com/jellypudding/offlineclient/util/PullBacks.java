package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// Tells a pull back from every other move the server makes for you. A pull back lands within
// a block of where you stood in the last few seconds whilst a pearl or a command or a portal
// sends you somewhere else. Lagback counts every position packet and is for the modules that
// must hear of each one.
public final class PullBacks {

    private static final double REACH = 1;
    private static final int LOOK_BACK_TICKS = 100;

    // Shorter than this is the server agreeing with where you are.
    private static final double LEAST = 0.1;

    // Ticks after a join or respawn whilst the server is still putting you in place.
    private static final int SETTLE_TICKS = 40;

    // A spot given as a step on any of these from where you stand is no pull back.
    private static final Set<Relative> STEPPED = EnumSet.of(Relative.X, Relative.Y, Relative.Z);

    public static final PullBacks INSTANCE = new PullBacks();

    // Spots from the netty thread. The player must not be read there.
    private final Queue<Vec3> moved = new ConcurrentLinkedQueue<>();
    private final PositionHistory history = new PositionHistory();
    private final WorldWatch world = new WorldWatch();
    private final Feed<Double> pulls = new Feed<>();

    private PullBacks() {
    }

    // Each reader sees every pull back once as the blocks it moved you.
    public static Feed.Reader<Double> reader() {
        return INSTANCE.pulls.reader();
    }

    // Fired on the netty thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket packet
            && Collections.disjoint(packet.relatives(), STEPPED)) {
            moved.add(packet.change().position());
        }
    }

    // The spot recorded last tick is where you stood before the server moved you. First of all
    // because a module that reads the feed on the same tick must find the pull back there.
    @Subscribe(priority = Subscribe.FIRST)
    private void onTick(TickEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (world.changed()) {
            history.clear();
        }
        Vec3 spot;
        while ((spot = moved.poll()) != null) {
            judge(player, spot);
        }
        history.record(player.position());
    }

    private void judge(LocalPlayer player, Vec3 spot) {
        Vec3 was = history.ticksAgo(0);
        if (was == null || player.tickCount < SETTLE_TICKS || player.isPassenger()
            || !history.wasNear(spot, REACH, LOOK_BACK_TICKS)) {
            return;
        }
        double distance = was.distanceTo(spot);
        if (distance >= LEAST) {
            pulls.add(distance);
        }
    }
}
