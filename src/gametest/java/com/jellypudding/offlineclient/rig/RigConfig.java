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

    // True when the run started with a copy of a player's own data folder.
    public static boolean ownerData() {
        return Boolean.getBoolean(OWNER_PROPERTY);
    }
}
