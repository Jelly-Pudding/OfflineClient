package com.jellypudding.offlineclient.rig.duo;

import com.jellypudding.offlineclient.rig.core.Heard;
import com.jellypudding.offlineclient.rig.core.Rig;
import com.jellypudding.offlineclient.rig.core.RigFailure;
import com.jellypudding.offlineclient.rig.core.World;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The world of the Paper server seen through its console. A command is typed there and the
// server itself says whether it took it.
public final class PaperWorld extends World {

    // The server keeps the outcome of the last command in two scores of this name.
    private static final String HOLDER = "rig";
    private static final String DONE = "rigok";
    private static final String VALUE = "rigval";
    private static final Pattern SCORE = Pattern.compile(HOLDER + " has (-?\\d+) \\[(\\w+)]");

    private static final int JOIN_TICKS = 600;
    private static final int HEAR_TICKS = 100;
    private static final String LIST_NAME = "Rig";

    private final PaperServer server;
    private int owed;

    private PaperWorld(Rig rig, PaperServer server, String player) {
        super(rig, player);
        this.server = server;
    }

    // Joins the server at the address and waits until the player stands in its world. The game
    // of the second player has no console to type at and hands in no server.
    static PaperWorld join(Rig rig, PaperServer server, String address) {
        ServerData entry = new ServerData(LIST_NAME, address, ServerData.Type.OTHER);
        // A pack the server sends is taken without a question the way a player who once said yes takes it.
        entry.setResourcePackStatus(ServerData.ServerPackStatus.ENABLED);
        rig.act(mc -> ConnectScreen.startConnecting(mc.gui.screen(), mc, ServerAddress.parseString(address), entry,
            false, null));
        for (int waited = 0; waited <= JOIN_TICKS; waited++) {
            boolean refused = rig.ask(mc -> mc.gui.screen() instanceof DisconnectedScreen);
            if (refused) {
                throw new RigFailure("The server at " + address + " turned the player away");
            }
            if (rig.ask(mc -> mc.player != null && mc.level != null && mc.gui.screen() == null)) {
                rig.ticks(SYNC_TICKS);
                return new PaperWorld(rig, server, rig.ask(mc -> mc.getUser().getName()));
            }
            rig.ticks(1);
        }
        throw new RigFailure("The player was not in the world of " + address + " after " + JOIN_TICKS + " ticks");
    }

    // A command with a mistake in it never runs and the score stays where it was put before.
    @Override
    protected int perform(String command, List<String> said) {
        if (server == null) {
            throw new RigFailure("Only the game of the first player types at the server's console");
        }
        List<String> answer = server.console(List.of(
            "scoreboard players set " + HOLDER + " " + DONE + " -1",
            "execute store success score " + HOLDER + " " + DONE + " store result score " + HOLDER + " " + VALUE
                + " run " + command,
            "scoreboard players get " + HOLDER + " " + DONE,
            "scoreboard players get " + HOLDER + " " + VALUE,
            mark()));
        int done = -1;
        int value = 0;
        for (String line : answer) {
            Matcher score = SCORE.matcher(line);
            if (!score.find()) {
                said.add(line);
            } else if (score.group(2).equals(DONE)) {
                done = Integer.parseInt(score.group(1));
            } else {
                value = Integer.parseInt(score.group(1));
            }
        }
        return done > 0 ? Math.max(value, 0) : -1;
    }

    // The server takes them all in one go.
    @Override
    protected void performAll(List<String> commands) {
        List<String> lines = new ArrayList<>(commands);
        lines.add(mark());
        server.console(lines);
    }

    // The line that makes the server send a mark behind what the lines before it changed.
    private String mark() {
        owed = Heard.next();
        return "probe heard " + player() + " " + Heard.group(owed);
    }

    // A game with no line to the server hears nothing more. Linger keeps the world of one.
    @Override
    protected void catchUp() {
        int mark = owed;
        rig.until("the game to hear from the server", HEAR_TICKS, mc -> !connected(mc) || Heard.took(mark));
    }

    private static boolean connected(Minecraft mc) {
        return mc.player != null && mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
    }

    // Leaves the server. The server itself runs on.
    @Override
    protected void close() {
        rig.act(mc -> {
            if (mc.level != null) {
                mc.disconnectFromWorld(Component.literal("The rig left"));
            }
            mc.gui.setScreen(new TitleScreen());
        });
        rig.ticks(SYNC_TICKS);
    }

    @Override
    public Map<String, String> facts() {
        return server.player(player());
    }

    @Override
    public void foodLevel(int points) {
        quietly("probe food " + player() + " " + points);
    }

    @Override
    public String blockAt(BlockPos pos) {
        return server.block(pos.getX(), pos.getY(), pos.getZ());
    }
}
