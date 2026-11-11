package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

// The names you join offline servers with saved to offlineclient/names.json. Whilst none is in use
// you join with your account name. Names match whatever their case.
public final class NameStore {

    public static final String RULE = "A name has 3 to 16 letters or digits or underscores.";

    // Typed in place of a name it makes one up.
    public static final String RANDOM = "random";

    // Every server takes these. Account names follow the same rule.
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{3,16}");

    // The most names a save of a whole server fills the list to. Joining as a new name still
    // saves it and the file keeps this many of the newest.
    public static final int MOST_SAVED = 256;

    private static final String CONSONANTS = "bcdfghjklmnprstvz";
    private static final String VOWELS = "aeiou";
    private static final int RANDOM_LENGTH = 8;

    private static final String NAMES = "names";
    private static final String USE = "use";

    private record Saved(List<String> names, String inUse) {
    }

    private static NameStore instance;

    private final Path file;
    private final List<String> names = new ArrayList<>();
    private String inUse;
    // The saved name a server got after it checked your account. Such a server takes no other name
    // than the account one. Null once the login goes through and as each join starts.
    private String checked;

    private NameStore(Path file) {
        this.file = file;
        DataFiles.readJson(file, NameStore::decode).ifPresent(saved -> {
            names.addAll(saved.names());
            inUse = saved.inUse() == null ? null : find(saved.inUse());
        });
    }

    public static synchronized NameStore get() {
        if (instance == null) {
            instance = new NameStore(DataFiles.path("names.json"));
        }
        return instance;
    }

    public static String accountName() {
        return OfflineClient.MC.getUser().getName();
    }

    // Your name in the world you play. Outside a world it is the name the next join sends.
    public static String ownName() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? get().joinName() : player.getGameProfile().name();
    }

    public static boolean valid(String name) {
        return VALID.matcher(name).matches();
    }

    // The name a typed word asks for. Null for a word no server would take.
    public static String named(String word) {
        String name = word.equalsIgnoreCase(RANDOM) ? random() : word;
        return valid(name) ? name : null;
    }

    // Eight letters that read like a word such as Tavokemu.
    public static String random() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            String letters = i % 2 == 0 ? CONSONANTS : VOWELS;
            name.append(letters.charAt(random.nextInt(letters.length())));
        }
        return ChatUtil.capitalised(name.toString());
    }

    public synchronized List<String> saved() {
        return List.copyOf(names);
    }

    // Null whilst you join with your account name. The thread that connects reads it.
    public synchronized String inUse() {
        return inUse;
    }

    // The name the next join sends.
    public synchronized String joinName() {
        return inUse == null ? accountName() : inUse;
    }

    // Called once your account has passed the check the server asked for.
    public synchronized void accountChecked() {
        checked = inUse;
    }

    public synchronized void clearChecked() {
        checked = null;
    }

    // The saved name a server that checks accounts turned away. Only read once the join has failed.
    public synchronized String checkedName() {
        return checked;
    }

    // Saves the name and joins with it from the next join. A saved name takes the case typed this
    // time. Your account name puts you back on it.
    public synchronized void use(String name) {
        if (name.equalsIgnoreCase(accountName())) {
            inUse = null;
        } else {
            names.removeIf(saved -> saved.equalsIgnoreCase(name));
            names.add(name);
            inUse = name;
        }
        write();
    }

    public synchronized void reset() {
        inUse = null;
        write();
    }

    // Saves names without joining as any of them and hands back how many were new. Your account
    // name is never saved and nothing goes in once the list is full.
    public synchronized int add(Collection<String> added) {
        int before = names.size();
        for (String name : added) {
            if (names.size() < MOST_SAVED && find(name) == null && !name.equalsIgnoreCase(accountName())) {
                names.add(name);
            }
        }
        if (names.size() > before) {
            write();
        }
        return names.size() - before;
    }

    public synchronized boolean isFull() {
        return names.size() >= MOST_SAVED;
    }

    // Forgetting the name in use puts you back on your account name.
    public synchronized boolean remove(String name) {
        boolean removed = names.removeIf(saved -> saved.equalsIgnoreCase(name));
        if (removed) {
            if (inUse != null && inUse.equalsIgnoreCase(name)) {
                inUse = null;
            }
            write();
        }
        return removed;
    }

    // The name after the one in use. The saved names come in turn and your account name follows
    // the last of them. Null whilst nothing is saved.
    public synchronized String next() {
        if (names.isEmpty()) {
            return null;
        }
        int after = inUse == null ? 0 : names.indexOf(inUse) + 1;
        return after < names.size() ? names.get(after) : accountName();
    }

    // The saved spelling of a name. Null when it is not saved.
    private String find(String name) {
        for (String saved : names) {
            if (saved.equalsIgnoreCase(name)) {
                return saved;
            }
        }
        return null;
    }

    // A name joined as moves to the end. The newest names are kept and the one in use with them.
    private static Saved decode(JsonElement root) {
        JsonObject object = root.getAsJsonObject();
        Deque<String> decoded = new ArrayDeque<>();
        for (String name : DataFiles.entries(object.get(NAMES), JsonElement::getAsString, "saved names")) {
            if (valid(name)) {
                decoded.addLast(name);
                if (decoded.size() > MOST_SAVED) {
                    decoded.removeFirst();
                }
            }
        }
        return new Saved(List.copyOf(decoded), JsonFields.text(object, USE));
    }

    private void write() {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        names.forEach(list::add);
        root.add(NAMES, list);
        if (inUse != null) {
            root.addProperty(USE, inUse);
        }
        DataFiles.writeJson(file, root);
    }
}
