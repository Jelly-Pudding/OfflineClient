package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.regex.Pattern;

// What the client knows about the server it is playing on. The server command
// and the HUD read it from here and always agree.
public final class ServerInfo {

    // Vanilla gives this label to a direct connect and to a list entry nobody named.
    private static final String UNNAMED_KEY = "selectServer.defaultName";

    private static final Pattern PAPER_FAMILY = Pattern.compile(
        "paper|purpur|pufferfish|folia|spigot|bukkit|leaves|gale", Pattern.CASE_INSENSITIVE);

    private ServerInfo() {
    }

    // The address you joined with. Null in single player.
    public static String address() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        return data == null ? null : data.ip;
    }

    // A stable name for where you are playing. Saved data is filed under it.
    public static String key() {
        String address = address();
        return address == null ? "singleplayer" : address.toLowerCase(Locale.ROOT);
    }

    // The label the server has in your own server list. Null until you name it.
    public static String savedName() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        if (data == null || data.name == null || data.name.isBlank()
            || data.name.equals(I18n.get(UNNAMED_KEY))) {
            return null;
        }
        return data.name;
    }

    // The message of the day the server sends as you join. Null if it sent none.
    public static Component motd() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        if (data == null || data.motd == null || data.motd.getString().isBlank()) {
            return null;
        }
        return data.motd;
    }

    // The server software such as Paper. Null until the server says.
    public static String brand() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? null : player.connection.serverBrand();
    }

    // True on Paper and Spigot and the servers built on them. They answer some things
    // differently from vanilla. A proxy keeps the name of the server behind it.
    public static boolean runsPaper() {
        String brand = brand();
        return brand != null && PAPER_FAMILY.matcher(brand).find();
    }

    public static int online() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? 0 : player.connection.getOnlinePlayers().size();
    }

    // Your own round trip time in milliseconds. Nought until the tab list has you.
    public static int ping() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return 0;
        }
        PlayerInfo info = player.connection.getPlayerInfo(player.getUUID());
        return info == null ? 0 : info.getLatency();
    }

    // The server tick rate to one decimal place.
    public static String tps() {
        return String.format(Locale.ROOT, "%.1f", TickRate.INSTANCE.tps());
    }
}
