package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.DataFiles;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ChunkOrigin;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

// What the server told the client about the world you are in. The same lines can go into a file
// kept for each server.
public final class WorldCommand extends Command {

    private static final String SAVE = "save";
    private static final String MARK = "mark";
    private static final String SPAWN = "spawn";
    private static final String DEATH = "death";
    private static final List<String> VERBS = List.of(SAVE, MARK);
    private static final List<String> MARKS = List.of(SPAWN, DEATH);

    private static final String SPAWN_WAYPOINT = "Spawn";
    private static final String DEATH_WAYPOINT = "LastDeath";

    // The game version whose terrain each dimension last changed in. ChunkOrigin tells chunks from
    // before it apart.
    private static final Map<ResourceKey<Level>, String> TERRAIN_VERSIONS = Map.of(
        Level.OVERWORLD, "1.18", Level.NETHER, "1.16", Level.END, "1.13");

    // A Java name or a Bedrock one behind the dot Floodgate puts in front. Sidebar lines and entity
    // ids on a scoreboard never fit it.
    private static final Pattern PLAYER_NAME = Pattern.compile("\\.?\\w{2,16}");
    private static final String BEDROCK_PREFIX = ".";

    // One line of the report. The link after the value stays out of the saved file.
    private record Row(String label, String value, Component link) {
        private Row(String label, String value) {
            this(label, value, null);
        }
    }

    public WorldCommand() {
        super("world", "Shows what the server told you about this world and can save it to a file.",
            "world [save] or world mark <spawn|death>");
    }

    @Override
    public void execute(String[] args) {
        LocalPlayer player = OfflineClient.MC.player;
        ClientLevel level = OfflineClient.MC.level;
        if (player == null || level == null) {
            return;
        }
        String verb = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (verb.isEmpty()) {
            report(player, level).forEach(WorldCommand::print);
        } else if (verb.equals(SAVE) && args.length == 1) {
            save(player, level);
        } else if (verb.equals(MARK) && args.length == 2 && MARKS.contains(args[1].toLowerCase(Locale.ROOT))) {
            mark(player, level, args[1].toLowerCase(Locale.ROOT));
        } else {
            usage();
        }
    }

    private static List<Row> report(LocalPlayer player, ClientLevel level) {
        List<Row> rows = new ArrayList<>();
        ChunkPos chunk = player.chunkPosition();
        rows.add(new Row("Chunk", chunk.x() + " " + chunk.z()));
        String version = TERRAIN_VERSIONS.get(level.dimension());
        if (version != null) {
            boolean older = ChunkOrigin.oldVersionSection(level.getChunk(chunk.x(), chunk.z())) >= 0;
            rows.add(new Row("Terrain", (older ? "generated before " : "no sign of a version before ") + version));
        }
        addBorder(rows, level.getWorldBorder());
        LevelData.RespawnData spawn = level.getLevelData().getRespawnData();
        rows.add(new Row("World spawn", place(spawn.globalPos()), markLink(SPAWN)));
        rows.add(player.getLastDeathLocation()
            .map(death -> new Row("Last death", place(death), markLink(DEATH)))
            .orElseGet(() -> new Row("Last death", "none known")));
        rows.add(new Row("Difficulty", difficulty(level.getLevelData())));
        rows.add(new Row("Permissions", ServerInfo.permissionLevel()));
        rows.add(new Row("Simulation distance", Tally.counted(level.getServerSimulationDistance(), "chunk")));
        rows.add(new Row("Day", String.valueOf(day(level))));
        rows.add(new Row("World age", Tally.counted((int) (level.getGameTime() / SharedConstants.TICKS_PER_GAME_DAY),
            "day")));
        addOfflineNames(rows, player, level.getScoreboard());
        return rows;
    }

    // A moving border also says where it is going and how long it has left.
    private static void addBorder(List<Row> rows, WorldBorder border) {
        rows.add(new Row("Border", "x " + number(border.getMinX()) + " to " + number(border.getMaxX())
            + " and z " + number(border.getMinZ()) + " to " + number(border.getMaxZ())));
        String size = number(border.getSize()) + " around " + number(border.getCenterX()) + " "
            + number(border.getCenterZ());
        if (border.getLerpTime() > 0) {
            size += " moving to " + number(border.getLerpTarget()) + " in "
                + ChatUtil.duration(border.getLerpTime() / (double) SharedConstants.TICKS_PER_SECOND);
        }
        rows.add(new Row("Border size", size));
    }

