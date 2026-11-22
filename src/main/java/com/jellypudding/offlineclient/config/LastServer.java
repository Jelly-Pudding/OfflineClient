package com.jellypudding.offlineclient.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.ServerData;

// A realm and a world on your own network are never saved.
public final class LastServer {

    private static final String ADDRESS = "address";
    // The game's own address boxes take no more than this.
    private static final int LONGEST_ADDRESS = 128;

    private LastServer() {
    }

    public static ServerData read() {
        if (OfflineClient.MC == null) {
            return null;
        }
        return DataFiles.readJson(DataFiles.path("lastserver.json"), LastServer::decode).orElse(null);
    }

    public static void write(ServerData server) {
        if (server.type() != ServerData.Type.OTHER || !fits(server.ip)) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty(ADDRESS, server.ip);
        DataFiles.writeJson(DataFiles.path("lastserver.json"), root);
    }

    private static ServerData decode(JsonElement root) {
        String address = root.getAsJsonObject().get(ADDRESS).getAsString().trim();
        return fits(address) ? new ServerData(address, address, ServerData.Type.OTHER) : null;
    }

    private static boolean fits(String address) {
        return !address.isBlank() && address.length() <= LONGEST_ADDRESS;
    }
}
