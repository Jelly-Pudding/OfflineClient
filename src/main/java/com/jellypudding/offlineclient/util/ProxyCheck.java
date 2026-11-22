package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.ProxyStore;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Times how long each saved proxy takes to take a connection. Only the proxies themselves are
// dialled and nothing is sent to them. Every proxy is tried on a thread of its own and one that
// fails is tried once more after a pause before it counts as dead.
public final class ProxyCheck {

    public enum State { CHECKING, ANSWERED, DEAD }

    public record Result(State state, long millis, String why) {
    }

    // One press of check. A newer press takes over and the threads of this one dial no more.
    private static final class Run {

        private final int total;
        private final AtomicInteger left;
        private final Semaphore room = new Semaphore(MOST_AT_ONCE);
        private final Map<ProxyStore.Entry, Result> results = new ConcurrentHashMap<>();

        private Run(int total) {
            this.total = total;
            this.left = new AtomicInteger(total);
        }
    }

    private static final Result CHECKING = new Result(State.CHECKING, 0, "");
    // A failed try alone says little. A proxy has to fail this often to count as dead.
    private static final int TRIES = 2;
    private static final int TRY_SECONDS = 5;
    private static final int TRY_MS = (int) TimeUnit.SECONDS.toMillis(TRY_SECONDS);
    private static final long RETRY_MS = TimeUnit.SECONDS.toMillis(2);
    private static final int MOST_AT_ONCE = 16;
    private static final long LEFT_OUT_RANK = Long.MAX_VALUE - 1;
    private static final long DEAD_RANK = Long.MAX_VALUE;

    private static volatile Run current;

    private ProxyCheck() {
    }

    // The reply runs on the game thread once the last proxy is done unless a newer check took over.
    public static void start(List<ProxyStore.Entry> entries, Runnable done) {
        Run run = new Run(entries.size());
        entries.forEach(entry -> run.results.put(entry, CHECKING));
        current = run;
        for (ProxyStore.Entry entry : entries) {
            Thread.ofVirtual().name("OfflineClient proxy check").start(() -> {
                Result result = check(entry, run);
                if (result == null || current != run) {
                    return;
                }
                run.results.put(entry, result);
                if (run.left.decrementAndGet() == 0) {
                    OfflineClient.MC.execute(() -> {
                        if (current == run) {
                            done.run();
                        }
                    });
                }
            });
        }
    }

    public static boolean running() {
        Run run = current;
        return run != null && run.left.get() > 0;
    }

    public static String progress() {
        Run run = current;
        return run == null ? "" : "checked " + (run.total - run.left.get()) + " of " + Tally.counted(run.total, "proxy");
    }

    public static Result result(ProxyStore.Entry entry) {
        Run run = current;
        return run == null ? null : run.results.get(entry);
    }

    public static boolean isDead(ProxyStore.Entry entry) {
        Result result = result(entry);
        return result != null && result.state() == State.DEAD;
    }

    public static String detail(ProxyStore.Entry entry) {
        Result result = result(entry);
        if (result == null) {
            return null;
        }
        return switch (result.state()) {
            case CHECKING -> "checking";
            case ANSWERED -> result.millis() + " ms";
            case DEAD -> "dead";
        };
    }

    public static String sentence(ProxyStore.Entry entry) {
        Result result = result(entry);
        if (result == null) {
            return "";
        }
        return switch (result.state()) {
            case CHECKING -> "Being checked.";
            case ANSWERED -> "Took a connection in " + result.millis() + " ms when checked.";
            case DEAD -> "Dead after " + TRIES + " tries. " + result.why() + ".";
        };
    }

    public static String summary() {
        Run run = current;
        if (run == null) {
            return "";
        }
        ProxyStore.Entry quickest = null;
        long best = Long.MAX_VALUE;
        int answered = 0;
        for (Map.Entry<ProxyStore.Entry, Result> found : run.results.entrySet()) {
            Result result = found.getValue();
            if (result.state() == State.ANSWERED) {
                answered++;
                if (result.millis() < best) {
                    best = result.millis();
                    quickest = found.getKey();
                }
            }
        }
        String checked = Tally.counted(run.total, "proxy");
        if (quickest == null) {
            return "Not one of " + checked + " took a connection. Your own connection may be down";
        }
        return answered + " of " + checked + " took a connection and " + quickest.name() + " was quickest at "
            + best + " ms";
    }

    public static String prune() {
        String refused = pruneRefusal();
        if (refused != null) {
            return refused;
        }
        ProxyStore store = ProxyStore.get();
        int removed = store.removeAll(dead(store.all()));
        return removed == 0 ? "No saved proxy was found dead" : "Removed " + Tally.counted(removed, "dead proxy");
    }

    // Whilst not one proxy took a connection the fault is more likely your own connection than
    // every proxy.
    public static String pruneRefusal() {
        Run run = current;
        if (run == null) {
            return null;
        }
        boolean dead = false;
        for (Result result : run.results.values()) {
            if (result.state() == State.ANSWERED) {
                return null;
            }
            dead |= result.state() == State.DEAD;
        }
        return dead ? "Removed nothing. Not one proxy took a connection and your own connection may be down" : null;
    }

    // Whilst a check runs the order stays as it is and no row moves under the pointer.
    public static List<ProxyStore.Entry> ranked(List<ProxyStore.Entry> entries) {
        Run run = current;
        if (run == null || run.left.get() > 0) {
            return entries;
        }
        List<ProxyStore.Entry> ranked = new ArrayList<>(entries);
        ranked.sort(Comparator.comparingLong(entry -> rank(run.results.get(entry))));
        return ranked;
    }

    private static long rank(Result result) {
        if (result == null) {
            return LEFT_OUT_RANK;
        }
        return result.state() == State.ANSWERED ? result.millis() : DEAD_RANK;
    }

    public static List<ProxyStore.Entry> dead(List<ProxyStore.Entry> among) {
        List<ProxyStore.Entry> dead = new ArrayList<>();
        for (ProxyStore.Entry entry : among) {
            if (isDead(entry)) {
                dead.add(entry);
            }
        }
        return dead;
    }

    // Null once a newer check took over.
    private static Result check(ProxyStore.Entry entry, Run run) {
        String why = "";
        try {
            for (int tried = 0; tried < TRIES; tried++) {
                if (tried > 0) {
                    Thread.sleep(RETRY_MS);
                }
                if (current != run) {
                    return null;
                }
                run.room.acquire();
                try {
                    // A newer check may have taken over whilst this one waited for room.
                    if (current != run) {
                        return null;
                    }
                    return new Result(State.ANSWERED, connect(entry), "");
                } catch (IOException e) {
                    why = reason(e);
                } finally {
                    run.room.release();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return new Result(State.DEAD, 0, why);
    }

    // Finding the address is not counted. The connection goes straight out the way a join reaches
    // the proxy.
    private static long connect(ProxyStore.Entry entry) throws IOException {
        InetAddress address = InetAddress.getByName(entry.host());
        try (Socket socket = new Socket(java.net.Proxy.NO_PROXY)) {
            long start = System.nanoTime();
            socket.connect(new InetSocketAddress(address, entry.port()), TRY_MS);
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        }
    }

    private static String reason(IOException e) {
        if (e instanceof UnknownHostException) {
            return "Its address led nowhere";
        }
        if (e instanceof SocketTimeoutException) {
            return "It gave no answer within " + TRY_SECONDS + " seconds";
        }
        if (e instanceof ConnectException) {
            return "It turned the connection down";
        }
        return "It could not be reached";
    }
}
