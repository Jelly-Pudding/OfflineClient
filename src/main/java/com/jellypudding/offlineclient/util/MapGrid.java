package com.jellypudding.offlineclient.util;

import net.minecraft.util.Mth;

// The grid the game lays maps out on. A fresh map shows a square of 128 blocks a side and each
// zoom doubles it. The grid starts half a fresh map west and north of the world's middle.
public final class MapGrid {

    // Blocks a side of a fresh map. Every map is this many pixels a side whatever its zoom.
    public static final int SIDE = 128;
    private static final int SHIFT = SIDE / 2;

    private MapGrid() {
    }

    public static int side(int zoom) {
        return SIDE << zoom;
    }

    public static int edge(double coordinate, int zoom) {
        int side = side(zoom);
        return Mth.floor((coordinate + SHIFT) / side) * side - SHIFT;
    }
}