    private static String difficulty(LevelData data) {
        String text = data.getDifficulty().getDisplayName().getString();
        if (data.isDifficultyLocked()) {
            text += " and locked";
        }
        return data.isHardcore() ? text + " in hardcore" : text;
    }

    // TimeChanger answers the clock with a time of its own. The field keeps what the server sent.
    private static long day(ClientLevel level) {
        long ticks = level.registryAccess().get(WorldClocks.OVERWORLD)
            .map(clock -> level.clockManager().getInstance(clock).totalTicks)
            .orElse(0L);
        return ticks / SharedConstants.TICKS_PER_GAME_DAY;
    }

    // Names the scoreboard knows that the tab list does not show. The server sends the scores of
    // shown objectives only and teams list their offline members too.
    private static void addOfflineNames(List<Row> rows, LocalPlayer player, Scoreboard scoreboard) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ScoreHolder holder : scoreboard.getTrackedPlayers()) {
            names.add(holder.getScoreboardName());
        }
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            names.addAll(team.getPlayers());
        }
        names.removeIf(name -> !PLAYER_NAME.matcher(name).matches() || player.connection.getPlayerInfo(name) != null);
        List<String> java = names.stream().filter(name -> !name.startsWith(BEDROCK_PREFIX)).toList();
        List<String> bedrock = names.stream().filter(name -> name.startsWith(BEDROCK_PREFIX)).toList();
        if (java.isEmpty()) {
            rows.add(new Row("Offline names", "none the scoreboard shows"));
        } else {
            rows.add(namesRow("offline name", java));
        }
        if (!bedrock.isEmpty()) {
            rows.add(namesRow("offline Bedrock name", bedrock));
        }
    }

    private static Row namesRow(String kind, List<String> names) {
        String all = String.join(" ", names);
        return new Row(Tally.counted(names.size(), kind), all,
            ChatUtil.copyOnClick(Component.literal("§8[§7copy§8]"), all));
    }

    // A value too long for chat is left to the copy link after it. The saved file keeps it whole.
    private static void print(Row row) {
        String value = row.value().length() > ChatUtil.LONGEST_SHOWN ? "too many to show" : row.value();
        ChatUtil.row(row.label(), value, row.link());
    }

    // Coordinates and the dimension they are in.
    private static String place(GlobalPos pos) {
        String dimension = ServerInfo.dimensionName(pos.dimension().identifier().toString());
        return BlockUtil.text(pos.pos()) + " in the " + dimension;
    }

    private static Component markLink(String what) {
        String command = OfflineClient.INSTANCE.getCommandManager().getPrefix() + "world " + MARK + " " + what;
        return Component.literal("§8[§7waypoint§8]").withStyle(style -> style
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to save it as a waypoint"))));
    }

    // Whole numbers lose their point. Border edges sit on half blocks when the centre does.
    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static void save(LocalPlayer player, ClientLevel level) {
        Path file = DataFiles.path("worlds", DataFiles.keyName(ServerInfo.key()) + ".txt");
        List<String> lines = new ArrayList<>();
        lines.add(DataFiles.timestamp() + " " + ServerInfo.dimensionName(level.dimension().identifier().toString())
            + " at " + BlockUtil.text(player.blockPosition()));
        for (Row row : report(player, level)) {
            lines.add(row.label() + " " + row.value());
        }
        lines.add("");
        if (!DataFiles.appendLines(file, lines)) {
            ChatUtil.error("The report could not be saved. The game log says why.");
            return;
        }
        ChatUtil.component(Component.literal("§7Saved the report to ").append(ChatUtil.fileLink(file)));
    }

    // Each waypoint goes into the dimension of its spot whichever one you stand in.
    private static void mark(LocalPlayer player, ClientLevel level, String what) {
        boolean spawn = what.equals(SPAWN);
        GlobalPos spot = spawn ? level.getLevelData().getRespawnData().globalPos()
            : player.getLastDeathLocation().orElse(null);
        if (spot == null) {
            ChatUtil.error("The server has not said where you last died.");
            return;
        }
        String name = spawn ? SPAWN_WAYPOINT : DEATH_WAYPOINT;
        String dimension = spot.dimension().identifier().toString();
        WaypointStore.get().mark(name, spot.pos(), dimension, Modules.nextWaypointHue());
        ChatUtil.message("§7Saved the waypoint §b" + name + " §7in the " + ServerInfo.dimensionName(dimension) + ".");
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, VERBS);
        }
        if (index == 2 && tokens[1].equalsIgnoreCase(MARK)) {
            return CommandManager.filter(current, MARKS);
        }
        return List.of();
    }
}
