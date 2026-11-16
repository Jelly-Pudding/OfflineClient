package com.jellypudding.offlineclient.rig;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

// Ends the game when a run outlives its time limit. A hung run never waits for a person.
public final class Watchdog implements PreLaunchEntrypoint {

    private static final String LIMIT_PROPERTY = "rig.limit";
    private static final int DEFAULT_LIMIT_SECONDS = 7200;
    private static final int TIMED_OUT = 3;
    private static final String PID_FILE = "pid.txt";
    private static final String TIMEOUT_FILE = "timeout.txt";

    @Override
    public void onPreLaunch() {
        Path out = RigConfig.out();
        try {
            Files.createDirectories(out);
            Files.writeString(out.resolve(PID_FILE), ProcessHandle.current().pid() + "\n");
        } catch (IOException e) {
            throw new IllegalStateException("The rig cannot write to " + out, e);
        }
        Thread guard = new Thread(() -> guard(out), "Rig watchdog");
        guard.setDaemon(true);
        guard.start();
    }

    private static void guard(Path out) {
        int limit = Integer.getInteger(LIMIT_PROPERTY, DEFAULT_LIMIT_SECONDS);
        try {
            TimeUnit.SECONDS.sleep(limit);
        } catch (InterruptedException e) {
            return;
        }
        try {
            Files.writeString(out.resolve(TIMEOUT_FILE), "The run passed its limit of " + limit
                + " seconds and was ended.\n\n" + threads());
        } catch (IOException e) {
            // The halt below matters more than the line written before it.
        }
        Runtime.getRuntime().halt(TIMED_OUT);
    }

    // Where every thread stood. It shows what the run was stuck on. A thread's own summary
    // stops after eight frames and the frame that matters often lies deeper.
    private static String threads() {
        StringBuilder text = new StringBuilder();
        for (ThreadInfo thread : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            text.append('"').append(thread.getThreadName()).append("\" ").append(thread.getThreadState());
            if (thread.getLockName() != null) {
                text.append(" on ").append(thread.getLockName());
            }
            if (thread.getLockOwnerName() != null) {
                text.append(" held by \"").append(thread.getLockOwnerName()).append('"');
            }
            text.append('\n');
            for (StackTraceElement frame : thread.getStackTrace()) {
                text.append("\tat ").append(frame).append('\n');
            }
            text.append('\n');
        }
        return text.toString();
    }
}
