package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudManager.Placement;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

// Keeps a piece that arrives at its starting place off everything already on the screen. One the
// player switches on there or puts back there that would lie over another piece or over the
// game's own bars takes the nearest free place and keeps it like a piece the player dragged.
// Nothing else ever moves.
final class Newcomers {

    private final HudManager manager;
    // The pieces that were on in the frame before. A piece taken off the overlay stays known
    // whilst an undo can still bring it back and leaves once nothing holds it.
    private final Set<HudElement> known = Collections.newSetFromMap(new WeakHashMap<>());
    // False until a frame has taken every piece that is on as it stands.
    private boolean seeded;

    Newcomers(HudManager manager) {
        this.manager = manager;
    }

    // The next frame takes every piece that is on as it stands. The first frame of a session
    // does and one that follows values applied in bulk.
    void takeAllAsTheyStand() {
        seeded = false;
    }

    // The piece is never seated for the switch it holds at this moment.
    void takeAsItStands(HudElement piece) {
        known.add(piece);
    }

    // Once a frame. A piece switched on with nothing to show yet waits here until it shows.
    void seat(Font font, int screenWidth, int screenHeight, boolean hidden) {
        List<HudElement> arrived = new ArrayList<>();
        for (HudElement element : manager.all()) {
            if (!element.isActive()) {
                known.remove(element);
            } else if (!seeded || !element.positionIsDefault()) {
                known.add(element);
            } else if (!known.contains(element) && HudManager.onScreen(element, hidden)) {
                arrived.add(element);
            }
        }
        seeded = true;
        if (!arrived.isEmpty()) {
            makeRoom(arrived, font, screenWidth, screenHeight, hidden);
            known.addAll(arrived);
        }
    }

    // Moves each of the pieces that lies over something to the nearest free place the way a drag
    // would. An earlier piece has its place before a later one.
    void makeRoom(List<HudElement> pieces, Font font, int screenWidth, int screenHeight, boolean hidden) {
        List<Box> taken = new ArrayList<>();
        for (Placement other : manager.others(pieces, font, screenWidth, screenHeight, hidden)) {
            // A cover lies over the rest on purpose and takes no room.
            if (!other.element().liesOver()) {
                taken.add(other.box());
            }
        }
        boolean moved = false;
        for (HudElement piece : pieces) {
            if (piece.liesOver()) {
                continue;
            }
            Placement at = HudManager.placement(piece, font, screenWidth, screenHeight);
            // A piece with a home belongs there. The game's own parts sit a pixel apart at home.
            Box free = piece.home(screenWidth, screenHeight) != null ? null
                : nearestFree(at.box(), taken, screenWidth, screenHeight, HudManager.margin(piece));
            if (free == null || free.equals(at.box())) {
                taken.add(at.box());
                continue;
            }
            HudManager.pin(piece, free.left(), free.top(), free.width(), free.height(), font, screenWidth,
                screenHeight);
            taken.add(free);
            moved = true;
        }
        if (moved) {
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
    }

    // The place nearest the box where it clears everything taken and stays on the screen. Null
    // when the screen has none. The nearest place always lines up with the box itself or lies
    // flush against the screen edge or something taken.
    private static Box nearestFree(Box box, List<Box> taken, int screenWidth, int screenHeight, int margin) {
        List<Integer> lefts = stops(box.left(), box.width(), screenWidth, margin, taken, true);
        List<Integer> tops = stops(box.top(), box.height(), screenHeight, margin, taken, false);
        Box best = null;
        long least = Long.MAX_VALUE;
        for (int left : lefts) {
            for (int top : tops) {
                long across = left - box.left();
                long down = top - box.top();
                long far = across * across + down * down;
                Box spot = new Box(left, top, box.width(), box.height());
                if (far < least && taken.stream().noneMatch(other -> crowds(spot, other))) {
                    best = spot;
                    least = far;
                }
            }
        }
        return best;
    }

    // Where the box may start along one axis. Its own place comes first and wins a tie.
    private static List<Integer> stops(int own, int size, int room, int margin, List<Box> taken, boolean across) {
        int last = Math.max(margin, room - size - margin);
        List<Integer> stops = new ArrayList<>();
        stops.add(own);
        stops.add(margin);
        stops.add(last);
        for (Box other : taken) {
            int start = across ? other.left() : other.top();
            int end = start + (across ? other.width() : other.height());
            for (int stop : new int[] {start - HudManager.CLEARANCE - size, end + HudManager.CLEARANCE}) {
                if (stop >= margin && stop <= last) {
                    stops.add(stop);
                }
            }
        }
        return stops;
    }

    // True when the two are nearer than the clearance on both axes.
    private static boolean crowds(Box one, Box other) {
        int gap = HudManager.CLEARANCE;
        return one.left() < other.left() + other.width() + gap && other.left() < one.left() + one.width() + gap
            && one.top() < other.top() + other.height() + gap && other.top() < one.top() + one.height() + gap;
    }
}
