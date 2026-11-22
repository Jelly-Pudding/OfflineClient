package com.jellypudding.offlineclient.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.InputUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

// The first of a kind is always there. All but the first are saved whole in one setting and the
// config never has to know how many there are.
public final class Families {

    // Most pieces of one kind. A save is never trusted to ask for more.
    private static final int MOST = 64;

    private static final int REMEMBERED = 32;

    private static final String SAVED_NUMBER = "number";
    private static final String SAVED_SETTINGS = "settings";
    private static final String SAVED_FOLDS = "folds";

    // An undo of an add takes the piece away again. Chat then says it brought back what the overlay had.
    private static final String UNDONE = "the overlay";

    public interface Teller {

        void done(String verb, String name, Defaults.Change change);

        void refused(String why);
    }

    private static final class Family {

        private final String name;
        private final int usual;
        private final IntFunction<HudElement> maker;
        private final NavigableMap<Integer, Member> members = new TreeMap<>();

        private Family(String name, int usual, IntFunction<HudElement> maker) {
            this.name = name;
            this.usual = usual;
            this.maker = maker;
        }

        private String noun() {
            return name.toLowerCase(Locale.ROOT);
        }

        // Nought when the kind is full.
        private int freeNumber() {
            for (int number = 1; number <= MOST; number++) {
                if (!members.containsKey(number)) {
                    return number;
                }
            }
            return 0;
        }
    }

    // The serial tells it from any other piece that ever had its number.
    private record Member(Family family, int number, int serial, HudElement piece) {

        private boolean added() {
            return number > 1;
        }
    }

    private final HudManager manager;
    private final Teller teller;
    private final List<Family> families = new ArrayList<>();
    // An undo puts the very same piece back.
    private final Map<Integer, Member> removed = new BoundedMap<>(REMEMBERED);
    private int nextSerial = 1;

    // A save without a kind leaves that kind as it stands.
    private final Setting<Void> store = new Setting<Void>("Added pieces",
        "The pieces added to the overlay and what each holds.", null) {

        @Override
        public JsonElement toJson() {
            JsonObject saved = new JsonObject();
            for (Family family : families) {
                JsonArray pieces = new JsonArray();
                for (Member member : family.members.values()) {
                    if (member.added()) {
                        pieces.add(write(member));
                    }
                }
                saved.add(family.name, pieces);
            }
            return saved;
        }

        @Override
        public void fromJson(JsonElement json) {
            if (!json.isJsonObject()) {
                return;
            }
            JsonObject saved = json.getAsJsonObject();
            for (Family family : families) {
                JsonElement pieces = saved.get(family.name);
                if (pieces != null && pieces.isJsonArray()) {
                    match(family, pieces.getAsJsonArray());
                }
            }
        }

        // Back to the pieces a fresh install has. Nothing removed before can come back.
        @Override
        public void reset() {
            removed.clear();
            for (Family family : families) {
                for (Member member : List.copyOf(family.members.values())) {
                    if (member.added()) {
                        detach(member);
                    }
                }
                for (int number = 2; number <= family.usual; number++) {
                    attach(make(family, number));
                }
            }
        }
    }.internal();

    // Never saved and never listed. An undo of an add or a removal goes through it.
    private final Setting<Void> present = new Setting<Void>("HUD pieces",
        "Which added pieces are on the overlay.", null) {

        @Override
        public JsonElement toJson() {
            JsonArray serials = new JsonArray();
            added().stream().map(Member::serial).sorted().forEach(serials::add);
            return serials;
        }

        @Override
        public void fromJson(JsonElement json) {
            Set<Integer> wanted = new HashSet<>();
            for (JsonElement serial : json.getAsJsonArray()) {
                wanted.add(serial.getAsInt());
            }
            for (Member member : added()) {
                if (!wanted.remove(member.serial())) {
                    take(member);
                }
            }
            for (int serial : wanted) {
                Member back = removed.remove(serial);
                if (back != null && !back.family().members.containsKey(back.number())) {
                    attach(back);
                }
            }
        }
    };

    public Families(HudManager manager, Teller teller) {
        this.manager = manager;
        this.teller = teller;
    }

    // The maker is handed the number of the piece to build.
    public List<HudElement> kind(String name, int usual, IntFunction<HudElement> maker) {
        Family family = new Family(name, usual, maker);
        families.add(family);
        List<HudElement> made = new ArrayList<>();
        for (int number = 1; number <= usual; number++) {
            Member member = make(family, number);
            family.members.put(number, member);
            made.add(member.piece());
        }
        return made;
    }

    public Setting<?> store() {
        return store;
    }

    public List<String> nouns() {
        return families.stream().map(Family::noun).toList();
    }

    public boolean add(String noun) {
        String wanted = Setting.idFor(noun);
        for (Family family : families) {
            if (Setting.idFor(family.noun()).equals(wanted)) {
                add(family);
                return true;
            }
        }
        return false;
    }

