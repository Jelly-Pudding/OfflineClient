package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

// What a tab opens. A box under the tabs holding one row per entry with a
// field to add another. Drag an edge to resize it.
public final class TabView {

    // One row. The detail sits on the right and the tip explains the row.
    public record Entry(String name, String detail, String tip) {

        public Entry(String name) {
            this(name, "", "");
        }
    }

    // What the box is looking at. The client owns the data and this reads it.
    public interface Source {
        List<Entry> entries();

        // Shown when there is nothing to list.
        String emptyText();

        // A line along the bottom saying what the rows do.
        default String footer() {
            return "";
        }

        // What the field at the top asks for. Empty hides the field.
        default String addHint() {
            return "";
        }

        default void add(String text) {
        }

        default void activate(String name) {
        }

        default void drop(String name) {
        }

        // True when clicking the detail listens for a key to put there.
        default boolean bindable() {
            return false;
        }

        default void bind(String name, int key) {
        }
    }

    // A multiplication sign. The closest thing to a cross the font has.
    private static final String DROP = "×";
    private static final String LISTENING = "press a key";
    private static final int DROP_ZONE = 14;
    private static final int EMPTY_HEIGHT = 18;
    private static final int LINE = 10;
    private static final int GRAB = 4;
    private static final int PAD = 2;
    private static final int MIN_WIDTH = 140;
    private static final int MAX_WIDTH = 460;
    private static final int MIN_HEIGHT = 40;
    private static final int DEFAULT_WIDTH = 220;

    // A listing can reach the disk. Often enough to feel live is plenty.
    private static final long REFRESH_MS = 500;

    private final Source source;
    private final SettingWidget.Host host;
    private final ScrollBar scrollBar = new ScrollBar();
    private final TextField adder = new TextField();

    private int width = DEFAULT_WIDTH;
    // Zero leaves the box as tall as its rows need.
    private int height;

    private boolean sizeLeft;
    private boolean sizeRight;
    private boolean sizeBottom;

    private boolean typing;
    private String binding;

    private List<Entry> entries = List.of();
    private long read;

    // Where the box landed this frame. The width and height above are what
    // the user asked for and these are what the window had room for.
    private int left;
    private int top;
    private int wide = MIN_WIDTH;
    private int drawn = MIN_HEIGHT;

