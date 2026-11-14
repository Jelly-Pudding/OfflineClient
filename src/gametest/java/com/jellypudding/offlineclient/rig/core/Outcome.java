package com.jellypudding.offlineclient.rig.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// One finished case as it is written to the result file and read back by the report.
public record Outcome(String suite, String name, Verdict verdict, String message, long millis, List<String> shots) {

    private static final String SUITE = "suite";
    private static final String NAME = "case";
    private static final String VERDICT = "verdict";
    private static final String MESSAGE = "message";
    private static final String MILLIS = "millis";
    private static final String SHOTS = "shots";

    public String id() {
        return suite + "/" + name;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty(SUITE, suite);
        json.addProperty(NAME, name);
        json.addProperty(VERDICT, verdict.name().toLowerCase(Locale.ROOT));
        json.addProperty(MESSAGE, message);
        json.addProperty(MILLIS, millis);
        JsonArray paths = new JsonArray();
        shots.forEach(paths::add);
        json.add(SHOTS, paths);
        return json;
    }

    public static Outcome fromJson(JsonObject json) {
        List<String> shots = new ArrayList<>();
        if (json.has(SHOTS)) {
            for (JsonElement path : json.getAsJsonArray(SHOTS)) {
                shots.add(path.getAsString());
            }
        }
        return new Outcome(json.get(SUITE).getAsString(), json.get(NAME).getAsString(),
            Verdict.valueOf(json.get(VERDICT).getAsString().toUpperCase(Locale.ROOT)),
            json.get(MESSAGE).getAsString(), json.get(MILLIS).getAsLong(), shots);
    }
}
