package com.jellypudding.offlineclient.rig.core;

// The clipboard of the test game. It lives in memory and nothing outside the game sees it.
public final class Clipboard {

    private static volatile String text = "";

    private Clipboard() {
    }

    public static String get() {
        return text;
    }

    public static void set(String copied) {
        text = copied == null ? "" : copied;
    }
}
