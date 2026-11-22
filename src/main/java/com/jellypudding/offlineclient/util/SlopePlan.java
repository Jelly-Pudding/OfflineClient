package com.jellypudding.offlineclient.util;

import java.util.ArrayList;
import java.util.List;

// The floor height of a line at each depth. Level until a bend and then a block higher or lower
// for each block along until the next bend.
public final class SlopePlan {

    // From its depth on the floor starts from the floor of the depth before and changes by the
    // rise for each block. A rise of nought keeps it level.
    private record Bend(int depth, int from, int rise) {
    }

    // In order of depth.
    private final List<Bend> bends = new ArrayList<>();
    private int floor;

    public void reset(int floor) {
        this.floor = floor;
        bends.clear();
    }

    // Bends planned at or past the depth were never built and give way to this one.
    public void bend(int depth, int rise) {
        bends.removeIf(bend -> bend.depth() >= depth);
        bends.add(new Bend(depth, floorAt(depth - 1), rise));
    }

    public int floorAt(int depth) {
        for (int i = bends.size() - 1; i >= 0; i--) {
            Bend bend = bends.get(i);
            if (bend.depth() <= depth) {
                return bend.from() + (depth - bend.depth() + 1) * bend.rise();
            }
        }
        return floor;
    }

    // A step needs the head room of its neighbours to be walked or jumped.
    public int roofAt(int depth) {
        return Math.max(floorAt(depth), Math.max(floorAt(depth - 1), floorAt(depth + 1)));
    }
}
