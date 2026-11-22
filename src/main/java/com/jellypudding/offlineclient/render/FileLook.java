package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Looks at a file of the player's own at most once a second. The read runs on a worker and the game
// thread takes what it found. A look that comes back after the file was let go goes to the late
// hook in place of the taker.
final class FileLook<T> {

    private static final long GAP_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long NEVER = Long.MIN_VALUE;

    private final Consumer<T> take;
    private final Consumer<T> late;

    private boolean looking;
    private long lookedAt = NEVER;
    // Goes up when the file is let go. A look that comes back afterwards is late.
    private int round;

    FileLook(Consumer<T> take, Consumer<T> late) {
        this.take = take;
        this.late = late;
    }

    boolean due() {
        return !looking && (lookedAt == NEVER || System.nanoTime() - lookedAt >= GAP_NANOS);
    }

    // The owner asks due first. Failed stands in for a read that throws.
    void start(Supplier<T> read, Supplier<T> failed) {
        looking = true;
        lookedAt = System.nanoTime();
        int started = round;
        CompletableFuture.supplyAsync(read, Util.ioPool())
            .exceptionally(failure -> failed.get())
            .thenAcceptAsync(found -> {
                if (started != round) {
                    late.accept(found);
                    return;
                }
                looking = false;
                take.accept(found);
            }, OfflineClient.MC);
    }

    void hurry() {
        lookedAt = NEVER;
    }

    void release() {
        looking = false;
        lookedAt = NEVER;
        round++;
    }
}
