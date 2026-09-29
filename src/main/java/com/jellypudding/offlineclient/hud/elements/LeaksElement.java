package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.Backdrop;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

// The places Locator and Eavesdrop and ItemCoords learnt from the server whilst they are
// on. The newest come first and the nearest break a tie. Each row lines up with the side
// the element is anchored to.
public final class LeaksElement extends HudElement {

    // The rows are gathered afresh this often. Each gathering asks every source and sorts
    // what they hold.
    private static final long REBUILD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final NumberSetting limit = new NumberSetting("Leak rows",
        "Most rows to draw.", 8, 1, 30, 1).min(1);
    private final BoolSetting ages = new BoolSetting("Leak ages",
        "Writes how long ago each place was last known to be right.", true);
    private final Backdrop backdrop = new Backdrop("Leak", "the rows", false);

    // Null until first built.
    private List<TextRow> rows;
    private long builtAt;

    public LeaksElement() {
        super("Coordinate leaks", "Places the server gave away with their coordinates. Locator and Eavesdrop and"
            + " ItemCoords fill it whilst they are on.", false, 100, 70);
        add(limit, ages);
        add(backdrop.settings());
    }

    @Override
    public boolean visible() {
        return isActive() && !rows(OfflineClient.MC.font).isEmpty();
    }

    private List<TextRow> rows(Font font) {
        long now = System.nanoTime();
        if (rows == null || now - builtAt >= REBUILD_NANOS) {
            rows = build(font);
            builtAt = now;
        }
        return rows;
    }

    private List<TextRow> build(Font font) {
        LocalPlayer player = OfflineClient.MC.player;
        List<TextRow> built = new ArrayList<>();
        if (player == null) {
            return built;
        }
        List<Leak> leaks = Leak.all();
        leaks.sort(Comparator.comparingLong(Leak::time).reversed()
            .thenComparingDouble(leak -> leak.pos().distToCenterSqr(player.getX(), leak.pos().getY(), player.getZ())));
        long now = System.currentTimeMillis();
        for (Leak leak : leaks.subList(0, Math.min(limit.getInt(), leaks.size()))) {
            built.add(rowOf(font, leak, now));
        }
        return built;
    }

    // The name in the colour of what found it and the rest in plain or muted text. A place in
    // another dimension names it.
    private TextRow rowOf(Font font, Leak leak, long now) {
        List<String> parts = new ArrayList<>(List.of(leak.name(), " " + leak.detail(), " " + leak.coordinates()));
        List<Integer> colors = new ArrayList<>(List.of(leak.color(), RenderUtil.MUTED_TEXT, GuiTheme.HUD_TEXT));
        if (leak.dimension() != null && !leak.dimension().equals(ServerInfo.dimension())) {
            parts.add(" " + ServerInfo.dimensionName(leak.dimension()));
            colors.add(RenderUtil.MUTED_TEXT);
        }
        if (ages.isOn()) {
            parts.add(" " + ChatUtil.shortAge(now - leak.time()));
            colors.add(RenderUtil.MUTED_TEXT);
        }
        return TextRow.of(font, parts, colors);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<TextRow> shown = rows(font);
        backdrop.around(context, TextRow.widest(shown), shown.size() * TextRow.LINE);
        TextRow.draw(context, font, shown, alignment());
    }

    @Override
    public int width(Font font) {
        return TextRow.widest(rows(font));
    }

    @Override
    public int height(Font font) {
        return Math.max(TextRow.LINE, rows(font).size() * TextRow.LINE);
    }
}
