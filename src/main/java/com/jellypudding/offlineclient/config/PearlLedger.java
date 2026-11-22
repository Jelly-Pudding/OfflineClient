package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.util.PearlOwners.Owner;
import net.minecraft.resources.RegistryOps;

import java.util.Map;
import java.util.UUID;

// A pearl keeps its id whilst its owner is away and names its owner again on the next visit.
public final class PearlLedger extends ServerLedger<UUID, Owner> {

    private static final String FILE = "pearlowners.json";
    private static final String WHAT = "pearl owners";
    // Far more pearls than a server keeps in stasis. It only bounds the memory and the file.
    private static final int MAX_PEARLS = 16_384;

    public PearlLedger() {
        super(FILE, MAX_PEARLS, WHAT);
    }

    @Override
    protected void parse(JsonElement root, Map<UUID, Owner> into, RegistryOps<JsonElement> ops) {
        DataFiles.entries(root, PearlLedger::pearl, WHAT).forEach(pearl -> into.put(pearl.getKey(), pearl.getValue()));
    }

    private static Map.Entry<UUID, Owner> pearl(JsonElement line) {
        String[] parts = line.getAsString().split(" ", 3);
        return Map.entry(UUID.fromString(parts[0]), new Owner(UUID.fromString(parts[1]), parts[2]));
    }

    @Override
    protected JsonElement encode(Map<UUID, Owner> owners, RegistryOps<JsonElement> ops) {
        JsonArray lines = new JsonArray();
        owners.forEach((pearl, owner) -> lines.add(pearl + " " + owner.id() + " " + owner.name()));
        return lines;
    }
}
