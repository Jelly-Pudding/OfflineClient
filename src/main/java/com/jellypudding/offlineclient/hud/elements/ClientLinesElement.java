package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.hud.TextRow;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ClientLines;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// The client's own messages whilst BetterChat sends them here. The newest sits at the bottom
// the way chat does. A line fades once its time is up and the open chat shows every row
// whatever its age. A long line wraps onto rows of its own.
public final class ClientLinesElement extends HudElement {

    // One wrapped row and how strongly it shows from nought to one.
    private record Row(FormattedCharSequence text, int width, float alpha) {
    }

    private static final double MILLIS_PER_SECOND = 1000;

    // How long a line takes to fade out once its time is up.
    private static final float FADE_MILLIS = 1000;

    // Lines wrap at about the width of the chat.
    private static final int WRAP_WIDTH = 320;

    // Two lines stand in whilst the editor is open and nothing has come yet.
    private static final List<Component> SAMPLE = List.of(
        Component.literal("§7The client's own messages show here."),
        Component.literal("§bSteve §7entered your render distance."));

    private final NumberSetting fade = new NumberSetting("Client lines fade",
        "Seconds a line stays before it fades away.", 10, 1, 60, 1, " seconds").min(1);
    private final NumberSetting rows = new NumberSetting("Client lines rows",
        "How many lines the panel shows at once.", 6, 1, 20, 1).min(1);
    private final RowCache<Row> shown = new RowCache<>(this::build);

    public ClientLinesElement() {
        super("Client lines", "The client's own messages in a panel of their own.", false, 25, 12);
        add(fade, rows);
    }

    @Override
    public boolean visible() {
        return isActive() && !rows().isEmpty();
    }

    private List<Row> rows() {
        List<Row> built = shown.get();
        if (!built.isEmpty() || !editing()) {
            return built;
        }
        List<Row> sample = new ArrayList<>();
        for (Component line : SAMPLE) {
            sample.addAll(wrapped(OfflineClient.MC.font, line, 1).reversed());
        }
        return sample;
    }

    private List<Row> build() {
        Font font = OfflineClient.MC.font;
        boolean chatOpen = OfflineClient.MC.gui.screen() instanceof ChatScreen;
        long now = System.currentTimeMillis();
        List<Row> newestFirst = new ArrayList<>();
        for (ClientLines.Line line : ClientLines.newest(rows.getInt())) {
            float alpha = chatOpen ? 1 : strength(now - line.at());
            if (alpha > 0) {
                newestFirst.addAll(wrapped(font, line.text(), alpha));
            }
            if (newestFirst.size() >= rows.getInt()) {
                break;
            }
        }
        List<Row> kept = new ArrayList<>(newestFirst.subList(0, Math.min(rows.getInt(), newestFirst.size())));
        Collections.reverse(kept);
        return kept;
    }

    // The rows of one line with its last row first.
    private static List<Row> wrapped(Font font, Component line, float alpha) {
        List<Row> parts = new ArrayList<>();
        for (FormattedCharSequence part : font.split(line, WRAP_WIDTH)) {
            parts.addFirst(new Row(part, font.width(part), alpha));
        }
        return parts;
    }

    // Full until the line's time is up and down to nought over the fade after it.
    private float strength(long age) {
        double left = fade.getValue() * MILLIS_PER_SECOND - age;
        return (float) Math.clamp(1 + left / FADE_MILLIS, 0, 1);
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Row> drawn = rows();
        int widest = widest(drawn);
        int y = 0;
        for (Row row : drawn) {
            int x = (int) Math.round((widest - row.width()) * alignment());
            context.text(font, row.text(), x, y, ARGB.white(row.alpha()), textShadow());
            y += TextRow.LINE;
        }
    }

    private static int widest(List<Row> drawn) {
        int widest = 1;
        for (Row row : drawn) {
            widest = Math.max(widest, row.width());
        }
        return widest;
    }

    @Override
    public int width(Font font) {
        return widest(rows());
    }

    @Override
    public int height(Font font) {
        return TextRow.height(font, rows().size());
    }
}
