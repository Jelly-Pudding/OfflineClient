package com.jellypudding.offlineclient.rig.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// What in the log counts as a fault of the client. One home for the rules the suites and the
// report both judge by.
public final class LogRules {

    // The client by any of its names but never the rig itself.
    private static final Pattern CLIENT = Pattern.compile("(?i)offlineclient(?![_.]rig)");
    private static final Pattern CLIENT_FRAME =
        Pattern.compile("\\tat (?:[\\w.]+//)?com\\.jellypudding\\.offlineclient\\.(?!rig\\.)");
    private static final Pattern CLIENT_RESOURCE = Pattern.compile("\\bofflineclient:[a-z0-9_./-]+");
    private static final Pattern SHADER = Pattern.compile("(?i)(couldn't|could not|can't|cannot|failed|error|missing"
        + "|unable).{0,80}(shader|render pipeline|pipeline \\(|pipeline for|post chain|program for|glsl|spirv|uniform"
        + "|sampler)");
    private static final Pattern MIXIN = Pattern.compile("(?i)mixin");
    private static final String CLIENT_LOGGER = "OfflineClient";

    public enum Kind {
        CLIENT_ERROR("an error that names the client"),
        CLIENT_EXCEPTION("an exception from inside the client"),
        SHADER("a failed shader or pipeline"),
        RESOURCE("a missing resource of the client"),
        MIXIN("a mixin fault");

        private final String words;

        Kind(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    public record Fault(Kind kind, LogFile.Entry entry) {

        public String brief() {
            return kind.words() + " [" + entry.level() + " " + entry.logger() + "] " + entry.brief();
        }
    }

    // Paths of the run hold the name of the project. They are taken out before a line is judged.
    private final List<String> pathsToForget = new ArrayList<>();

    public LogRules(Path... folders) {
        for (Path folder : folders) {
            String path = folder.toAbsolutePath().normalize().toString();
            pathsToForget.add(path);
            pathsToForget.add(path.replace('\\', '/'));
        }
    }

    // Null for an entry that is no fault of the client.
    public Fault judge(LogFile.Entry entry) {
        String text = entry.text();
        for (String path : pathsToForget) {
            text = text.replace(path, "");
        }
        boolean serious = entry.isError() || entry.isWarning();
        if (CLIENT_FRAME.matcher(text).find()) {
            return new Fault(Kind.CLIENT_EXCEPTION, entry);
        }
        boolean named = CLIENT.matcher(text).find() || entry.logger().equals(CLIENT_LOGGER);
        if (serious && named && MIXIN.matcher(entry.logger() + " " + text).find()) {
            return new Fault(Kind.MIXIN, entry);
        }
        if (entry.isError() && named) {
            return new Fault(Kind.CLIENT_ERROR, entry);
        }
        if (serious && SHADER.matcher(text).find()) {
            return new Fault(Kind.SHADER, entry);
        }
        if (serious && CLIENT_RESOURCE.matcher(text).find()) {
            return new Fault(Kind.RESOURCE, entry);
        }
        return null;
    }

    public List<Fault> faults(List<LogFile.Entry> entries) {
        List<Fault> faults = new ArrayList<>();
        for (LogFile.Entry entry : entries) {
            Fault fault = judge(entry);
            if (fault != null) {
                faults.add(fault);
            }
        }
        return faults;
    }

    // A warning the client wrote itself. No fault on its own but worth a line in a result.
    public static boolean clientWarning(LogFile.Entry entry) {
        return entry.isWarning() && entry.logger().equals(CLIENT_LOGGER);
    }
}
