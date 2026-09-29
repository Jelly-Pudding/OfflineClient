package com.jellypudding.offlineclient.util;

// A chat error said once and not again until a different one takes its place or the owner
// clears it. A module that fails the same way on every shot then tells the player once.
public final class ChatWarning {

    private String last;

    public void say(String text) {
        if (!text.equals(last)) {
            last = text;
            ChatUtil.error(text);
        }
    }

    public void clear() {
        last = null;
    }
}
