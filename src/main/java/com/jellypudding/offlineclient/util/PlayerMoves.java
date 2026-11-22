package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Follows the other players in view from the places the server sends for them. The client
// eases each one along a few ticks behind and only the sent place shows a jump or a burst
// of speed as it happened. Your own player and the bodies only this client has are left out.
public final class PlayerMoves {

    public record Jump(Player player, Vec3 from, Vec3 to) {
    }

    private static final int SECOND = SharedConstants.TICKS_PER_SECOND;

    // The server sends a whole new place once a player moves further than this in one go.
    // No running or flying covers it and a step this long is a jump rather than speed.
    private static final double JUMP_STEP = 8;

    // A player is left unjudged this long after they come into view or take a hit. A hit can
    // fling them far and fast.
    private static final int CLEAR_TICKS = 60;

    private static final int LOOKBACK = 10;

    private static final double SINK = 0.1;

    // The server sends where a moving player is every second tick. One still for longer has stopped.
    private static final int PLAYER_UPDATE_TICKS = 2;
    private static final int STILL_TICKS = 4;

    private static final class Track {

        Vec3 previous;
        Vec3 last;
        Vec3 velocity = Vec3.ZERO;
        int movedAt;
        int seenAt;
        final double[] steps = new double[SECOND];
        final int[] swings = new int[SECOND];
        final double[] heights = new double[LOOKBACK];
        int hovering;
        int clear = CLEAR_TICKS;
        int swingsNow;
    }

    // By entity id. A player who leaves view and comes back starts afresh.
    private final Map<Integer, Track> tracks = new HashMap<>();
    private int tick;

    // Once a tick before anything is read.
    public void tick() {
        tick++;
        for (AbstractClientPlayer player : OfflineClient.MC.level.players()) {
            if (!EntityUtil.isOtherPlayer(player)) {
                continue;
            }
            Track track = tracks.computeIfAbsent(player.getId(), id -> new Track());
            track.seenAt = tick;
            follow(player, track);
        }
        tracks.values().removeIf(track -> track.seenAt != tick);
    }

    private void follow(Player player, Track track) {
        Vec3 now = player.getPositionCodec().getBase();
        boolean fresh = track.last == null;
        track.previous = fresh ? now : track.last;
        track.last = now;
        double step = now.subtract(track.previous).horizontalDistance();
        boolean jumped = now.distanceTo(track.previous) > JUMP_STEP;
        if (jumped) {
            Arrays.fill(track.steps, 0);
            step = 0;
        }
        if (fresh || jumped) {
            track.velocity = Vec3.ZERO;
            track.movedAt = tick;
        } else if (!now.equals(track.previous)) {
            int took = Math.clamp(tick - track.movedAt, 1, PLAYER_UPDATE_TICKS);
            track.velocity = now.subtract(track.previous).scale(1.0 / took);
            track.movedAt = tick;
        } else if (tick - track.movedAt > STILL_TICKS) {
            track.velocity = Vec3.ZERO;
        }
        int slot = tick % SECOND;
        track.steps[slot] = step;
        track.swings[slot] = track.swingsNow;
        track.swingsNow = 0;
        int back = tick % LOOKBACK;
        double before = fresh ? now.y : track.heights[back];
        track.heights[back] = now.y;
        // A glide or a riptide leaves its steps in the window for a second after it ends. The
        // count below drops once this same tick.
        if (carried(player)) {
            track.clear = Math.max(track.clear, SECOND + 1);
        }
        // A stalled server sends nobody anywhere and then catches up in a rush.
        if (TickRate.INSTANCE.stalled()) {
            track.clear = CLEAR_TICKS;
        }
        if (track.clear > 0) {
            track.clear--;
            track.hovering = 0;
            return;
        }
        track.hovering = staysUp(player) && now.y > before - SINK ? track.hovering + 1 : 0;
    }

    private static boolean staysUp(Player player) {
        BlockState inside = player.getInBlockState();
        return !player.onGround() && !player.isInLiquid() && !player.onClimbable()
            && !inside.is(Blocks.COBWEB) && !inside.is(Blocks.POWDER_SNOW)
            && !SeenEffects.of(player).contains(MobEffects.LEVITATION);
    }

    // Gliding or riding or riptiding or able to fly. The speed of each says nothing.
    private static boolean carried(Player player) {
        return player.isFallFlying() || player.isPassenger() || player.isAutoSpinAttack() || player.isCreative()
            || player.isSpectator();
    }

    public List<Jump> jumps(double least) {
        List<Jump> jumps = new ArrayList<>();
        for (Map.Entry<Integer, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            if (track.previous.distanceTo(track.last) >= least
                && OfflineClient.MC.level.getEntity(entry.getKey()) instanceof Player player && !player.isPassenger()) {
                jumps.add(new Jump(player, track.previous, track.last));
            }
        }
        return jumps;
    }

    // Nought whilst the player is not judged.
    public double speed(Player player) {
        Track track = judged(player);
        if (track == null) {
            return 0;
        }
        double covered = 0;
        for (double step : track.steps) {
            covered += step;
        }
        return covered;
    }

    // Blocks a tick between the last two places the server sent. A player who stood still sent
    // nothing for a while and only set off within the last update.
    public Vec3 velocity(Player player) {
        Track track = tracks.get(player.getId());
        return track == null ? Vec3.ZERO : track.velocity;
    }

    // Nought whilst the player is not judged.
    public int hovering(Player player) {
        Track track = judged(player);
        return track == null ? 0 : track.hovering;
    }

    public int swings(Player player) {
        Track track = tracks.get(player.getId());
        if (track == null) {
            return 0;
        }
        int swings = track.swingsNow;
        for (int count : track.swings) {
            swings += count;
        }
        return swings;
    }

    public void swung(int entityId) {
        Track track = tracks.get(entityId);
        if (track != null) {
            track.swingsNow++;
        }
    }

    // The player took a hit and may fly off for a while.
    public void hurt(int entityId) {
        Track track = tracks.get(entityId);
        if (track != null) {
            track.clear = CLEAR_TICKS;
        }
    }

    public void clear() {
        tracks.clear();
    }

    // Null for a player not followed long enough to judge.
    private Track judged(Player player) {
        Track track = tracks.get(player.getId());
        return track == null || track.clear > 0 ? null : track;
    }
}
