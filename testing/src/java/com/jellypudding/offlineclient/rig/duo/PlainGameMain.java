package com.jellypudding.offlineclient.rig.duo;

import net.minecraft.client.main.Main;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

// Starts the game with no mod loader and no mod in it for the case that holds the client
// against a plain game. All it adds is a watch that ends this game once the game that started
// it has gone or its time is up. The game itself runs as it ships.
public final class PlainGameMain {

    static final String PARENT_PROPERTY = "rig.parent";
    static final String LIMIT_PROPERTY = "rig.plainLimit";
    private static final int DEFAULT_LIMIT_SECONDS = 600;

    private PlainGameMain() {
    }

    public static void main(String[] args) {
        Thread watch = new Thread(PlainGameMain::watch, "Rig plain game watch");
        watch.setDaemon(true);
        watch.start();
        Main.main(args);
    }

    private static void watch() {
        Optional<ProcessHandle> parent = ProcessHandle.of(Long.getLong(PARENT_PROPERTY, -1));
        int limit = Integer.getInteger(LIMIT_PROPERTY, DEFAULT_LIMIT_SECONDS);
        try {
            for (int waited = 0; waited < limit && parent.map(ProcessHandle::isAlive).orElse(false); waited++) {
                TimeUnit.SECONDS.sleep(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().halt(0);
    }
}
