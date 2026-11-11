package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.config.ProxyText.Typed;
import com.jellypudding.offlineclient.util.ProxyTunnel;
import com.jellypudding.offlineclient.util.Tally;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The proxies you join through saved to offlineclient/proxylist.json with the one picked. Their
// logins are kept apart in proxies.json under the name of each proxy. Names match whatever their
// case and a proxy saved under a name takes the place of the one there.
public final class ProxyStore {

    // One saved proxy.
    public record Entry(String name, ProxyTunnel.Kind kind, String host, int port) {

        // The host and port the way proxy lists write them. An IPv6 host goes in brackets.
        public String address() {
            return ProxyText.address(host, port);
        }
    }

    // The file an import reads and the kind its proxies are without a word of their own.
    public record Request(String file, ProxyTunnel.Kind kind) {

        // Words such as my list.txt socks4. Either part can be left out.
        public static Request of(String words) {
            List<String> parts = new ArrayList<>(List.of(ProxyText.WHITESPACE.split(words.strip())));
            ProxyTunnel.Kind kind = ProxyText.KIND_WORDS.get(parts.getLast().toLowerCase(Locale.ROOT));
            if (kind != null) {
                parts.removeLast();
            }
            String file = String.join(" ", parts).strip();
            return new Request(file.isEmpty() ? DEFAULT_FILE : file, kind == null ? ProxyText.DEFAULT_KIND : kind);
        }

        // The file name without the folders before it.
        public String shownFile() {
            return file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        }
    }

    // What an import came to. A problem stands for a file that could not be read at all.
    public record Imported(String file, int added, int known, int unread, int leftOut, String problem) {

        private static Imported failed(String problem) {
            return new Imported("", 0, 0, 0, 0, problem);
        }

        public boolean addedAny() {
            return added > 0;
        }

        // What happened as a few sentences.
        public String words() {
            if (problem != null) {
                return problem;
            }
            if (added == 0 && unread == 0 && leftOut == 0) {
                return known == 0 ? file + " holds no proxy." : "Every proxy in " + file + " is saved already.";
            }
            StringBuilder words = new StringBuilder(added == 0 ? "Added no proxy from " + file + "."
                : "Added " + Tally.counted(added, "proxy") + " from " + file + ".");
            if (known > 0) {
                words.append(' ').append(Tally.counted(known, "proxy")).append(known == 1 ? " was" : " were")
                    .append(" saved already.");
            }
            if (unread > 0) {
                words.append(' ').append(Tally.counted(unread, "line")).append(" held no proxy.");
            }
            if (leftOut > 0) {
                words.append(" The list holds at most ").append(MOST_SAVED).append(" and ")
                    .append(leftOut).append(" more did not fit.");
            }
            return words.toString();
        }
    }

    private record Saved(List<Entry> entries, String picked) {
    }

    // An address and the login that goes with it. Hosts match whatever their case.
    private record Held(String host, int port, ProxyTunnel.Login login) {

        private Held {
            host = host.toLowerCase(Locale.ROOT);
        }
    }

    public static final int MOST_SAVED = 1000;
    public static final String DEFAULT_FILE = "proxies.txt";

    private static final String LIST = "proxies";
    private static final String USE = "use";
    private static final String NAME = "name";
    private static final String KIND = "type";
    private static final String HOST = "host";
    private static final String PORT = "port";

    // Far more than any list of proxies a player keeps.
    private static final long LARGEST_FILE = 1L << 20;
    // A line of a list that starts with this is a remark.
    private static final String REMARK = "#";
    // Two proxies with the same address get a number after the name. The second gets two.
    private static final String NUMBERED = "#";
    private static final int FIRST_NUMBER = 2;

    private static ProxyStore instance;

    private final Path file;
    // The proxies in the order they were saved under the lower case of their names.
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private String picked;

    private ProxyStore(Path file) {
        this.file = file;
        DataFiles.readJson(file, ProxyStore::decode).ifPresent(saved -> {
            saved.entries().forEach(entry -> entries.put(key(entry.name()), entry));
            Entry found = saved.picked() == null ? null : find(saved.picked());
            picked = found == null ? null : found.name();
        });
    }

    public static synchronized ProxyStore get() {
        if (instance == null) {
            instance = new ProxyStore(DataFiles.path("proxylist.json"));
        }
        return instance;
    }

