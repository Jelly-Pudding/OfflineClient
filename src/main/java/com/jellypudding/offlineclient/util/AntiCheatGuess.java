package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// Guesses the anti cheat a server runs. Most of them ping the client to time its packets and
// each counts the ping ids its own way from its own start. Vanilla and Paper send no pings.
// A plugin can also give itself away by the commands it adds.
public enum AntiCheatGuess {
    INSTANCE;

    // A steady count of ping ids that one anti cheat starts somewhere in this window.
    private record Count(String name, int step, int lowestStart, int highestStart) {

        boolean matches(int step, int start) {
            return this.step == step && start >= lowestStart && start <= highestStart;
        }
    }

    private static final int PINGS = 5;

    private static final long SETTLE_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private static final List<Count> COUNTS = List.of(
        new Count("Grim", -1, -5, 0),
        new Count("Karhu", -1, -3005, -2995),
        new Count("Vulcan", 1, -23772, -23762),
        new Count("Matrix", 1, 95, 105),
        new Count("Matrix", 1, -20005, -19995));

    // A count that matches no window above still points somewhere. Intave counts down from
    // far below nought. Polar counts down from anywhere else and Verus counts up.
    private static final int INTAVE_BELOW = -3005;

    private static final Map<String, String> COMMAND_WORDS = Map.ofEntries(
        Map.entry("grim", "Grim"), Map.entry("grimac", "Grim"),
        Map.entry("vulcan", "Vulcan"), Map.entry("matrix", "Matrix"),
        Map.entry("spartan", "Spartan"), Map.entry("nocheatplus", "NoCheatPlus"),
        Map.entry("ncp", "NoCheatPlus"), Map.entry("themis", "Themis"),
        Map.entry("polar", "Polar"), Map.entry("intave", "Intave"),
        Map.entry("karhu", "Karhu"), Map.entry("verus", "Verus"));

    // Filled on the netty thread and read on the game thread.
    private final List<Integer> pings = new ArrayList<>();
    private long joinedAt;

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundLoginPacket _ -> joined();
            case ClientboundPingPacket ping -> heard(ping.getId());
            default -> {
            }
        }
    }

    private synchronized void joined() {
        pings.clear();
        joinedAt = System.currentTimeMillis();
    }

    private synchronized void heard(int id) {
        if (pings.size() < PINGS) {
            pings.add(id);
        }
    }

    public synchronized boolean settled() {
        return pings.size() >= PINGS || System.currentTimeMillis() - joinedAt >= SETTLE_MILLIS;
    }

    // Null until the pings are all in and for a count that is not steady.
    public synchronized String fromPings() {
        if (pings.size() < PINGS) {
            return null;
        }
        int step = pings.get(1) - pings.get(0);
        for (int i = 2; i < pings.size(); i++) {
            if (pings.get(i) - pings.get(i - 1) != step) {
                return null;
            }
        }
        int start = pings.getFirst();
        for (Count count : COUNTS) {
            if (count.matches(step, start)) {
                return count.name();
            }
        }
        return switch (step) {
            case -1 -> start < INTAVE_BELOW ? "Intave" : "Polar";
            case 1 -> "Verus";
            default -> null;
        };
    }

    // A command counts by its namespace.
    public static List<String> among(Collection<String> words) {
        Set<String> found = new LinkedHashSet<>();
        for (String word : words) {
            String name = word.toLowerCase(Locale.ROOT);
            int colon = name.indexOf(':');
            String known = COMMAND_WORDS.get(colon == -1 ? name : name.substring(0, colon));
            if (known != null) {
                found.add(known);
            }
        }
        return List.copyOf(found);
    }

    // Game thread only.
    public static List<String> fromCommands() {
        return among(ServerPlugins.roots());
    }

    // Game thread only.
    public String summary() {
        List<String> named = fromCommands();
        if (!named.isEmpty()) {
            return "§c" + String.join("§7 and §c", named) + "§7 going by "
                + (named.size() == 1 ? "its" : "their") + " commands";
        }
        String counted = fromPings();
        if (counted != null) {
            return "§c" + counted + "§7 going by its pings";
        }
        synchronized (this) {
            if (pings.size() >= PINGS) {
                return "§7an unknown one going by its pings";
            }
        }
        return settled() ? "§7none seen" : "§7still listening";
    }

    // Game thread only.
    public boolean found() {
        if (!fromCommands().isEmpty()) {
            return true;
        }
        synchronized (this) {
            return pings.size() >= PINGS;
        }
    }
}
