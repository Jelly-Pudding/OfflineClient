package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KillEvent;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerWatch;
import net.minecraft.client.gui.Font;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

// The kills are noted whether or not the piece is on.
public final class KillFeedElement extends RowsElement {

    private static final int MOST_KEPT = 100;

    private static final long SAMPLE_AGE = TimeUnit.SECONDS.toMillis(90);

    private record Kill(String name, long at) {
    }

    private final NumberSetting limit = new NumberSetting("Kill feed rows",
        "Most kills to list.", 5, 1, 20, 1).max(MOST_KEPT);
    private final BoolSetting ages = new BoolSetting("Kill feed ages",
        "Writes how long ago each kill was.", true);
    private final ColorSetting color = new ColorSetting("Kill feed colour",
        "Colour of the names.", 0, 0.6f, 1f, false);

    private final Deque<Kill> kills = new ArrayDeque<>();
    private final ServerWatch server = new ServerWatch();

    public KillFeedElement() {
        super("Kill feed", "The players you killed on this server this session with the newest first.", false,
            0, 55);
        add(limit, ages, color);
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    @Subscribe
    private void onKill(KillEvent event) {
        forgetOtherServer();
        kills.addFirst(new Kill(EntityUtil.nameOf(event.getVictim()), System.currentTimeMillis()));
        while (kills.size() > MOST_KEPT) {
            kills.removeLast();
        }
    }

    // The feed belongs to one server and starts again on any other.
    private void forgetOtherServer() {
        if (server.changed()) {
            kills.clear();
        }
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null && !shown().isEmpty();
    }

    private List<Kill> shown() {
        forgetOtherServer();
        if (kills.isEmpty() && editing()) {
            long now = System.currentTimeMillis();
            return List.of(new Kill("Steve", now), new Kill("Alex", now - SAMPLE_AGE));
        }
        return kills.stream().limit(limit.getInt()).toList();
    }

    @Override
    protected List<TextRow> rows(Font font) {
        long now = System.currentTimeMillis();
        List<Kill> shown = shown();
        List<TextRow> rows = new ArrayList<>(shown.size());
        for (int row = 0; row < shown.size(); row++) {
            Kill kill = shown.get(row);
            List<String> parts = new ArrayList<>(List.of(EntityUtil.displayName(kill.name())));
            List<Integer> colors = new ArrayList<>(List.of(color.rowColor(row)));
            if (ages.isOn()) {
                parts.add(" " + ChatUtil.shortAge(now - kill.at()));
                colors.add(RenderUtil.MUTED_TEXT);
            }
            rows.add(TextRow.of(font, parts, colors));
        }
        return rows;
    }
}