    public boolean remove(HudElement piece) {
        for (Member member : added()) {
            if (member.piece() == piece) {
                remove(member);
                return true;
            }
        }
        return false;
    }

    public List<HudElement> removable() {
        return added().stream().map(Member::piece).toList();
    }

    private List<Member> added() {
        List<Member> added = new ArrayList<>();
        for (Family family : families) {
            for (Member member : family.members.values()) {
                if (member.added()) {
                    added.add(member);
                }
            }
        }
        return added;
    }

    // A piece the player asks for comes switched on. It then seats itself clear of the rest.
    private void add(Family family) {
        int number = family.freeNumber();
        if (number == 0) {
            teller.refused("The overlay holds no more than " + MOST + " of a kind.");
            return;
        }
        Member member = make(family, number);
        teller.done("Added", member.piece().getName(), Defaults.record(UNDONE, present, () -> {
            attach(member);
            member.piece().setActive(true);
        }));
    }

    private void remove(Member member) {
        teller.done("Removed", member.piece().getName(), Defaults.record(UNDONE, present, () -> take(member)));
    }

    private void take(Member member) {
        removed.put(member.serial(), member);
        detach(member);
    }

    private Member make(Family family, int number) {
        HudElement piece = family.maker.apply(number);
        if (number > 1 && !piece.getName().equals(family.name + " " + number)) {
            throw new IllegalStateException(piece.getName() + " is not piece " + number + " of " + family.name);
        }
        Member member = new Member(family, number, nextSerial++, piece);
        piece.addLast(number == 1 ? addRow(family) : removeRow(member));
        return member;
    }

    private ActionSetting addRow(Family family) {
        String noun = family.noun();
        return new ActionSetting("Add " + noun,
            "Adds another " + noun + " to the overlay with settings and a place of its own.", () -> add(family));
    }

    private ActionSetting removeRow(Member member) {
        return new ActionSetting("Remove " + member.piece().getName().toLowerCase(Locale.ROOT),
            "Takes this piece off the overlay. " + InputUtil.undoKeys() + " brings it back.",
            () -> remove(member));
    }

    private void attach(Member member) {
        NavigableMap<Integer, Member> members = member.family().members;
        manager.addAfter(members.lowerEntry(member.number()).getValue().piece(), member.piece());
        members.put(member.number(), member);
    }

    private void detach(Member member) {
        member.family().members.remove(member.number());
        manager.remove(member.piece());
        member.piece().discard();
    }

    private void match(Family family, JsonArray pieces) {
        Map<Integer, JsonObject> wanted = new TreeMap<>();
        for (JsonElement entry : pieces) {
            int number = numberOf(entry);
            if (number > 1 && number <= MOST) {
                wanted.putIfAbsent(number, entry.getAsJsonObject());
            }
        }
        for (Member member : List.copyOf(family.members.values())) {
            if (member.added() && !wanted.containsKey(member.number())) {
                detach(member);
            }
        }
        wanted.forEach((number, saved) -> {
            Member member = family.members.get(number);
            if (member == null) {
                member = make(family, number);
                attach(member);
            }
            read(member.piece(), saved);
        });
    }

    private static int numberOf(JsonElement entry) {
        JsonElement number = entry.isJsonObject() ? entry.getAsJsonObject().get(SAVED_NUMBER) : null;
        try {
            return number == null ? 0 : number.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static JsonObject write(Member member) {
        JsonObject settings = new JsonObject();
        JsonObject folds = new JsonObject();
        for (Setting<?> setting : member.piece().getSettings()) {
            if (setting.saves()) {
                settings.add(setting.getName(), setting.toJson());
            }
            if (setting.foldChanged()) {
                folds.addProperty(setting.getName(), setting.isFolded());
            }
        }
        JsonObject saved = new JsonObject();
        saved.addProperty(SAVED_NUMBER, member.number());
        saved.add(SAVED_SETTINGS, settings);
        saved.add(SAVED_FOLDS, folds);
        return saved;
    }

    private static void read(HudElement piece, JsonObject saved) {
        try {
            JsonObject settings = objectIn(saved, SAVED_SETTINGS);
            JsonObject folds = objectIn(saved, SAVED_FOLDS);
            for (Setting<?> setting : piece.getSettings()) {
                JsonElement value = setting.saves() ? settings.get(setting.getName()) : null;
                if (value != null) {
                    setting.fromJson(value);
                }
                JsonElement folded = folds.get(setting.getName());
                if (folded != null) {
                    setting.setFolded(folded.getAsBoolean());
                }
            }
        } catch (RuntimeException e) {
            OfflineClient.LOG.error("Failed to apply the saved state of {}", piece.getName(), e);
        }
    }

    private static JsonObject objectIn(JsonObject saved, String key) {
        JsonElement found = saved.get(key);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }
}