    public TabView(Source source, SettingWidget.Host host) {
        this.source = source;
        this.host = host;
        refresh();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setWidth(int value) {
        width = Math.clamp(value, MIN_WIDTH, MAX_WIDTH);
    }

    public void setHeight(int value) {
        height = value <= 0 ? 0 : Math.max(MIN_HEIGHT, value);
    }

    private void refresh() {
        entries = source.entries();
        read = System.currentTimeMillis();
    }

    private List<Entry> entries() {
        if (System.currentTimeMillis() - read > REFRESH_MS) {
            refresh();
        }
        return entries;
    }

    private int contentHeight() {
        int count = entries().size();
        return count == 0 ? EMPTY_HEIGHT : count * GuiTheme.ROW_HEIGHT;
    }

    // The strip the field sits in. Zero when this list cannot be added to.
    private int fieldHeight() {
        return source.addHint().isEmpty() ? 0 : GuiScreenBase.SEARCH_HEIGHT + PAD;
    }

    private int footerHeight() {
        return source.footer().isEmpty() ? 0 : LINE;
    }

    // What the box is allowed to draw at right now. The size the user chose
    // is kept whole even when the window is too small to show it.
    private int drawHeight(int viewH, int under) {
        int wanted = height > 0 ? height
            : PAD * 2 + fieldHeight() + footerHeight() + contentHeight();
        return Math.clamp(wanted, MIN_HEIGHT, Math.max(MIN_HEIGHT, viewH - under - 6));
    }

    private int drawWidth(int viewW) {
        return Math.clamp(width, MIN_WIDTH, Math.max(MIN_WIDTH, viewW - 8));
    }

    private int viewHeight() {
        return Math.max(0, drawn - PAD * 2 - fieldHeight() - footerHeight());
    }

    private int rowRoom() {
        return wide - PAD * 2;
    }

    private int rowsTop() {
        return top + PAD + fieldHeight();
    }

    public boolean isTyping() {
        return typing || binding != null;
    }

    public boolean isOver(double mx, double my) {
        return mx >= left - GRAB && mx < left + wide + GRAB
            && my >= top - GRAB && my < top + drawn + GRAB;
    }

    public void wheel(double amount) {
        int full = contentHeight();
        int view = viewHeight();
        scrollBar.scroll(ScrollBar.wheelDelta(amount, full, view), full, view);
    }

    public void release() {
        sizeLeft = false;
        sizeRight = false;
        sizeBottom = false;
        scrollBar.release();
    }

    // True when the key belonged to this box.
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (binding != null) {
            if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
                source.bind(binding, KeybindSetting.UNBOUND);
            } else if (key != InputConstants.KEY_ESCAPE) {
                source.bind(binding, key);
            }
            binding = null;
            refresh();
            return true;
        }
        if (!typing) {
            return false;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            adder.clear();
            typing = false;
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            commit();
            return true;
        }
        adder.keyPressed(event, TextField.ANY);
        return true;
    }

    public boolean charTyped(char typed) {
        return typing && adder.charTyped(typed, TextField.ANY);
    }

    private void commit() {
        String text = adder.get().trim();
        if (!text.isEmpty()) {
            source.add(text);
            refresh();
        }
        adder.clear();
    }

    public void render(GuiGraphicsExtractor context, int viewW, int viewH, int under,
                       int mouseX, int mouseY) {
        applyResize(viewW, viewH, under, mouseX, mouseY);
        wide = drawWidth(viewW);
        drawn = drawHeight(viewH, under);
        left = (viewW - wide) / 2;
        top = under;

        int full = contentHeight();
        int view = viewHeight();
        scrollBar.update(mouseY, full, view);

        RenderUtil.roundedBorderedRect(context, left, top, left + wide, top + drawn,
            GuiTheme.CORNER + 1, GuiTheme.bgPanel(), GuiTheme.outline());
        context.guiRenderState.up();
        renderGrabBands(context, mouseX, mouseY);

        Font font = OfflineClient.MC.font;
        int rowX = left + PAD;
        if (fieldHeight() > 0) {
            boolean hovered = SettingWidget.isOver(mouseX, mouseY, rowX, top + PAD,
                rowRoom(), GuiScreenBase.SEARCH_HEIGHT);
            GuiScreenBase.searchField(context, font, rowX, top + PAD, rowRoom(), adder,
                source.addHint(), typing, hovered, typing, null);
        }

        int rowW = ScrollBar.rowWidth(rowRoom(), full, view);
        int viewTop = rowsTop();
        context.enableScissor(rowX, viewTop, rowX + rowW, viewTop + view);
        renderRows(context, font, rowX, viewTop, view, rowW, mouseX, mouseY);
        context.disableScissor();

        if (full > view) {
            int trackX = ScrollBar.trackX(rowX, rowRoom());
            scrollBar.render(context, trackX, viewTop, view, full,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, viewTop, view));
        }
        if (footerHeight() > 0) {
            context.text(font, SettingWidget.trimEnd(font, source.footer(), rowRoom()),
                rowX + GuiTheme.PAD - 2, GuiTheme.textY(viewTop + view, LINE),
                GuiTheme.textFaint(), false);
        }
    }

    private void renderRows(GuiGraphicsExtractor context, Font font, int rowX, int viewTop,
                            int view, int rowW, int mouseX, int mouseY) {
        List<Entry> rows = entries();
        if (rows.isEmpty()) {
            context.text(font, source.emptyText(), rowX + GuiTheme.PAD,
                GuiTheme.textY(viewTop, EMPTY_HEIGHT), GuiTheme.textFaint(), false);
            return;
        }
        boolean inView = mouseX >= rowX && mouseX < rowX + rowW
            && mouseY >= viewTop && mouseY < viewTop + view;
        int rowY = viewTop - scrollBar.getOffset();
        for (Entry entry : rows) {
            int h = GuiTheme.ROW_HEIGHT;
            if (rowY + h > viewTop && rowY < viewTop + view) {
                renderRow(context, font, entry, rowX, rowY, rowW, mouseX, mouseY, inView);
            }
            rowY += h;
        }
    }

    private void renderRow(GuiGraphicsExtractor context, Font font, Entry entry, int rowX,
                           int rowY, int rowW, int mouseX, int mouseY, boolean inView) {
        int h = GuiTheme.ROW_HEIGHT;
        boolean hovered = inView && SettingWidget.isOver(mouseX, mouseY, rowX, rowY, rowW, h);
        context.fill(rowX, rowY, rowX + rowW, rowY + h - 1,
            hovered ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
        context.fill(rowX, rowY + h - 1, rowX + rowW, rowY + h, GuiTheme.RULE);
        context.guiRenderState.up();

        int textY = GuiTheme.textY(rowY, h - 1);
        String detail = entry.name().equals(binding) ? LISTENING : entry.detail();
        int detailWidth = detail.isEmpty() ? 0 : font.width(detail) + 6;
        int detailX = rowX + rowW - DROP_ZONE - detailWidth;
        if (!detail.isEmpty()) {
            boolean overDetail = hovered && mouseX >= detailX && mouseX < detailX + detailWidth;
            int colour = entry.name().equals(binding) ? GuiTheme.accentText()
                : (overDetail && source.bindable() ? GuiTheme.text() : GuiTheme.textFaint());
            context.text(font, detail, detailX + 3, textY, colour, false);
        }

        context.text(font, SettingWidget.trimEnd(font, entry.name(), detailX - rowX - GuiTheme.PAD),
            rowX + GuiTheme.PAD, textY, hovered ? GuiTheme.text() : GuiTheme.textDim(), false);

        boolean overDrop = hovered && mouseX >= rowX + rowW - DROP_ZONE;
        context.text(font, DROP, rowX + rowW - DROP_ZONE + (DROP_ZONE - font.width(DROP)) / 2,
            textY, overDrop ? ColorUtil.lerp(GuiTheme.text(), 0xFFFF6B6B, 0.7f)
                : GuiTheme.textFaint(), false);

        if (hovered) {
            host.setTooltip(overDrop ? "Remove " + entry.name() : entry.tip());
        }
    }

    private void renderGrabBands(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int hot = GuiTheme.accent();
        if (sizeLeft || sizeRight || nearLeft(mouseX, mouseY) || nearRight(mouseX, mouseY)) {
            context.fill(left - 1, top, left, top + drawn, hot);
            context.fill(left + wide, top, left + wide + 1, top + drawn, hot);
        }
        if (sizeBottom || nearBottom(mouseX, mouseY)) {
            context.fill(left, top + drawn, left + wide, top + drawn + 1, hot);
        }
        context.guiRenderState.up();
    }

    // The bands sit outside the box. Reaching inwards would cover the
    // scrollbar and the first pixels of every row.
    private boolean nearLeft(double mx, double my) {
        return inRow(my) && mx >= left - GRAB && mx <= left + 1;
    }

    private boolean nearRight(double mx, double my) {
        return inRow(my) && mx >= left + wide - 1 && mx <= left + wide + GRAB;
    }

    private boolean inRow(double my) {
        return my >= top && my <= top + drawn;
    }

    private boolean nearBottom(double mx, double my) {
        return mx >= left - GRAB && mx <= left + wide + GRAB
            && Math.abs(my - (top + drawn)) <= GRAB;
    }

    // The box stays centred. The grabbed side widens it both ways at once
    // and pulling past the middle pins it rather than turning it inside out.
    private void applyResize(int viewW, int viewH, int under, int mouseX, int mouseY) {
        int middle = viewW / 2;
        if (sizeLeft || sizeRight) {
            int half = sizeRight ? mouseX - middle : middle - mouseX;
            setWidth(Math.max(0, half) * 2);
        }
        if (sizeBottom) {
            setHeight(Math.clamp(mouseY - under, MIN_HEIGHT,
                Math.max(MIN_HEIGHT, viewH - under - 6)));
        }
    }

    // True when the click belonged to the box.
    public boolean mouseClicked(double mx, double my, int button) {
        boolean leftEdge = nearLeft(mx, my);
        boolean rightEdge = nearRight(mx, my);
        boolean bottom = nearBottom(mx, my);
        if (leftEdge || rightEdge || bottom) {
            if (InputUtil.isLeft(button)) {
                sizeLeft = leftEdge;
                sizeRight = rightEdge && !leftEdge;
                sizeBottom = bottom;
            } else if (InputUtil.isRight(button)) {
                width = DEFAULT_WIDTH;
                height = 0;
            }
            return true;
        }
        if (mx < left || mx >= left + wide || my < top || my >= top + drawn) {
            typing = false;
            binding = null;
            return false;
        }

        int rowX = left + PAD;
        if (fieldHeight() > 0 && SettingWidget.isOver(mx, my, rowX, top + PAD,
            rowRoom(), GuiScreenBase.SEARCH_HEIGHT)) {
            typing = true;
            binding = null;
            if (GuiScreenBase.overClear(adder, mx, my, rowX, top + PAD, rowRoom())) {
                adder.clear();
            }
            return true;
        }
        typing = false;

        int full = contentHeight();
        int view = viewHeight();
        int viewTop = rowsTop();
        int trackX = ScrollBar.trackX(rowX, rowRoom());
        if (full > view && ScrollBar.isOverTrack(mx, my, trackX, viewTop, view)) {
            scrollBar.beginDrag((int) my);
            return true;
        }
        // The pad above and below the strip belongs to no row.
        if (my < viewTop || my >= viewTop + view || !InputUtil.isLeft(button)) {
            return true;
        }
        clickRow(mx, my, rowX, viewTop, ScrollBar.rowWidth(rowRoom(), full, view));
        return true;
    }

    private void clickRow(double mx, double my, int rowX, int viewTop, int rowW) {
        Font font = OfflineClient.MC.font;
        int rowY = viewTop - scrollBar.getOffset();
        for (Entry entry : entries()) {
            int h = GuiTheme.ROW_HEIGHT;
            if (SettingWidget.isOver(mx, my, rowX, rowY, rowW, h)) {
                int detailWidth = entry.detail().isEmpty() ? 0 : font.width(entry.detail()) + 6;
                int detailX = rowX + rowW - DROP_ZONE - detailWidth;
                if (mx >= rowX + rowW - DROP_ZONE) {
                    source.drop(entry.name());
                } else if (source.bindable() && detailWidth > 0 && mx >= detailX) {
                    binding = entry.name();
                    return;
                } else {
                    source.activate(entry.name());
                }
                binding = null;
                refresh();
                return;
            }
            rowY += h;
        }
    }
}
