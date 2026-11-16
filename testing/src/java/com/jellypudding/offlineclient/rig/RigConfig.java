package com.jellypudding.offlineclient.rig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// What the launch told the rig through system properties.
public final class RigConfig {

    private static final String OUT_PROPERTY = "rig.out";
    private static final String DEFAULT_OUT = "rig-results";
    private static final String FILTER_PROPERTY = "rig.filter";
    private static final String SCALES_PROPERTY = "rig.scales";
    private static final String DEFAULT_SCALES = "2,4";
    private static final String OWNER_PROPERTY = "rig.owner";
    private static final String SERVER_PROPERTY = "rig.server";
    private static final String PROBE_PROPERTY = "rig.probe";
    private static final String GUARD_PROPERTY = "rig.guard";
    private static final String PLUGINS_PROPERTY = "rig.plugins";
    private static final String ROLE_PROPERTY = "rig.role";
    private static final String PARTNER = "partner";
    private static final String LINK_PROPERTY = "rig.link";
    private static final String CONTROL_PROPERTY = "rig.control";
    private static final String SEPARATOR = ",";

    private RigConfig() {
    }

    // The folder every result of this run lands in.
    public static Path out() {
        return Path.of(System.getProperty(OUT_PROPERTY, DEFAULT_OUT)).toAbsolutePath();
    }

    public static String filter() {
        return System.getProperty(FILTER_PROPERTY, "");
    }

    // The GUI scales the Screens suite draws every screen at.
    public static List<Integer> scales() {
        List<Integer> scales = new ArrayList<>();
        for (String word : System.getProperty(SCALES_PROPERTY, DEFAULT_SCALES).split(SEPARATOR)) {
            if (!word.isBlank()) {
                scales.add(Integer.parseInt(word.trim()));
            }
        }
        return scales;
    }

    // The folder of the Paper server the two player suites borrow. Null when the local settings
    // name none and those suites are left out.
    public static Path server() {
        String folder = System.getProperty(SERVER_PROPERTY, "");
        return folder.isBlank() ? null : Path.of(folder);
    }

    // A folder of plugin jars that belong to the rig alone. Git ignores it.
    public static Path plugins() {
        return Path.of(System.getProperty(PLUGINS_PROPERTY, ""));
    }

    // The anti cheats the Guard suite runs under one after the other. Empty when the local
    // settings name none.
    public static List<String> guard() {
        List<String> plugins = new ArrayList<>();
        for (String plugin : System.getProperty(GUARD_PROPERTY, "").split(SEPARATOR)) {
            if (!plugin.isBlank()) {
                plugins.add(plugin.trim());
            }
        }
        return plugins;
    }

    // The jar of the plugin that tells the rig what the server saw.
    public static Path probe() {
        return Path.of(System.getProperty(PROBE_PROPERTY, ""));
    }

    // True in the game of the second player. It runs no suites and does what the first asks.
    public static boolean partner() {
        return PARTNER.equals(System.getProperty(ROLE_PROPERTY));
    }

    public static String partnerRole() {
        return "-D" + ROLE_PROPERTY + "=" + PARTNER;
    }

    // The port on this machine the first player's game listens on for the second.
    public static int link() {
        return Integer.getInteger(LINK_PROPERTY, 0);
    }

    public static String linkAt(int port) {
        return "-D" + LINK_PROPERTY + "=" + port;
    }

    public static String outAt(Path folder) {
        return "-D" + OUT_PROPERTY + "=" + folder;
    }

    // True for a control run. No module can be switched on and every case about a module has to
    // fail. One that still passes proves nothing.
    public static boolean control() {
        return Boolean.getBoolean(CONTROL_PROPERTY);
    }

    // True when the run started with a copy of a player's own data folder.
    public static boolean ownerData() {
        return Boolean.getBoolean(OWNER_PROPERTY);
    }
}
