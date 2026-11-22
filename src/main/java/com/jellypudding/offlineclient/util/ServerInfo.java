package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ServerInfo {

    // Vanilla gives this label to a direct connect and to a list entry nobody named.
    private static final String UNNAMED_KEY = "selectServer.defaultName";

    private static final Pattern PAPER_FAMILY = Pattern.compile(
        "paper|purpur|pufferfish|folia|spigot|bukkit|leaves|gale", Pattern.CASE_INSENSITIVE);

    private static final String SINGLE_PLAYER = "singleplayer";

    // What the game calls the permissions of a player who may run no commands.
    private static final String NO_PERMISSIONS = "none";

    // A packet can wait up to a tick at each end of the trip.
    private static final int ANSWER_SLACK_TICKS = 2;

    private static final Map<String, String> DIMENSION_NAMES = Map.of(
        Level.OVERWORLD.identifier().toString(), "Overworld",
        Level.NETHER.identifier().toString(), "Nether",
        Level.END.identifier().toString(), "End");

    private ServerInfo() {
    }

    // Null in single player.
    public static String address() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        return data == null ? null : data.ip;
    }

    // A stable name for where you are playing. Saved data is filed under it. Each single
    // player world has its own named after its save folder.
    public static String key() {
        String address = address();
        if (address != null) {
            return address.toLowerCase(Locale.ROOT);
        }
        String save = saveName();
        return save == null ? SINGLE_PLAYER : SINGLE_PLAYER + "/" + save;
    }

    public static String saveName() {
        // The level id is the folder name. The world path would be read through a cache the
        // server thread fills at the same time.
        IntegratedServer local = OfflineClient.MC.getSingleplayerServer();
        return local == null ? null : local.storageSource.getLevelId();
    }

    // Whether data filed under a saved key belongs to the place with this key. Plain
    // singleplayer covers every single player world.
    public static boolean matches(String saved, String key) {
        return saved.equals(key) || saved.equals(SINGLE_PLAYER) && key.startsWith(SINGLE_PLAYER + "/");
    }

    public static String dimension() {
        ClientLevel level = OfflineClient.MC.level;
        return level == null ? "" : level.dimension().identifier().toString();
    }

    // The server and the dimension together. Data kept apart for every world is filed
    // under it. Empty outside a world.
    public static String worldKey() {
        String dimension = dimension();
        return dimension.isEmpty() ? "" : key() + " " + dimension;
    }

    public static String dimensionName(String dimension) {
        String known = DIMENSION_NAMES.get(dimension);
        if (known != null) {
            return known;
        }
        String words = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ');
        return ChatUtil.capitalised(words);
    }

    public static List<String> dimensions() {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        Stream<String> listed = connection == null ? Stream.empty()
            : connection.levels().stream().map(level -> level.identifier().toString()).sorted();
        return Stream.concat(Arrays.stream(Dimension.values()).map(Dimension::id), listed).distinct().toList();
    }

    // Null until you name the server.
    public static String savedName() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        if (data == null || data.name == null || data.name.isBlank()
            || data.name.equals(I18n.get(UNNAMED_KEY))) {
            return null;
        }
        return data.name;
    }

    public static Component motd() {
        ServerData data = OfflineClient.MC.getCurrentServer();
        if (data == null || data.motd == null || data.motd.getString().isBlank()) {
            return null;
        }
        return data.motd;
    }

    // Null until the server says.
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

    // A server can add hidden entries such as the figures it gives a skin and those are left out.
    public static int online() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? 0 : player.connection.getListedOnlinePlayers().size();
    }

    public static List<String> othersOnline() {
        List<String> names = new ArrayList<>();
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return names;
        }
        String self = player.getGameProfile().name();
        for (PlayerInfo info : player.connection.getListedOnlinePlayers()) {
            String name = info.getProfile().name();
            if (!name.isBlank() && !name.equalsIgnoreCase(self)) {
                names.add(name);
            }
        }
        return names;
    }

    public static String permissionLevel() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? NO_PERMISSIONS : Player.printPlayerPermissions(player.permissions());
    }

    // Nought until the tab list has you.
    public static int ping() {
        LocalPlayer player = OfflineClient.MC.player;
        return player == null ? 0 : Math.max(0, latency(player.getUUID()));
    }

    // Minus one when the tab list does not have the player.
    public static int latency(UUID player) {
        ClientPacketListener connection = OfflineClient.MC.getConnection();
        return latency(connection == null ? null : connection.getPlayerInfo(player));
    }

    public static int latency(PlayerInfo info) {
        return info == null ? -1 : Math.max(-1, info.getLatency());
    }

    public static int pingTicks() {
        return Math.round(ping() / (float) SharedConstants.MILLIS_PER_TICK);
    }

    // Ticks until the server's answer to a click sent now has arrived. A block it turned
    // down has gone back by then.
    public static int answerTicks() {
        return pingTicks() + ANSWER_SLACK_TICKS;
    }

    public static String tps() {
        return oneDecimal(TickRate.INSTANCE.tps());
    }

    public static String liveTps() {
        return oneDecimal(TickRate.INSTANCE.liveTps());
    }

    private static String oneDecimal(float rate) {
        return String.format(Locale.ROOT, "%.1f", rate);
    }
}
