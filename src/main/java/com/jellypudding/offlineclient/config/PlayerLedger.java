package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.RegistryOps;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Every player met on the server you play on with what Dossier learnt about them. The records
// sit in a file beside the finds and come back on the next visit. The screen and the command
// read them whether or not Dossier is on.
public final class PlayerLedger extends ServerLedger<UUID, PlayerRecord> {

    public static final PlayerLedger INSTANCE = new PlayerLedger();

    private static final String FILE = "players.json";
    private static final String WHAT = "player records";
    // A busy server lists thousands of players in a month. The least recently seen make room.
    private static final int MAX_PLAYERS = 4096;

    // The players forgotten by hand on each server. Nothing more is recorded about them there
    // until the game restarts.
    private final Map<String, Set<UUID>> forgotten = new HashMap<>();

    private PlayerLedger() {
        super(FILE, MAX_PLAYERS, WHAT);
    }

    // The record of a player started the first time they turn up. Null whilst the file is
    // still being read and for a player forgotten by hand on this server. A tab list entry with
    // a blank name is never a player and gets none.
    public PlayerRecord recordOf(UUID id, String name, long now) {
        if (name.isBlank() || !ready() || forgotten.getOrDefault(server(), Set.of()).contains(id)) {
            return null;
        }
        PlayerRecord record = get(id);
        if (record == null) {
            record = new PlayerRecord(id, name, now);
            put(id, record);
        }
        return record;
    }

    // Takes the player out of this server's file and keeps them out of its record.
    public void forgetPlayer(UUID id) {
        String server = server();
        if (server != null) {
            forgotten.computeIfAbsent(server, _ -> new HashSet<>()).add(id);
        }
        forget(id);
    }

    // Every record of this server. Safe to keep whilst the records change.
    public List<PlayerRecord> all() {
        return List.copyOf(values());
    }

    // The player who goes by that name or once did. The one seen last wins. Null for nobody.
    public PlayerRecord named(String name) {
        Comparator<PlayerRecord> newest = Comparator.comparingLong(PlayerRecord::lastSeen);
        List<PlayerRecord> all = all();
        return all.stream().filter(record -> record.name().equalsIgnoreCase(name)).max(newest)
            .orElseGet(() -> all.stream().filter(record -> record.pastNames().stream()
                .anyMatch(past -> past.equalsIgnoreCase(name))).max(newest).orElse(null));
    }

    // The entry the tab list shows for the player. Null whilst they are not in it.
    public static PlayerInfo live(UUID id) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id);
        return info != null && connection.getListedOnlinePlayers().contains(info) ? info : null;
    }

    // One broken record is left out rather than the whole file.
    @Override
    protected void parse(JsonElement root, Map<UUID, PlayerRecord> into, RegistryOps<JsonElement> ops) {
        DataFiles.members(root, (id, saved) -> PlayerRecord.fromJson(UUID.fromString(id), saved.getAsJsonObject()),
            WHAT).values().forEach(record -> into.put(record.id(), record));
    }

    @Override
    protected JsonElement encode(Map<UUID, PlayerRecord> entries, RegistryOps<JsonElement> ops) {
        JsonObject root = new JsonObject();
        entries.forEach((id, record) -> root.add(id.toString(), record.toJson()));
        return root;
    }

    @Override
    protected PlayerRecord frozen(PlayerRecord record) {
        return record.copy();
    }
}
