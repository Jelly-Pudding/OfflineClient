package com.jellypudding.offlineclient.rig.core;

import net.minecraft.resources.Identifier;

// A server that runs beside the game sends a mark behind everything it changed. A mark is a
// cooldown of no length for a group no item has and the game keeps nothing of it. The game takes
// what it is sent in the order sent. A mark it took tells that all before it was taken too.
public final class Heard {

    private static final String SPACE = "rig";
    private static final String PATH = "heard";

    private static volatile int marks;
    private static volatile int taken;

    private Heard() {
    }

    public static int next() {
        return ++marks;
    }

    public static String group(int mark) {
        return SPACE + Identifier.NAMESPACE_SEPARATOR + PATH + mark;
    }

    // Fed by a mixin of the rig on the thread of the game.
    public static void took(Identifier group) {
        if (group.getNamespace().equals(SPACE) && group.getPath().startsWith(PATH)) {
            taken = Math.max(taken, Integer.parseInt(group.getPath().substring(PATH.length())));
        }
    }

    // A game that joins is sent the world afresh. A mark sent down the line it had before will
    // never come. Fed by a mixin of the rig on the thread of the game.
    public static void joined() {
        taken = Math.max(taken, marks);
    }

    public static boolean took(int mark) {
        return taken >= mark;
    }
}
