package com.jellypudding.offlineclient.config;

import com.jellypudding.offlineclient.OfflineClient;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

// Files that two games on one computer may both write. One background thread does every
// read and write of this game. A lock file stops two games writing at the same moment and
// a stamp tells a game whether the other one wrote since it last looked.
public final class SharedFiles {

    // When a file last changed and how long it was. Any write gives a new stamp.
    public record Stamp(FileTime modified, long size) {
    }

    // Work on a file that may fail.
    @FunctionalInterface
    public interface FileWork<T> {
        T run() throws IOException;
    }

    private static final String LOCK_FILE = "sharing.lock";
    // How long a closing game waits for its files.
    private static final long WAIT_SECONDS = 10;

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "OfflineClient shared files");
        thread.setDaemon(true);
        return thread;
    });

    // A file lock belongs to the whole program. Threads of this game take turns here first.
    private static final Object TURN = new Object();

    private SharedFiles() {
    }

    // Runs the work on the file thread and hands its answer to the game thread. A failure
    // is logged and hands back null.
    public static <T> void submit(FileWork<T> work, Consumer<T> onGameThread) {
        THREAD.execute(() -> {
            T answer = null;
            try {
                answer = work.run();
            } catch (IOException | RuntimeException e) {
                OfflineClient.LOG.error("Shared file work failed", e);
            }
            T handed = answer;
            OfflineClient.MC.execute(() -> onGameThread.accept(handed));
        });
    }

    // Runs the work on the file thread after everything already waiting there and blocks
    // until it is done. For the moment the game closes. Null when it failed or took too long.
    public static <T> T await(FileWork<T> work) {
        try {
            return THREAD.submit(work::run).get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            OfflineClient.LOG.error("Shared file work failed", e);
        }
        return null;
    }

    // Null for a file that is not there.
    public static Stamp stamp(Path file) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(attributes.lastModifiedTime(), attributes.size());
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    // Runs the work whilst no other game on this computer can write a shared file. A reader
    // needs no lock when every write swaps in a whole file. An append only file must cope with
    // a torn last line.
    public static <T> T locked(FileWork<T> work) throws IOException {
        synchronized (TURN) {
            Path lock = DataFiles.path(LOCK_FILE);
            Files.createDirectories(lock.getParent());
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock _ = channel.lock()) {
                return work.run();
            }
        }
    }
}
