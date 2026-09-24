package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

public final class ServerInfoElement extends HudElement {

    private static final int LINE = 10;

    private final BoolSetting address = new BoolSetting("Show address",
        "Write the address you are connected to.", true);
    private final BoolSetting players = new BoolSetting("Show players online",
        "Write how many are on the tab list.", true);
    private final BoolSetting tps = new BoolSetting("Show server tps",
        "Write how fast the server is ticking.", true);
    private final BoolSetting ping = new BoolSetting("Show ping",
        "Write your own round trip time.", true);
    private final ColorSetting color = new ColorSetting("Server info colour",
        "Colour of the rows.", 190, 0.15f, 0.85f, false);

    public ServerInfoElement() {
        super("Server info", "Where you are and how the server is doing.", false, 100, 96);
        add(address, players, tps, ping, color);
    }

    @Override
    public boolean visible() {
        return isActive() && !rows().isEmpty();
    }

    private List<String> rows() {
        List<String> rows = new ArrayList<>(4);
        if (OfflineClient.MC.player == null) {
            return rows;
        }
        if (address.isOn()) {
            String ip = ServerInfo.address();
            rows.add(ip == null ? "Single player" : ip);
        }
        if (players.isOn()) {
            rows.add(ServerInfo.online() + " online");
        }
        if (tps.isOn()) {
            rows.add(ServerInfo.tps() + " tps");
        }
        if (ping.isOn()) {
            rows.add(ServerInfo.ping() + " ms");
        }
        return rows;
    }

    // Rows are right aligned. The block hugs the edge of the screen.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        int widest = width(font);
        int y = 0;
        for (String row : rows()) {
            context.text(font, row, widest - font.width(row), y, color.getColor(), true);
            y += LINE;
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (String row : rows()) {
            widest = Math.max(widest, font.width(row));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return Math.max(LINE, rows().size() * LINE);
    }
}
