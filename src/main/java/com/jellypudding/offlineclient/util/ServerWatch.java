package com.jellypudding.offlineclient.util;

// Notices the client playing on another server. A rejoin to the same address and a
// dimension change do not count.
public final class ServerWatch {

    private String seen;

    // True once for each new server.
    public boolean changed() {
        String now = ServerInfo.key();
        if (now.equals(seen)) {
            return false;
        }
        seen = now;
        return true;
    }

    // The next check counts as a change whatever server the client is on.
    public void forget() {
        seen = null;
    }
}
