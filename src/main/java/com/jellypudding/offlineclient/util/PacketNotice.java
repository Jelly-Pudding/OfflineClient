package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Tells the player in chat that the client threw a packet away. Safe from the netty thread.
// A hostile server can trip the same drop thousands of times a second. Each kind is said
// at most once in a few seconds.
public final class PacketNotice {

    private static final long QUIET_MILLIS = 5000;

    private static final Map<String, Long> LAST_SAID = new ConcurrentHashMap<>();

    private PacketNotice() {
    }

    public static void report(String kind, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_SAID.get(kind);
        if (last != null && now - last < QUIET_MILLIS) {
            return;
        }
        LAST_SAID.put(kind, now);
        // Chat is only safe on the game thread.
        OfflineClient.MC.schedule(() -> ChatUtil.error(message));
    }
}
