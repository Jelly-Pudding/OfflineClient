package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.RigConfig;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

// The second player. It is a second game started the way this one was with another name and a
// folder of its own. It runs no suites. It joins the same server and does what this game asks
// over a line between the two that never leaves the machine.
public final class Partner {

    public static final String NAME = "RigPartner";
    static final String OK = "ok";
    static final String FAILED = "fail";
    static final String SPLIT = "\t";
    static final String QUIT = "quit";

    private static final String FOLDER = "clientGameTestPartner";
    private static final String NAME_OPTION = "--username";
    private static final String RIG_OPTIONS = "-Drig.";
    private static final String LIMIT_PROPERTY = "rig.limit";
    private static final List<String> SHARED_FILES = List.of("options.txt", "rig-hosts.txt");
    // A second game takes as long to start as the first did.
    private static final int HELLO_TICKS = 3000;
    private static final int ANSWER_TICKS = 1200;

    private static Partner running;

    private final Rig rig;
    private final Process process;
    private final PrintWriter orders;
    private final BlockingQueue<String> answers = new LinkedBlockingQueue<>();
    private String asked;

    private Partner(Rig rig, Process process, Socket line) throws IOException {
        this.rig = rig;
        this.process = process;
        this.orders = new PrintWriter(line.getOutputStream(), true, StandardCharsets.UTF_8);
        BufferedReader heard = new BufferedReader(new InputStreamReader(line.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try {
                for (String answer = heard.readLine(); answer != null; answer = heard.readLine()) {
                    answers.add(answer);
                }
            } catch (IOException e) {
                answers.add(FAILED + SPLIT + "The line to the second player broke. " + e);
            }
        }, "Rig partner answers");
        reader.setDaemon(true);
        reader.start();
    }

    // The second game of this run. It is started the first time it is asked for.
    public static Partner get(Rig rig) {
        if (running == null) {
            running = start(rig);
        }
        return running;
    }

    public static void end() {
        if (running != null) {
            running.stop();
            running = null;
        }
    }

    private static Partner start(Rig rig) {
        try (ServerSocket door = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Process process = launch(rig, door.getLocalPort());
            door.setSoTimeout(1);
            for (int waited = 0; waited <= HELLO_TICKS; waited++) {
                try {
                    return new Partner(rig, process, door.accept());
                } catch (java.net.SocketTimeoutException notYet) {
                    if (!process.isAlive()) {
                        throw new RigFailure("The second game ended before it said hello");
                    }
                    rig.ticks(1);
                }
            }
            process.destroyForcibly();
            throw new RigFailure("The second game did not say hello within " + HELLO_TICKS + " ticks");
        } catch (IOException e) {
            throw new RigFailure("The second game could not be started", e);
        }
    }

    // The words this game was started with and the second player's name and folder in place of
    // its own. They go through a file. The class path alone is longer than a command may be.
    private static Process launch(Rig rig, int port) throws IOException {
        Path home = Path.of("").toAbsolutePath();
        Path folder = home.resolveSibling(FOLDER);
        Files.createDirectories(folder);
        for (String file : SHARED_FILES) {
            Files.copy(home.resolve(file), folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> words = new ArrayList<>();
        for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!option.startsWith(RIG_OPTIONS)) {
                words.add(option);
            }
        }
        words.add(RigConfig.partnerRole());
        words.add(RigConfig.linkAt(port));
        words.add(RigConfig.outAt(rig.results().out().resolve("partner")));
        words.add("-D" + LIMIT_PROPERTY + "=" + System.getProperty(LIMIT_PROPERTY, "1500"));
        words.add("-cp");
        words.add(System.getProperty("java.class.path"));
        String[] command = System.getProperty("sun.java.command").split(" ");
        for (int i = 0; i < command.length; i++) {
            words.add(i > 0 && command[i - 1].equals(NAME_OPTION) ? NAME : command[i]);
        }
        Path file = folder.resolve("launch.args");
        Files.write(file, words.stream().map(Partner::quoted).toList());
        return new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "@" + file)
            .directory(folder.toFile()).redirectErrorStream(true)
            .redirectOutput(folder.resolve("output.log").toFile()).start();
    }

    private static String quoted(String word) {
        return '"' + word.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    // Sends one order and does not wait. This game plays on whilst the second player carries it out.
    public void send(String... words) {
        if (asked != null) {
            throw new RigFailure("The second player still owes the answer to " + asked);
        }
        asked = String.join(" ", words);
        orders.println(String.join(SPLIT, words));
    }

    // The answer to the order sent last. This game ticks on whilst it waits.
    public String await() {
        String order = asked;
        asked = null;
        for (int waited = 0; waited <= ANSWER_TICKS; waited++) {
            String answer = answers.poll();
            if (answer != null) {
                String[] parts = answer.split(SPLIT, 2);
                if (!parts[0].equals(OK)) {
                    throw new RigFailure("The second player could not " + order + ". "
                        + (parts.length > 1 ? parts[1] : ""));
                }
                return parts.length > 1 ? parts[1] : "";
            }
            if (!process.isAlive()) {
                throw new RigFailure("The second game ended whilst it was asked to " + order);
            }
            rig.ticks(1);
        }
        throw new RigFailure("The second player did not answer " + order);
    }

    public String ask(String... words) {
        send(words);
        return await();
    }

    public Map<String, String> see(String who) {
        return pairs(ask("see", who));
    }

    // Starts a watch of somebody over a number of ticks. The answer comes from await or watched.
    public void watch(String who, int ticks) {
        send("watch", who, Integer.toString(ticks));
    }

    public Map<String, String> watched() {
        return pairs(await());
    }

    static Map<String, String> pairs(String words) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (String pair : words.split(" ")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                pairs.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return pairs;
    }

    private void stop() {
        orders.println(QUIT);
        for (int waited = 0; waited <= ANSWER_TICKS && process.isAlive(); waited++) {
            rig.ticks(1);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
