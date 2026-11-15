package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// A third game with no mod loader and no mod in it. It joins the server by itself and then
// only stands there. What the server holds about it is what a server holds about any plain game
// and the client is held against that.
public final class PlainGame implements AutoCloseable {

    public static final String NAME = "RigPlain";

    private static final String FOLDER = "clientGameTestPlain";
    private static final String MAIN = PlainGameMain.class.getName();
    private static final List<String> SHARED_FILES = List.of("options.txt", "rig-hosts.txt");
    // Options of the mod loader and of the rig mean nothing to a plain game.
    private static final List<String> DROPPED_OPTIONS = List.of("-Drig.", "-Dfabric.", "-javaagent");
    private static final String LAUNCH_SETTINGS = "-Dfabric.dli.config=";
    private static final String GAME_WORDS = "clientArgs";
    private static final int JOIN_TICKS = 3000;
    private static final int ASK_EVERY = 10;
    private static final int SETTLE_TICKS = 40;
    private static final int LIMIT_SECONDS = 420;

    private final Process process;

    private PlainGame(Process process) {
        this.process = process;
    }

    // Starts the plain game and waits until the server holds its player.
    public static PlainGame join(Rig rig, PaperServer server) {
        Process process;
        Path folder = Path.of("").toAbsolutePath().resolveSibling(FOLDER);
        try {
            process = launch(rig, folder);
        } catch (IOException e) {
            throw new RigFailure("The plain game could not be started", e);
        }
        PlainGame game = new PlainGame(process);
        for (int waited = 0; waited <= JOIN_TICKS; waited += ASK_EVERY) {
            if ("true".equals(server.player(NAME).get("online"))) {
                rig.ticks(SETTLE_TICKS);
                return game;
            }
            if (!process.isAlive()) {
                throw new RigFailure("The plain game ended before it joined. Its words are in "
                    + folder.resolve("output.log"));
            }
            rig.ticks(ASK_EVERY);
        }
        game.close();
        throw new RigFailure("The plain game did not join within " + JOIN_TICKS + " ticks");
    }

    // The words this game was started with less everything of the mod loader. The plain game
    // takes its assets from where this one does and joins the server as soon as it is up.
    private static Process launch(Rig rig, Path folder) throws IOException {
        Path home = Path.of("").toAbsolutePath();
        Files.createDirectories(folder);
        for (String file : SHARED_FILES) {
            Files.copy(home.resolve(file), folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> words = new ArrayList<>();
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (DROPPED_OPTIONS.stream().noneMatch(option::startsWith)) {
                words.add(option);
            }
        }
        words.add("-D" + PlainGameMain.PARENT_PROPERTY + "=" + ProcessHandle.current().pid());
        words.add("-D" + PlainGameMain.LIMIT_PROPERTY + "=" + LIMIT_SECONDS);
        words.add("-cp");
        words.add(System.getProperty("java.class.path"));
        words.add(MAIN);
        words.addAll(List.of("--version", "rig", "--accessToken", "0", "--username", NAME, "--width", "480",
            "--height", "270", "--gameDir", folder.toString(), "--quickPlayMultiplayer", PaperServer.address()));
        words.addAll(gameWords());
        Path file = folder.resolve("launch.args");
        Files.write(file, words.stream().map(PlainGame::quoted).toList());
        Process process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "@" + file)
            .directory(folder.toFile()).redirectErrorStream(true)
            .redirectOutput(folder.resolve("output.log").toFile()).start();
        Path id = rig.results().out().resolve("plain").resolve("pid.txt");
        Files.createDirectories(id.getParent());
        Files.writeString(id, Long.toString(process.pid()));
        return process;
    }

    // What the launch settings of this game hand every client such as where the assets lie.
    private static List<String> gameWords() throws IOException {
        List<String> words = new ArrayList<>();
        boolean reading = false;
        for (String line : Files.readAllLines(launchSettings(), StandardCharsets.UTF_8)) {
            if (!line.startsWith("\t")) {
                reading = line.trim().equals(GAME_WORDS);
            } else if (reading) {
                words.add(line.trim());
            }
        }
        return words;
    }

    // The mod loader takes the name of its settings file out of the system properties as it
    // starts. The words this game was started with still hold it.
    private static Path launchSettings() {
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (option.startsWith(LAUNCH_SETTINGS)) {
                return Path.of(option.substring(LAUNCH_SETTINGS.length()));
            }
        }
        throw new RigFailure("This game was started without launch settings to take the assets from");
    }

    private static String quoted(String word) {
        return '"' + word.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
