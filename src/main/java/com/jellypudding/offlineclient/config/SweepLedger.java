package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.util.Sweep;
import net.minecraft.resources.RegistryOps;

import java.util.Map;

// The sweep the autopilot flew last in each dimension of the server you play on and the place
// it reached. The file sits beside the finds. A sweep that does not read back whole is left out.
public final class SweepLedger extends ServerLedger<String, Sweep> {

    private static final String FILE = "sweeps.json";
    private static final String WHAT = "saved sweeps";
    // Far more dimensions than a server has. It only bounds the memory and the file.
    private static final int MAX_DIMENSIONS = 64;
    // No dimension has a longer id. A longer one in a file is a broken file.
    private static final int LONGEST_ID = 256;

    public SweepLedger() {
        super(FILE, MAX_DIMENSIONS, WHAT);
    }

    @Override
    protected void parse(JsonElement root, Map<String, Sweep> into, RegistryOps<JsonElement> ops) {
        into.putAll(DataFiles.members(root, (dimension, sweep) -> dimension.length() > LONGEST_ID ? null
            : Sweep.fromJson(sweep), WHAT));
    }

    @Override
    protected JsonElement encode(Map<String, Sweep> sweeps, RegistryOps<JsonElement> ops) {
        JsonObject root = new JsonObject();
        sweeps.forEach((dimension, sweep) -> root.add(dimension, sweep.toJson()));
        return root;
    }
}
