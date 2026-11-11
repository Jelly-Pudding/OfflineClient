package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.util.Util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Looks at a file of the player's own at most once a second. The read runs on a worker and the game
// thread takes what it found. One look is out at a time. A look that comes back after the file was let
// go is late and goes to the late hook in place of the taker.
final class FileLook<T> {

    private static final long GAP_NANOS = TimeUnit.SECONDS.toNanos(1);
    // No look has begun since the file was let go.
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

    // True once the last look is back and a second has passed since it began.
    boolean due() {
        return !looking && (lookedAt == NEVER || System.nanoTime() - lookedAt >= GAP_NANOS);
    }

    // Sends the read to a worker. The owner asks due first. Failed stands in for a read that throws.
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

    // The next look may begin as soon as the one out is back.
    void hurry() {
        lookedAt = NEVER;
    }

    // Forgets the look that is out. What it finds is late.
    void release() {
        looking = false;
        lookedAt = NEVER;
        round++;
    }
}
