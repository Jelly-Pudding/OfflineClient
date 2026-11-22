package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class PlayerRecord {

    public record Place(BlockPos pos, String dimension, long time) {

        private String text() {
            return BlockUtil.text(pos) + " " + dimension;
        }

        private static Place parse(String text, long time) {
            int split = text.lastIndexOf(' ');
            BlockPos pos = split < 0 ? null : BlockUtil.parse(text.substring(0, split));
            return pos == null ? null : new Place(pos, text.substring(split + 1), time);
        }
    }

    public enum Kind { JOINED, LEFT, SEEN, POPPED, DIED, MODE, RENAMED }

    public record Event(long time, Kind kind, String detail) {

        public Place place() {
            return kind == Kind.SEEN || kind == Kind.DIED ? Place.parse(detail, time) : null;
        }

        // Only a hand edited file holds a place or a count that cannot be read back.
        private boolean readable() {
            try {
                return switch (kind) {
                    case SEEN -> place() != null;
                    case DIED -> detail.isEmpty() || place() != null;
                    case POPPED -> Integer.parseInt(detail) > 0;
                    default -> true;
                };
            } catch (NumberFormatException e) {
                return false;
            }
        }

        private String words() {
            return switch (kind) {
                case JOINED -> "Joined";
                case LEFT -> "Left";
                case SEEN -> "Seen at";
                case POPPED -> "Popped " + (detail.equals("1") ? "a totem" : detail + " totems");
                case DIED -> place() == null ? "Died" : "Died at";
                case MODE -> "Switched to " + detail;
                case RENAMED -> "Changed name from " + detail;
            };
        }
    }

    public record Fact(String text, String detail, Place place) {

        public String plain() {
            String coordinates = place == null ? "" : " " + BlockUtil.text(place.pos());
            return text + coordinates + (detail.isEmpty() ? "" : SEPARATOR + detail);
        }
    }

    public static final List<EquipmentSlot> GEAR_SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
        EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);

    private static final int MAX_EVENTS = 20;
    private static final int MAX_NAMES = 8;
    private static final int MAX_HELD = 12;
    // Pops this close together share one line of the log.
    private static final long POP_RUN_MS = TimeUnit.MINUTES.toMillis(1);
    // A player back in view this long after they were last seen goes in the log again.
    private static final long SIGHTING_GAP_MS = TimeUnit.MINUTES.toMillis(5);
    private static final String SEPARATOR = " · ";

    private final UUID id;
    private String name;
    private final List<String> pastNames = new ArrayList<>();
    private final long firstSeen;
    private long lastOnline;
    private int joins;
    private long lastJoin;
    private long lastLeave;
    // Below nought until the tab list gives a figure.
    private int ping = -1;
    private String mode = "";
    private Place place;
    private List<String> gear = List.of();
    private final List<String> held = new ArrayList<>();
    private int pops;
    private int deaths;
    private final List<Event> events = new ArrayList<>();

    public PlayerRecord(UUID id, String name, long firstSeen) {
        this.id = id;
        this.name = name;
        this.firstSeen = firstSeen;
    }

    // A copy the file thread may read whilst this one goes on changing.
    public PlayerRecord copy() {
        PlayerRecord copy = new PlayerRecord(id, name, firstSeen);
        copy.pastNames.addAll(pastNames);
        copy.lastOnline = lastOnline;
        copy.joins = joins;
        copy.lastJoin = lastJoin;
        copy.lastLeave = lastLeave;
        copy.ping = ping;
        copy.mode = mode;
        copy.place = place;
        copy.gear = gear;
        copy.held.addAll(held);
        copy.pops = pops;
        copy.deaths = deaths;
        copy.events.addAll(events);
        return copy;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> pastNames() {
        return List.copyOf(pastNames);
    }

    public long lastOnline() {
        return lastOnline;
    }

    public Place place() {
        return place;
    }

    public long lastSeen() {
        return Math.max(lastOnline, place == null ? 0 : place.time());
    }

    public boolean rename(String newName, long now) {
        if (newName.isEmpty() || newName.equals(name)) {
            return false;
        }
        pastNames.remove(newName);
        pastNames.addFirst(name);
        trim(pastNames, MAX_NAMES);
        log(now, Kind.RENAMED, name);
        name = newName;
        return true;
    }

    // The first mode seen goes in without a line in the log.
    public boolean mode(String newMode, long now) {
        if (newMode.equals(mode)) {
            return false;
        }
        if (!mode.isEmpty()) {
            log(now, Kind.MODE, newMode);
        }
        mode = newMode;
        return true;
    }

    // The ping changes too often to be worth a write of its own.
    public void ping(int latency) {
        ping = latency;
    }

    public void online(long now) {
        lastOnline = now;
    }

    public void joined(long now) {
        joins++;
        lastJoin = now;
        lastOnline = now;
        log(now, Kind.JOINED, "");
    }

    public void left(long now) {
        lastLeave = now;
        lastOnline = now;
        log(now, Kind.LEFT, "");
    }

    public boolean seen(Place at, boolean cameIntoView) {
        Place before = place;
        place = at;
        boolean fresh = cameIntoView && (before == null || !before.dimension().equals(at.dimension())
            || at.time() - before.time() >= SIGHTING_GAP_MS);
        if (fresh) {
            log(at.time(), Kind.SEEN, at.text());
        }
        return fresh;
    }

    public boolean wear(List<String> pieces) {
        List<String> kept = pieces.stream().allMatch(String::isEmpty) ? List.of() : List.copyOf(pieces);
        if (kept.equals(gear)) {
            return false;
        }
        gear = kept;
        return true;
    }

    public boolean hold(List<String> items) {
        List<String> before = List.copyOf(held);
        for (String item : items.reversed()) {
            held.remove(item);
            held.addFirst(item);
        }
        trim(held, MAX_HELD);
        return !held.equals(before);
    }

    public void popped(long now) {
        pops++;
        Event last = events.isEmpty() ? null : events.getFirst();
        if (last != null && last.kind() == Kind.POPPED && now - last.time() < POP_RUN_MS) {
            events.set(0, new Event(now, Kind.POPPED, String.valueOf(Integer.parseInt(last.detail()) + 1)));
        } else {
            log(now, Kind.POPPED, "1");
        }
    }

    public void died(Place at, long now) {
        deaths++;
        log(now, Kind.DIED, at == null ? "" : at.text());
    }

    private void log(long time, Kind kind, String detail) {
        events.addFirst(new Event(time, kind, detail));
        trim(events, MAX_EVENTS);
    }

    private static void trim(List<?> list, int max) {
        while (list.size() > max) {
            list.removeLast();
        }
    }

    public List<Fact> facts(PlayerInfo live, long now) {
        List<Fact> facts = new ArrayList<>();
        facts.add(status(live, now));
        facts.add(new Fact("First met " + ChatUtil.ago(now - firstSeen), DataFiles.timestamp(firstSeen), null));
        if (joins > 0) {
            facts.add(new Fact("Seen joining " + Tally.counted(joins, "time"), "last time "
                + ChatUtil.ago(now - lastJoin) + SEPARATOR + DataFiles.timestamp(lastJoin), null));
        }
        if (place != null) {
            facts.add(new Fact("Last seen at", where(place, now), place));
        }
        for (int i = 0; i < Math.min(gear.size(), GEAR_SLOTS.size()); i++) {
            Fact piece = piece(gear.get(i), GEAR_SLOTS.get(i));
            if (piece != null) {
                facts.add(piece);
            }
        }
        if (!held.isEmpty()) {
            facts.add(new Fact("Seen holding", String.join(SEPARATOR, held.stream().map(PlayerRecord::itemWords)
                .toList()), null));
        }
        if (pops > 0 || deaths > 0) {
            facts.add(new Fact(Tally.counted(pops, "totem") + " popped and " + Tally.counted(deaths, "death"),
                "counted whilst they were in sight", null));
        }
        if (!pastNames.isEmpty()) {
            facts.add(new Fact("Once called " + pastNames.getFirst(), pastNames.size() > 1
                ? "before that " + String.join(SEPARATOR, pastNames.subList(1, pastNames.size())) : "", null));
        }
        return facts;
    }

    private Fact status(PlayerInfo live, long now) {
        if (live != null) {
            int latency = ServerInfo.latency(live);
            return new Fact("Online now", (latency < 0 ? "no ping yet" : latency + " ms ping") + SEPARATOR
                + live.getGameMode().getName(), null);
        }
        if (lastOnline == 0) {
            return new Fact("Never seen in the tab list", "", null);
        }
        String left = lastLeave == lastOnline ? "left at " : "still online at ";
        return new Fact("Last seen online " + ChatUtil.ago(now - lastOnline),
            left + DataFiles.timestamp(lastOnline) + (ping < 0 || mode.isEmpty() ? ""
                : SEPARATOR + ping + " ms ping" + SEPARATOR + mode), null);
    }

    private static Fact piece(String piece, EquipmentSlot slot) {
        if (piece.isEmpty()) {
            return null;
        }
        String[] parts = piece.split(" ", 2);
        boolean worn = slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
        return new Fact((worn ? "Wears " : "Holds ") + ChatUtil.withArticle(itemWords(parts[0])),
            ItemUtil.slotWords(slot) + (parts.length > 1 ? SEPARATOR + parts[1] : ""), null);
    }

    private static String itemWords(String id) {
        return ChatUtil.words(Identifier.tryParse(id));
    }

    // The distance only shows in the dimension you are in.
    public static String where(Place at, long now) {
        StringBuilder text = new StringBuilder(ServerInfo.dimensionName(at.dimension()));
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null && at.dimension().equals(ServerInfo.dimension())) {
            double away = player.position().distanceTo(Vec3.atCenterOf(at.pos()));
            text.append(SEPARATOR).append(Math.round(away)).append(" blocks away");
        }
        return text.append(SEPARATOR).append(ChatUtil.ago(now - at.time())).toString();
    }

    public List<Fact> history(long now) {
        List<Fact> lines = new ArrayList<>();
        for (Event event : events) {
            Place at = event.place();
            String when = ChatUtil.ago(now - event.time()) + SEPARATOR + DataFiles.timestamp(event.time());
            lines.add(new Fact(event.words(), at == null ? when
                : ServerInfo.dimensionName(at.dimension()) + SEPARATOR + when, at));
        }
        return lines;
    }

    public String plainText(PlayerInfo live, long now) {
        List<String> lines = new ArrayList<>();
        lines.add(name + " " + id);
        facts(live, now).forEach(fact -> lines.add(fact.plain()));
        history(now).forEach(fact -> lines.add(fact.plain()));
        return String.join("\n", lines);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("first", firstSeen);
        putIfSet(json, "online", lastOnline);
        putIfSet(json, "joins", joins);
        putIfSet(json, "joined", lastJoin);
        putIfSet(json, "left", lastLeave);
        if (ping >= 0) {
            json.addProperty("ping", ping);
        }
        if (!mode.isEmpty()) {
            json.addProperty("mode", mode);
        }
        if (place != null) {
            json.addProperty("place", place.text() + " " + place.time());
        }
        putIfAny(json, "names", pastNames);
        putIfAny(json, "gear", gear);
        putIfAny(json, "held", held);
        putIfSet(json, "pops", pops);
        putIfSet(json, "deaths", deaths);
        putIfAny(json, "log", events.stream()
            .map(event -> (event.time() + " " + event.kind().name().toLowerCase(Locale.ROOT) + " "
                + event.detail()).trim()).toList());
        return json;
    }

    public static PlayerRecord fromJson(UUID id, JsonObject json) {
        PlayerRecord record = new PlayerRecord(id, json.get("name").getAsString(), json.get("first").getAsLong());
        record.lastOnline = number(json, "online");
        record.joins = (int) number(json, "joins");
        record.lastJoin = number(json, "joined");
        record.lastLeave = number(json, "left");
        record.ping = json.has("ping") ? json.get("ping").getAsInt() : -1;
        record.mode = json.has("mode") ? json.get("mode").getAsString() : "";
        if (json.has("place")) {
            String text = json.get("place").getAsString();
            int split = text.lastIndexOf(' ');
            record.place = Place.parse(text.substring(0, split), Long.parseLong(text.substring(split + 1)));
        }
        record.pastNames.addAll(strings(json, "names"));
        record.gear = List.copyOf(strings(json, "gear"));
        record.held.addAll(strings(json, "held"));
        record.pops = (int) number(json, "pops");
        record.deaths = (int) number(json, "deaths");
        for (String line : strings(json, "log")) {
            String[] parts = line.split(" ", 3);
            Event event = new Event(Long.parseLong(parts[0]), Kind.valueOf(parts[1].toUpperCase(Locale.ROOT)),
                parts.length > 2 ? parts[2] : "");
            if (event.readable()) {
                record.events.add(event);
            }
        }
        return record;
    }

    private static void putIfSet(JsonObject json, String key, long value) {
        if (value != 0) {
            json.addProperty(key, value);
        }
    }

    private static void putIfAny(JsonObject json, String key, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        json.add(key, array);
    }

    private static long number(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsLong() : 0;
    }

    private static List<String> strings(JsonObject json, String key) {
        if (!json.has(key)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) {
            values.add(element.getAsString());
        }
        return values;
    }
}
