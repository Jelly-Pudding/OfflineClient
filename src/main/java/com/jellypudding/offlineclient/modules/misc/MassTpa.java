package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// Asks everyone online for a teleport one after another. Whoever says yes first wins.
public final class MassTpa extends Module {

    private final TextSetting command = new TextSetting("Command",
        "The teleport command such as tpa or tpahere. Click to type it.", "tpa");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between one request and the next.", 20, 1, 200, 1, " ticks").min(1);
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
        String own = mc.getUser().getName();
        for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (!name.equalsIgnoreCase(own)) {
                players.add(name);
            }
        }
        Collections.shuffle(players);
        if (players.isEmpty()) {
            ChatUtil.error("Nobody else is online.");
            setEnabled(false);
        }
    }

    private String commandName() {
        String name = command.getValue().trim();
        return name.startsWith("/") ? name.substring(1) : name;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || timer-- > 0) {
            return;
        }
        if (next >= players.size()) {
            ChatUtil.message("Every player has been asked.");
            setEnabled(false);
            return;
        }
        mc.getConnection().sendCommand(commandName() + " " + players.get(next++));
        timer = delay.getInt() - 1;
    }

    // Fired on the netty thread. The reply from the server says how it went.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundSystemChatPacket packet)) {
            return;
        }
        String text = packet.content().getString().toLowerCase(Locale.ROOT);
        if (!ignoreErrors.isOn() && (text.contains("/help") || text.contains("permission"))) {
            mc.schedule(() -> stop("This server has no " + commandName() + " command."));
        } else if (stopWhenAccepted.isOn() && text.contains("accepted") && text.contains("request")) {
            mc.schedule(() -> stop("Somebody accepted the request."));
        }
    }

    private void stop(String why) {
        if (isEnabled()) {
            ChatUtil.message(why);
            setEnabled(false);
        }
    }
}
