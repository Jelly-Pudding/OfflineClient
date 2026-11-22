package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatPace;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.SentLine;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Shortcuts;
import com.jellypudding.offlineclient.util.ShownChat;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MassTpa extends Module {

    private final TextSetting command = new TextSetting("Command",
        "The teleport command such as tpa sent once for each player with their name on the end or where you put"
            + " {player}. Click to type it.", "tpa");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between one request and the next.", 20, 1, 200, 1, " ticks");
    private final BoolSetting ignoreErrors = new BoolSetting("Ignore errors",
        "Keeps going when the server says the command does not exist.", false);
    private final BoolSetting stopWhenAccepted = new BoolSetting("Stop when accepted",
        "Stops asking once somebody accepts a request.", true);

    private final List<String> players = new ArrayList<>();
    private int next;
    private int timer;

    public MassTpa() {
        super("MassTPA", "Sends a teleport request to every player on the server.", Category.MISC);
        addSettings(command, delay, ignoreErrors, stopWhenAccepted);
        searchTags("mass tpa", "teleport request", "tpa spam");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return players.isEmpty() ? null : next + " of " + players.size();
    }

    @Override
    protected void onEnable() {
        players.clear();
        next = 0;
        timer = 0;
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        if (command.isBlank()) {
            disable("MassTPA needs a command such as tpa.");
            return;
        }
        players.addAll(ServerInfo.othersOnline());
        Collections.shuffle(players);
        if (players.isEmpty()) {
            disable("Nobody else is online.");
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (timer-- > 0) {
            return;
        }
        if (next >= players.size()) {
            ChatUtil.message("Every player has been asked.");
            setEnabled(false);
            return;
        }
        if (!ChatPace.MACRO.say(SentLine.of(SentLine.naming(command.getValue()), "player", players.get(next)))) {
            return;
        }
        next++;
        timer = delay.getInt() - 1;
    }

    // Fired on the netty thread. Telling the server's reply from a player's line reads the tab
    // list on the game thread.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundSystemChatPacket packet) {
            mc.schedule(() -> readReply(packet));
        }
    }

    private void readReply(ClientboundSystemChatPacket packet) {
        String reply = ShownChat.serverText(packet);
        if (reply == null) {
            return;
        }
        String text = reply.toLowerCase(Locale.ROOT);
        if (!ignoreErrors.isOn() && (text.contains("/help") || text.contains("permission"))) {
            stop("This server has no " + Shortcuts.commandWord(command.getValue()) + " command.");
        } else if (stopWhenAccepted.isOn() && text.contains("accepted") && text.contains("request")) {
            stop("Somebody accepted the request.");
        }
    }

    private void stop(String why) {
        if (isEnabled()) {
            ChatUtil.message(why);
            setEnabled(false);
        }
    }
}
