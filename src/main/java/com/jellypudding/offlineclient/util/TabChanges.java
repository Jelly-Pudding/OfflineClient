package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Players who join and leave the server as the tab list packets tell it. Everyone the server
// lists in the moments after you join was there already and is no news. A player the server
// keeps off the list comes and goes without a word.
public final class TabChanges {

    public record Change(String name, boolean joined) {
    }

    // One player the packets have named and whether the tab list shows them.
    private record Known(String name, boolean listed) {
    }

    // The server sends everyone already online in the moments after you join.
    private static final long BURST_MS = 2000;

    // The most players remembered. A server that lists more has the rest come and go unheard.
    private static final int MOST_KNOWN = 4096;

    // Written by the packets on the netty thread and by sync on the game thread.
    private final Map<UUID, Known> known = new ConcurrentHashMap<>();
    private volatile long burstUntil;

    // Takes everyone on the tab list as already there. For the game thread.
    public void sync() {
        known.clear();
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        if (connection == null) {
            return;
        }
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            if (known.size() >= MOST_KNOWN) {
                return;
            }
            known.put(info.getProfile().id(), new Known(info.getProfile().name(), true));
        }
    }

    // Who a packet says has joined or left. Empty for any other packet. Called on the netty thread.
    public List<Change> read(Packet<?> packet) {
        return switch (packet) {
            case ClientboundLoginPacket _ -> {
                known.clear();
                burstUntil = System.currentTimeMillis() + BURST_MS;
                yield List.of();
            }
            case ClientboundPlayerInfoUpdatePacket update -> updated(update);
            case ClientboundPlayerInfoRemovePacket remove -> removed(remove);
            default -> List.of();
        };
    }

    // The client lists a player only when the packet says to.
    private List<Change> updated(ClientboundPlayerInfoUpdatePacket packet) {
        EnumSet<Action> actions = packet.actions();
        boolean adds = actions.contains(Action.ADD_PLAYER);
        boolean lists = actions.contains(Action.UPDATE_LISTED);
        boolean burst = System.currentTimeMillis() < burstUntil;
        List<Change> changes = new ArrayList<>();
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            if (adds && entry.profile() != null) {
                if (known.size() >= MOST_KNOWN && !known.containsKey(entry.profileId())) {
                    continue;
                }
                boolean listed = lists && entry.listed();
                Known before = known.put(entry.profileId(), new Known(entry.profile().name(), listed));
                if (listed && before == null && !burst) {
                    changes.add(new Change(entry.profile().name(), true));
                }
            } else if (lists) {
                known.computeIfPresent(entry.profileId(), (id, was) -> new Known(was.name(), entry.listed()));
            }
        }
        return changes;
    }

    // A server takes a leaving player off the list on their own. A packet that takes several
    // off at once clears the list for a move between servers and nobody has left.
    private List<Change> removed(ClientboundPlayerInfoRemovePacket packet) {
        List<UUID> ids = packet.profileIds();
        if (ids.size() > 1) {
            ids.forEach(known::remove);
            return List.of();
        }
        List<Change> changes = new ArrayList<>();
        for (UUID id : ids) {
            Known gone = known.remove(id);
            if (gone != null && gone.listed()) {
                changes.add(new Change(gone.name(), false));
            }
        }
        return changes;
    }
}
