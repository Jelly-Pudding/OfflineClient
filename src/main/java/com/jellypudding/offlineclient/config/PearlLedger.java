package com.jellypudding.offlineclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.jellypudding.offlineclient.util.PearlOwners.Owner;
import net.minecraft.resources.RegistryOps;

import java.util.Map;
import java.util.UUID;

// Who owns each stasis pearl on the server you play on. A pearl keeps its id whilst its owner
// is away and names its owner again on the next visit. The owners sit in a file beside the
// finds. Two games on one computer add to the same file and neither loses what the other
// learnt.
public final class PearlLedger extends ServerLedger<UUID, Owner> {

    private static final String FILE = "pearlowners.json";
    // Far more pearls than a server keeps in stasis. It only bounds the memory and the file.
    private static final int MAX_PEARLS = 16_384;

    public PearlLedger() {
        super(FILE, MAX_PEARLS, "pearl owners");
    }

    @Override
    protected void parse(JsonElement root, Map<UUID, Owner> into, RegistryOps<JsonElement> ops) {
        for (JsonElement line : root.getAsJsonArray()) {
            String[] parts = line.getAsString().split(" ", 3);
            into.put(UUID.fromString(parts[0]), new Owner(UUID.fromString(parts[1]), parts[2]));
        }
    }

    // One line for each pearl such as its id and its owner's id and name.
    @Override
    protected JsonElement encode(Map<UUID, Owner> owners, RegistryOps<JsonElement> ops) {
        JsonArray lines = new JsonArray();
        owners.forEach((pearl, owner) -> lines.add(pearl + " " + owner.id() + " " + owner.name()));
        return lines;
    }
}
