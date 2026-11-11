package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

public final class ServerInfoElement extends RowsElement {

    private final BoolSetting address = new BoolSetting("Show address",
        "Writes the address you are connected to.", true);
    private final BoolSetting players = new BoolSetting("Show players online",
        "Writes how many are on the tab list.", true);
    private final BoolSetting tps = new BoolSetting("Show server tps",
        "Writes how fast the server is ticking.", true);
    private final BoolSetting ping = new BoolSetting("Show ping",
        "Writes your own round trip time.", true);
    private final ColorSetting color = labelColor("Server info colour",
        "Colour of the rows.");

    public ServerInfoElement() {
        super("Server info", "Where you are and how the server is doing.", false, 100, 96);
        add(address, players, tps, ping, color);
    }

    @Override
    public boolean visible() {
        return isActive() && !lines().isEmpty();
    }

    private List<String> lines() {
        List<String> lines = new ArrayList<>(4);
        if (OfflineClient.MC.player == null) {
            return lines;
        }
        if (address.isOn()) {
            String ip = ServerInfo.address();
            lines.add(ip == null ? "Single player" : ip);
        }
        if (players.isOn()) {
            lines.add(ServerInfo.online() + " online");
        }
        if (tps.isOn()) {
            lines.add(ServerInfo.tps() + " tps");
        }
        if (ping.isOn()) {
            lines.add(ServerInfo.ping() + " ms");
        }
        return lines;
    }

    @Override
    protected List<TextRow> rows(Font font) {
        return TextRow.lines(font, lines(), color);
    }
}
