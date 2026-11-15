package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// The Paper server the two player suites play on. Its jar and libraries are copied out of the
// server folder the local settings name and it runs from a folder of the rig's own. The test
// players join without a login. Nothing in the folder it was copied from is ever written.
public final class PaperServer {

    // Which of the plugins of the copied server join the rig's own and whether the server lets
    // players float. A server that does not kicks anybody who hangs in the air.
    public record Setup(String name, List<String> plugins, boolean allowFlight) {

        public static Setup plain() {
            return new Setup("no plugins", List.of(), false);
        }

        public static Setup with(String... plugins) {
            return new Setup(String.join(" and ", plugins), List.of(plugins), true);
        }
    }

    // True when a plugin whose jar begins with the name lies among the plugins of the borrowed
    // server or in the rig's own folder of plugins.
    public static boolean has(String plugin) {
        return !jars(plugin).isEmpty();
    }

    private static List<Path> jars(String plugin) {
        List<Path> found = new ArrayList<>();
        for (Path folder : List.of(RigConfig.server().resolve(PLUGINS), RigConfig.plugins())) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (DirectoryStream<Path> jars = Files.newDirectoryStream(folder, plugin + "*.jar")) {
                jars.forEach(found::add);
            } catch (IOException e) {
                throw new RigFailure("The plugins in " + folder + " could not be listed", e);
            }
            if (!found.isEmpty()) {
                break;
            }
        }
        return found;
    }

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 25599;

    private static final String HOME = "rig-server";
    private static final String JAR_GLOB = "paper-*.jar";
    private static final Pattern BUILD = Pattern.compile("-(\\d+)\\.jar$");
    private static final String LIBRARY_LIST = "META-INF/libraries.list";
    private static final String VERSION_LIST = "META-INF/versions.list";
    private static final String LIBRARIES = "libraries";
    private static final String VERSIONS = "versions";
    private static final String CACHE = "cache";
    private static final String CACHED_GAME = "mojang_%s.jar";
    private static final String PLUGINS = "plugins";
    private static final String PLUGIN_SETTINGS = "config.yml";
    private static final String EULA = "eula.txt";
    private static final String WORLD = "world";
    private static final String PID_FILE = "rig-server.pid";
    private static final String HOSTS_FILE = "rig-hosts.txt";
    private static final String LOG_FILE = "server.log";
    private static final String MEMORY = "-Xmx2G";
    private static final int WRITTEN_WITHIN = 30;

    private static final String READY = "Done (";
    private static final String WORD = "PROBE ";
    private static final String LINE_HEAD = "]: ";
    // Two minutes for a first start that grows a world. Later starts take a few seconds.
    private static final int START_TICKS = 2400;
    private static final int ANSWER_TICKS = 200;
    private static final int STOP_TICKS = 600;

    private final Rig rig;
    private final Process process;
    private final BufferedWriter console;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private int marks;

    private PaperServer(Rig rig, Process process) {
        this.rig = rig;
        this.process = process;
        this.console = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    }

    public static String address() {
        return HOST + ":" + PORT;
    }

    // Copies what the server needs and starts it and waits until players can join.
    public static PaperServer start(Rig rig, Setup setup) {
        Path source = RigConfig.server();
        Path home = rig.results().out().getParent().resolveSibling(HOME);
        try {
            Files.createDirectories(home);
            endLeftOver(rig, home);
            Path jar = prepare(source, home, setup);
            Process process = new ProcessBuilder(java(), MEMORY, "-Djdk.net.hosts.file=" + home.resolve(HOSTS_FILE),
                "-jar", jar.getFileName().toString(), "--nogui", "--nojline")
                .directory(home.toFile()).redirectErrorStream(true).start();
            Files.writeString(home.resolve(PID_FILE), Long.toString(process.pid()));
            PaperServer server = new PaperServer(rig, process);
            try {
                server.listen(rig.results().out().resolve(LOG_FILE));
                server.awaitReady();
            } catch (RuntimeException | Error failed) {
                process.destroyForcibly();
                throw failed;
            }
            return server;
        } catch (IOException e) {
            throw new RigFailure("The server could not be set up in " + home, e);
        }
    }

    private static String java() {
        return ProcessHandle.current().info().command().orElseThrow();
    }

    // A server an earlier run left behind still holds the port. It is the rig's own and is ended.
    private static void endLeftOver(Rig rig, Path home) throws IOException {
        Path file = home.resolve(PID_FILE);
        if (Files.isRegularFile(file)) {
            Instant written = Files.getLastModifiedTime(file).toInstant();
            Optional<ProcessHandle> old = ProcessHandle.of(Long.parseLong(Files.readString(file).trim()));
            if (old.isPresent() && beganJustBefore(old.get(), written)) {
                old.get().destroyForcibly();
                for (int waited = 0; waited < STOP_TICKS && old.get().isAlive(); waited++) {
                    rig.ticks(1);
                }
            }
            Files.delete(file);
        }
        try (ServerSocket free = new ServerSocket(PORT, 1, InetAddress.getByName(HOST))) {
            free.getLocalPort();
        } catch (IOException e) {
            throw new RigFailure("Something else on this machine holds port " + PORT, e);
        }
    }

    // The rig writes the id of its server down the moment the server starts. A process that
    // began at any other time got the same id from the system later and is not the rig's to end.
    private static boolean beganJustBefore(ProcessHandle process, Instant written) {
        Optional<Instant> began = process.info().startInstant();
        return began.isPresent() && began.get().isBefore(written.plusSeconds(1))
            && began.get().isAfter(written.minusSeconds(WRITTEN_WITHIN));
    }

    private static Path prepare(Path source, Path home, Setup setup) throws IOException {
        Path jar = newestJar(source);
        copy(jar, home.resolve(jar.getFileName()));
        try (ZipFile bundle = new ZipFile(jar.toFile())) {
            for (String[] entry : listed(bundle, LIBRARY_LIST)) {
                copy(source.resolve(LIBRARIES).resolve(entry[2]), home.resolve(LIBRARIES).resolve(entry[2]));
            }
            for (String[] entry : listed(bundle, VERSION_LIST)) {
                copy(source.resolve(VERSIONS).resolve(entry[2]), home.resolve(VERSIONS).resolve(entry[2]));
                String game = String.format(CACHED_GAME, entry[1]);
                copy(source.resolve(CACHE).resolve(game), home.resolve(CACHE).resolve(game));
            }
        }
        copy(source.resolve(EULA), home.resolve(EULA));
        freshWorld(home);
        Files.writeString(home.resolve(HOSTS_FILE), HOST + " localhost\n");
        Files.writeString(home.resolve("server.properties"), properties(setup));
        plugins(source.resolve(PLUGINS), home.resolve(PLUGINS), setup);
        return jar;
    }

    // Every run starts on untouched ground. What an earlier run built would stand in the way.
    private static void freshWorld(Path home) throws IOException {
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(home, WORLD + "*")) {
            for (Path world : worlds) {
                try (java.util.stream.Stream<Path> files = Files.walk(world)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    // The jar with the highest build number. An owner keeps older ones beside it.
    private static Path newestJar(Path source) throws IOException {
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> found = Files.newDirectoryStream(source, JAR_GLOB)) {
            found.forEach(jars::add);
        }
        return jars.stream().max(Comparator.comparingInt(PaperServer::build))
            .orElseThrow(() -> new RigFailure("No server jar named " + JAR_GLOB + " lies in the server folder"));
    }

    private static int build(Path jar) {
        Matcher number = BUILD.matcher(jar.getFileName().toString());
        return number.find() ? Integer.parseInt(number.group(1)) : 0;
    }

    private static List<String[]> listed(ZipFile bundle, String name) throws IOException {
        ZipEntry entry = bundle.getEntry(name);
        List<String[]> rows = new ArrayList<>();
        if (entry == null) {
            return rows;
        }
        String listed = new String(bundle.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        for (String line : listed.split("\n")) {
            String[] row = line.trim().split("\t");
            if (row.length == 3) {
                rows.add(row);
            }
        }
        return rows;
    }

    // A file that is there already with the same size is left alone.
    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isRegularFile(from) || Files.isRegularFile(to) && Files.size(to) == Files.size(from)) {
            return;
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String properties(Setup setup) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("online-mode", false);
        values.put("white-list", false);
        values.put("server-ip", HOST);
        values.put("server-port", PORT);
        values.put("level-name", WORLD);
        values.put("level-type", "minecraft\\:flat");
        values.put("generate-structures", false);
        values.put("gamemode", "survival");
        values.put("difficulty", "easy");
        values.put("allow-flight", setup.allowFlight());
        values.put("spawn-protection", 0);
        values.put("enforce-secure-profile", false);
        values.put("enable-rcon", false);
        values.put("enable-query", false);
        values.put("view-distance", 6);
        values.put("simulation-distance", 6);
        values.put("max-players", 8);
        values.put("pause-when-empty-seconds", -1);
        values.put("motd", "OfflineClient test rig");
        StringBuilder text = new StringBuilder();
        values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        return text.toString();
    }

    // The probe always. Of the copied server's plugins the ones the setup names with the settings
    // file each keeps. The jars of an earlier run go first.
    private static void plugins(Path from, Path to, Setup setup) throws IOException {
        Files.createDirectories(to);
        try (DirectoryStream<Path> old = Files.newDirectoryStream(to, "*.jar")) {
            for (Path jar : old) {
                Files.delete(jar);
            }
        }
        Files.copy(RigConfig.probe(), to.resolve(RigConfig.probe().getFileName()), StandardCopyOption.REPLACE_EXISTING);
        for (String plugin : setup.plugins()) {
            List<Path> jars = jars(plugin);
            if (jars.isEmpty()) {
                throw new RigFailure("No plugin called " + plugin + " was found");
            }
            for (Path jar : jars) {
                Files.copy(jar, to.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            // The settings its owner gave it go along. A plugin without any writes its own.
            Path settings = jars.getFirst().resolveSibling(plugin).resolve(PLUGIN_SETTINGS);
            if (Files.isRegularFile(settings)) {
                copy(settings, to.resolve(plugin).resolve(PLUGIN_SETTINGS));
            }
        }
    }

    private void listen(Path log) {
        Thread reader = new Thread(() -> {
            try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8))) {
                for (String line = output.readLine(); line != null; line = output.readLine()) {
                    lines.add(line);
                    Files.writeString(log, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            } catch (IOException e) {
                lines.add("The rig lost the server's output. " + e);
            }
        }, "Rig server output");
        reader.setDaemon(true);
        reader.start();
    }

    private void awaitReady() {
        for (int waited = 0; waited <= START_TICKS; waited++) {
            if (since(0).stream().anyMatch(line -> line.contains(READY))) {
                console("scoreboard objectives add rigok dummy");
                console("scoreboard objectives add rigval dummy");
                return;
            }
            expectAlive();
            rig.ticks(1);
        }
        stop();
        throw new RigFailure("The server was not ready after " + START_TICKS + " ticks. " + tail());
    }

    private void expectAlive() {
        if (!process.isAlive()) {
            throw new RigFailure("The server ended by itself. " + tail());
        }
    }

    private String tail() {
        List<String> all = List.copyOf(lines);
        return "Its last lines were " + all.subList(Math.max(0, all.size() - 12), all.size());
    }

    // Types a line at the server's console and hands back what the server wrote in answer.
    public List<String> console(String line) {
        return console(List.of(line));
    }

    // The same for several lines the server runs within one tick.
    public List<String> console(List<String> typed) {
        int from = lines.size();
        String mark = WORD + "mark " + ++marks;
        try {
            for (String line : typed) {
                console.write(line + "\n");
            }
            console.write("probe mark " + marks + "\n");
            console.flush();
        } catch (IOException e) {
            throw new RigFailure("The server's console is closed. " + tail(), e);
        }
        for (int waited = 0; waited <= ANSWER_TICKS; waited++) {
            List<String> answer = since(from);
            for (int i = 0; i < answer.size(); i++) {
                if (answer.get(i).endsWith(mark)) {
                    return answer.subList(0, i);
                }
            }
            expectAlive();
            rig.ticks(1);
        }
        throw new RigFailure("The server did not answer the console lines " + typed);
    }

    // What the server wrote since a place in its log with the time and level cut off each line.
    public List<String> since(int from) {
        List<String> answer = new ArrayList<>();
        synchronized (lines) {
            for (String line : lines.subList(Math.min(from, lines.size()), lines.size())) {
                int head = line.indexOf(LINE_HEAD);
                answer.add(head < 0 ? line : line.substring(head + LINE_HEAD.length()));
            }
        }
        return answer;
    }

    // Waits for a line of the probe that begins with these words and hands back what follows
    // them. Null when none came in time.
    public String heard(String head, int from, int limitTicks) {
        for (int waited = 0; waited <= limitTicks; waited++) {
            List<String> all = allHeard(head, from);
            if (!all.isEmpty()) {
                return all.getFirst();
            }
            expectAlive();
            rig.ticks(1);
        }
        return null;
    }

    // Every such line since a place in the log.
    public List<String> allHeard(String head, int from) {
        String wanted = WORD + head + " ";
        List<String> found = new ArrayList<>();
        for (String line : since(from)) {
            int at = line.indexOf(wanted);
            if (at >= 0) {
                found.add(line.substring(at + wanted.length()));
            }
        }
        return found;
    }

    // Where the log stands now. A case hands it to since later.
    public int logMark() {
        return lines.size();
    }

    // What the probe says about a player this tick.
    public Map<String, String> player(String name) {
        return pairs(probe("player " + name, ""));
    }

    // What the player did since the tallies were last reset.
    public Map<String, Double> tally(String name) {
        Map<String, Double> counted = new LinkedHashMap<>();
        pairs(probe("tally " + name, "")).forEach((key, value) -> counted.put(key, Double.parseDouble(value)));
        return counted;
    }

    // True when the plugin of that name runs on the server. One that failed to start does not.
    public boolean runs(String plugin) {
        return Boolean.parseBoolean(probe("plugin " + plugin, ""));
    }

    public void resetTallies() {
        probe("reset", "");
    }

    // The id of the block the server holds there with its namespace in front.
    public String block(int x, int y, int z) {
        return probe("block", x + " " + y + " " + z);
    }

    // Asks the probe with a head and more words. Its answer is one line that begins with the same
    // head and the words after the head come back.
    private String probe(String head, String more) {
        String wanted = WORD + head;
        for (String line : console(("probe " + head + " " + more).trim())) {
            int at = line.indexOf(wanted);
            if (at >= 0) {
                return line.substring(at + wanted.length()).trim();
            }
        }
        throw new RigFailure("The probe did not answer " + head + " " + more);
    }

    private static Map<String, String> pairs(String words) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String pair : words.split(" ")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                pairs.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return pairs;
    }

    // Asks the server to save and end and waits for it. One that does not is ended.
    public void stop() {
        if (!process.isAlive()) {
            return;
        }
        try {
            console.write("stop\n");
            console.flush();
        } catch (IOException e) {
            process.destroyForcibly();
        }
        for (int waited = 0; waited <= STOP_TICKS && process.isAlive(); waited++) {
            rig.ticks(1);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