    public synchronized List<Entry> all() {
        return List.copyOf(entries.values());
    }

    // Null when nothing is saved under that name.
    public synchronized Entry find(String name) {
        return entries.get(key(name));
    }

    // The proxy joins go through whilst Proxy is on. Null whilst none is picked. The thread that
    // opens a connection reads it.
    public synchronized Entry picked() {
        return picked == null ? null : find(picked);
    }

    public synchronized void pick(Entry entry) {
        picked = entry.name();
        write();
    }

    public synchronized boolean isFull() {
        return entries.size() >= MOST_SAVED;
    }

    // Saves the proxy in place of one saved under its name. An import keeps logins by the same
    // rule. Null when the list is full.
    public synchronized Entry save(Typed typed) {
        Entry entry = new Entry(typed.savedName(), typed.kind(), typed.host(), typed.port());
        String key = key(entry.name());
        Entry before = entries.get(key);
        if (before == null && isFull()) {
            return null;
        }
        entries.put(key, entry);
        ProxyTunnel.Login saved = logins().get(key);
        ProxyTunnel.Login login = kept(before, typed, saved);
        if (login == null && saved != null) {
            logins().remove(key);
        } else if (login != null && !login.equals(saved)) {
            logins().set(key, login);
        }
        write();
        return entry;
    }

    // Forgetting the proxy also forgets its login and the pick when it was the one picked.
    public synchronized boolean remove(String name) {
        Entry entry = find(name);
        if (entry == null) {
            return false;
        }
        removeAll(List.of(entry));
        return true;
    }

    // Forgets every one of these proxies that is still saved and their logins. Hands back how many
    // went.
    public synchronized int removeAll(Collection<Entry> gone) {
        List<Entry> going = new ArrayList<>();
        for (Entry entry : gone) {
            if (entries.remove(key(entry.name()), entry)) {
                going.add(entry);
            }
        }
        if (going.isEmpty()) {
            return 0;
        }
        logins().edit(saved -> going.forEach(entry -> saved.remove(key(entry.name()))));
        if (picked != null && find(picked) == null) {
            picked = null;
        }
        write();
        return going.size();
    }

    // Null when the proxy has no login saved. Read on the network threads as each join opens.
    public synchronized ProxyTunnel.Login login(Entry entry) {
        return logins().get(key(entry.name()));
    }

    public synchronized void saveLogin(Entry entry, ProxyTunnel.Login login) {
        logins().set(key(entry.name()), login);
    }

    public synchronized boolean forgetLogin(Entry entry) {
        return logins().remove(key(entry.name()));
    }

    // Adds every proxy in a text file with one on each line in any of the usual forms. A proxy
    // saved already is left alone and a line that holds none is counted. A relative file name is
    // read from the offlineclient folder.
    public synchronized Imported importFrom(Request request) {
        String shown = request.shownFile();
        String text;
        try {
            Path path = DataFiles.path(request.file());
            if (!Files.isRegularFile(path)) {
                return Imported.failed("Cannot find " + shown + " in the offlineclient folder.");
            }
            if (Files.size(path) > LARGEST_FILE) {
                return Imported.failed(shown + " is larger than a megabyte and holds no list of proxies.");
            }
            text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException e) {
            return Imported.failed("Could not read " + shown + ".");
        }
        Batch batch = new Batch(request.kind());
        text.lines().forEach(batch::read);
        if (batch.added > 0) {
            logins().edit(saved -> batch.written.forEach((key, login) -> {
                if (login == null) {
                    saved.remove(key);
                } else {
                    saved.put(key, login);
                }
            }));
            write();
        }
        return new Imported(shown, batch.added, batch.known, batch.unread, batch.leftOut, null);
    }

    // One import. Every line costs a few lookups however long the list and the file are.
    private final class Batch {

        private final ProxyTunnel.Kind kind;
        // The login of every saved proxy by its name with what this import changed. Null for none.
        private final Map<String, ProxyTunnel.Login> logins = new HashMap<>();
        // What this import changed in the logins. Null stands for one taken away.
        private final Map<String, ProxyTunnel.Login> written = new HashMap<>();
        // How many saved proxies have each address and login.
        private final Map<Held, Integer> held = new HashMap<>();
        // The number the next proxy of each address tries after its name.
        private final Map<String, Integer> numbers = new HashMap<>();
        private int added;
        private int known;
        private int unread;
        private int leftOut;

