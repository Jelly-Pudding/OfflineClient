package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.RowsElement;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Leak;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.ServerInfo;
import net.minecraft.client.gui.Font;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LeaksElement extends RowsElement {

    private static final String NO_SOURCE = "Switch on Locator or Eavesdrop or ItemCoords";

    private static final BlockPos SAMPLE_POS = new BlockPos(1250, 64, -3400);
    private static final long SAMPLE_AGE = TimeUnit.SECONDS.toMillis(90);
    private static final int SAMPLE_COLOUR = 0xFF40FF40;

    private final NumberSetting limit = new NumberSetting("Leak rows",
        "Most rows to draw.", 8, 1, 30, 1);
    private final BoolSetting ages = new BoolSetting("Leak ages",
        "Writes how long ago each place was last known to be right.", true);
    private final RowCache<TextRow> rows = new RowCache<>(this::build);

    public LeaksElement() {
        super("Coordinate leaks", "Lists the players and places that Locator and Eavesdrop and ItemCoords find.",
            false, 100, 70);
        add(limit, ages);
    }

    @Override
    protected List<TextRow> rows(Font font) {
        List<TextRow> built = rows.get();
        if (!built.isEmpty() || !editing()) {
            return built;
        }
        long now = System.currentTimeMillis();
        Leak sample = new Leak("Steve", "exact", SAMPLE_POS, true, null, now - SAMPLE_AGE, SAMPLE_COLOUR);
        return List.of(rowOf(font, sample, now));
    }

    private List<TextRow> build() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return List.of();
        }
        Font font = ClientFonts.hud();
        if (!Leak.anySourceOn()) {
            return List.of(TextRow.of(font, NO_SOURCE, RenderUtil.MUTED_TEXT));
        }
        List<Leak> leaks = Leak.all();
        leaks.sort(Comparator.comparingLong(Leak::time).reversed()
            .thenComparingDouble(leak -> leak.pos().distToCenterSqr(player.getX(), leak.pos().getY(), player.getZ())));
        long now = System.currentTimeMillis();
        List<TextRow> built = new ArrayList<>();
        for (Leak leak : leaks.subList(0, Math.min(limit.getInt(), leaks.size()))) {
            built.add(rowOf(font, leak, now));
        }
        return built;
    }

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
}
