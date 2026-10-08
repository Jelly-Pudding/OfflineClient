package com.jellypudding.offlineclient.util;

// The eight points of the compass an eighth of a turn apart in the order the yaw passes them
// from south. The four main points also name the way along the axis they face.
public enum CompassPoint {

    SOUTH("S", "south", "+Z"),
    SOUTH_WEST("SW", "south west", null),
    WEST("W", "west", "-X"),
    NORTH_WEST("NW", "north west", null),
    NORTH("N", "north", "-Z"),
    NORTH_EAST("NE", "north east", null),
    EAST("E", "east", "+X"),
    SOUTH_EAST("SE", "south east", null);

    // Degrees of yaw from one point to the next.
    public static final int STEP = 45;

    private static final CompassPoint[] ALL = values();

    private final String letters;
    private final String words;
    private final String axis;

    CompassPoint(String letters, String words, String axis) {
        this.letters = letters;
        this.words = words;
        this.axis = axis;
    }

    // Such as NE.
    public String letters() {
        return letters;
    }

    // Such as north east.
    public String words() {
        return words;
    }

    // Such as +X. Null for the points between the main four.
    public String axis() {
        return axis;
    }

    // The yaw in degrees that faces this point.
    public int yaw() {
        return ordinal() * STEP;
    }

    // The point nearest a yaw in degrees.
    public static CompassPoint nearest(float yaw) {
        return ALL[Math.floorMod(Math.round(yaw / STEP), ALL.length)];
    }
}