        private Batch(ProxyTunnel.Kind kind) {
            this.kind = kind;
            for (Entry entry : entries.values()) {
                ProxyTunnel.Login login = login(entry);
                logins.put(key(entry.name()), login);
                count(entry, login, 1);
            }
        }

        // A proxy whose address and login are saved already is known. One named after a saved
        // proxy takes its place and keeps its login the way a typed one does.
        private void read(String line) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith(REMARK)) {
                return;
            }
            Typed typed = ProxyText.typed(text, kind);
            if (typed == null) {
                unread++;
                return;
            }
            Entry before = typed.name() == null ? null : find(typed.name());
            ProxyTunnel.Login login = kept(before, typed, before == null ? null : logins.get(key(before.name())));
            if (held.containsKey(new Held(typed.host(), typed.port(), login))) {
                known++;
                return;
            }
            if (before == null && isFull()) {
                leftOut++;
                return;
            }
            String name = typed.name() == null ? freeName(typed.address()) : typed.name();
            String key = key(name);
            if (before != null) {
                count(before, logins.get(key), -1);
            }
            Entry entry = new Entry(name, typed.kind(), typed.host(), typed.port());
            entries.put(key, entry);
            logins.put(key, login);
            written.put(key, login);
            count(entry, login, 1);
            added++;
        }

        private void count(Entry entry, ProxyTunnel.Login login, int change) {
            held.merge(new Held(entry.host(), entry.port(), login), change,
                (was, more) -> was + more == 0 ? null : was + more);
        }

        // The address as a name or the address with the first free number after it. An address
        // that comes again carries on from the number it reached.
        private String freeName(String address) {
            if (find(address) == null) {
                return address;
            }
            int number = numbers.getOrDefault(key(address), FIRST_NUMBER);
            while (find(address + NUMBERED + number) != null) {
                number++;
            }
            numbers.put(key(address), number + 1);
            return address + NUMBERED + number;
        }
    }

    // The login a proxy ends up with when it is saved over the one saved under its name before. A
    // login typed with it replaces the saved one. Without one the saved login stays whilst the
    // host and port do. Null for none.
    private static ProxyTunnel.Login kept(Entry before, Typed typed, ProxyTunnel.Login saved) {
        if (typed.login() != null) {
            return typed.login();
        }
        return before != null && before.host().equalsIgnoreCase(typed.host()) && before.port() == typed.port()
            ? saved : null;
    }

    private static ServerStore<ProxyTunnel.Login> logins() {
        return ServerStore.proxyLogins();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    // Rows that cannot be read are left out and a name that comes again loads once. The list keeps
    // no more than it can hold.
    private static Saved decode(JsonElement root) {
        JsonObject object = root.getAsJsonObject();
        Map<String, Entry> decoded = new LinkedHashMap<>();
        for (Entry entry : DataFiles.entries(object.get(LIST), ProxyStore::entry, "saved proxies")) {
            if (decoded.size() < MOST_SAVED) {
                decoded.putIfAbsent(key(entry.name()), entry);
            }
        }
        return new Saved(List.copyOf(decoded.values()), JsonFields.text(object, USE));
    }

    // Null for a row a hand edit broke.
    private static Entry entry(JsonElement element) {
        JsonObject saved = element.getAsJsonObject();
        String name = saved.get(NAME).getAsString();
        String host = saved.get(HOST).getAsString();
        int port = saved.get(PORT).getAsInt();
        ProxyTunnel.Kind kind = ProxyTunnel.Kind.valueOf(saved.get(KIND).getAsString());
        return ProxyText.validName(name) && ProxyText.validHost(host) && ProxyText.validPort(port)
            ? new Entry(name, kind, host, port) : null;
    }

    private void write() {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject saved = new JsonObject();
            saved.addProperty(NAME, entry.name());
            saved.addProperty(KIND, entry.kind().name());
            saved.addProperty(HOST, entry.host());
            saved.addProperty(PORT, entry.port());
            list.add(saved);
        }
        root.add(LIST, list);
        if (picked != null) {
            root.addProperty(USE, picked);
        }
        DataFiles.writeJson(file, root);
    }
}
