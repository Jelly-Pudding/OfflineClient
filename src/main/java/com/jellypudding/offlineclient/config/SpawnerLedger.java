package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.resources.RegistryOps;

import java.util.LinkedHashMap;
import java.util.Map;

// What each spawner on the server you play on showed the last time it was read and how long
// you stood within its reach since. A reading that moved further than your own time there
// explains was moved by someone else. Spots you came near or dug in are remembered for good.
// The spots of every dimension share one file beside the finds.
public final class SpawnerLedger extends ServerLedger<SpawnerLedger.Spot, SpawnerLedger.Entry> {

    // A spawner's count or a trial spawner's state when it was last read and the ticks you
    // stood within its reach since. Near says you came within reach of the spot at any time.
    public record Entry(int reading, int ticksNear, boolean near) {

        // False for a spot you came near or dug in whose spawner was never read.
        public boolean hasReading() {
            return reading != NO_READING;
        }
    }

    // Where a spawner stands. The same position in another dimension is another spot.
    record Spot(String dimension, long pos) {
    }

    private static final String FILE = "spawnerreadings.json";
    private static final String WHAT = "spawner readings";
    private static final int NO_READING = Integer.MIN_VALUE;
    // Far more spawners than a hunt passes in every dimension together. It only bounds the
    // memory and the file.
    private static final int MAX_SPOTS = 65_536;
    private static final String NEAR = "1";
    private static final String NEVER_NEAR = "0";

    public SpawnerLedger() {
        super(FILE, MAX_SPOTS, WHAT);
    }

    // Null for a spot never read or visited in the dimension you are in.
    public Entry get(long pos) {
        Spot spot = here(pos);
        return spot == null ? null : get(spot);
    }

    // A new reading starts the time near afresh. Near stays once it was set.
    public void read(long pos, int reading, int ticksNear, boolean near) {
        Spot spot = here(pos);
        if (spot != null) {
            Entry old = get(spot);
            put(spot, new Entry(reading, ticksNear, near || old != null && old.near()));
        }
    }

    // One more tick near a spawner or one more dig in a room since its last reading.
    public void cameNear(long pos) {
        Spot spot = here(pos);
        if (spot != null) {
            Entry old = get(spot);
            put(spot, old == null ? new Entry(NO_READING, 1, true)
                : new Entry(old.reading(), old.ticksNear() + 1, true));
        }
    }

    // Someone took a spawner whilst you stood away. Your visits before cannot explain it.
    public void takenByOthers(long pos) {
        Spot spot = here(pos);
        Entry old = spot == null ? null : get(spot);
        if (old != null && old.ticksNear() > 0) {
            put(spot, new Entry(old.reading(), 0, old.near()));
        }
    }

    // The spot in the dimension you are in. Null outside a world.
    private static Spot here(long pos) {
        String dimension = ServerInfo.dimension();
        return dimension.isEmpty() ? null : new Spot(dimension, pos);
    }

    // A line or a dimension that cannot be read only loses itself.
    @Override
    protected void parse(JsonElement root, Map<Spot, Entry> into, RegistryOps<JsonElement> ops) {
        DataFiles.members(root, (dimension, lines) -> DataFiles.entries(lines, SpawnerLedger::entry, WHAT),
                "dimensions of " + WHAT)
            .forEach((dimension, read) -> read.forEach(line -> into.put(new Spot(dimension, line.getKey()),
                line.getValue())));
    }

    // The position of a spot and what it showed.
    private static Map.Entry<Long, Entry> entry(JsonElement line) {
        String[] parts = line.getAsString().split(" ");
        return Map.entry(Long.parseLong(parts[0]),
            new Entry(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), NEAR.equals(parts[3])));
    }

    // One line for each spot such as its position and its reading and the ticks you stood near
    // since and whether you ever went near. The lines of a dimension go together.
    @Override
    protected JsonElement encode(Map<Spot, Entry> readings, RegistryOps<JsonElement> ops) {
        Map<String, JsonArray> byDimension = new LinkedHashMap<>();
        readings.forEach((spot, entry) -> byDimension.computeIfAbsent(spot.dimension(), _ -> new JsonArray())
            .add(spot.pos() + " " + entry.reading() + " " + entry.ticksNear() + " "
                + (entry.near() ? NEAR : NEVER_NEAR)));
        JsonObject root = new JsonObject();
        byDimension.forEach(root::add);
        return root;
    }
}
