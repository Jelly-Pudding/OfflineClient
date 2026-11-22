package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.core.RigFailure;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

// An address on this machine that stands in for the home of a resource pack. It hands out nothing
// and counts how often a game came asking.
public final class PackTrap implements AutoCloseable {

    private final ServerSocket door;
    private final AtomicInteger asked = new AtomicInteger();

    public PackTrap() {
        try {
            door = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new RigFailure("No port could be opened for the pack address", e);
        }
        Thread counter = new Thread(this::count, "Rig pack trap");
        counter.setDaemon(true);
        counter.start();
    }

    private void count() {
        while (!door.isClosed()) {
            try (Socket caller = door.accept()) {
                asked.incrementAndGet();
            } catch (IOException e) {
                // A closed door ends the wait.
            }
        }
    }

    public String url() {
        return "http://127.0.0.1:" + door.getLocalPort() + "/pack.zip";
    }

    public int asked() {
        return asked.get();
    }

    @Override
    public void close() {
        try {
            door.close();
        } catch (IOException e) {
            throw new RigFailure("The pack address could not be closed", e);
        }
    }
}
