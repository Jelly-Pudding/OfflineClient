package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindLog.Find;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.path.Travel;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every find the modules logged on this server in one window. The rows narrow to a module
// or to a search and each can walk there or mark a waypoint or copy the spot or forget the
// find. It follows the ClickGUI scale like every other panel.
public final class FindsScreen extends Screen {

    // One find with what the list needs to sort and draw it. The distance is below nought
    // for a find in another dimension.
    private record Row(FindLog log, Find find, double distance, String meta) {

        boolean here() {
            return distance >= 0;
        }
    }

    // The buttons at the end of a row. Walking and marking only work in the dimension you are in.
    private enum RowAction {
        GO("go", "Walks there with the pathfinder and closes this list.", true),
        WAYPOINT("waypoint", "Saves the spot as a waypoint named after the find.", true),
        COPY("copy", "Puts the coordinates on the clipboard.", false),
        FORGET(GuiTheme.CROSS, "Forgets this find and keeps the same find away this session.", false);

        private final String label;
        private final String help;
        private final boolean hereOnly;

        RowAction(String label, String help, boolean hereOnly) {
            this.label = label;
            this.help = help;
            this.hereOnly = hereOnly;
        }
    }

    // Where one row button landed this frame and the row it acts on.
    private record Chip(RowAction action, Row row, int x, int y, int w) {
    }

    private static final int MARGIN = WindowParts.MARGIN;
    // Two lines to a find. What it is and then where and when.
    private static final int ROW_HEIGHT = 24;
    private static final int LINE_GAP = 10;
    private static final int CHIP_HEIGHT = 12;
    private static final int CHIP_PAD = 4;
    private static final int CHIP_GAP = 3;
    private static final int SORT_WIDTH = 72;
    private static final int CLEAR_WIDTH = 48;
    // Distances and ages move as you do. The rows are worked out again this often.
    private static final long REFRESH_MS = 500;
    // How long what an action did stays in the bar at the foot.
    private static final long STATUS_MS = 2500;
    private static final String SEPARATOR = " · ";
    private static final String ALL = "All";
    private static final String HINT = "Go walks to a find. Waypoint marks it. Copy takes the coordinates. "
        + "The cross forgets it.";

    // Each row lays its buttons out from the right.
    private static final List<RowAction> HERE_ACTIONS = List.of(RowAction.values()).reversed();
    private static final List<RowAction> ELSEWHERE_ACTIONS =
        HERE_ACTIONS.stream().filter(action -> !action.hereOnly).toList();

    private static final Comparator<Row> NEAREST = Comparator.comparing((Row row) -> !row.here())
        .thenComparingDouble(Row::distance)
        .thenComparing(Comparator.comparingLong((Row row) -> row.find().found()).reversed());
    private static final Comparator<Row> NEWEST =
        Comparator.comparingLong((Row row) -> row.find().found()).reversed();

    private final Screen parent;
    private final LatchedScale latched = new LatchedScale();
    private final TextInput textInput = new TextInput(this);
    private final SearchField search = SearchField.sticky("type to search", this::changed);
    private final ScrollBar scrollBar = new ScrollBar();
    private final List<FindLog> logs = FindSource.logs();
    private final Map<FindLog, Integer> counts = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();

    // Null lists every module.
    private FindLog filter;
    // Clears the module in the filter after a second click.
    private ActionSetting clear;
    private boolean newestFirst;
    private boolean stale = true;
    private int seenRevisions;
    private long refreshedAt;

    // What the bar at the foot shows this frame and what an action just did.
    private String footer;
    private int footerColor;
    private String status;
    private long statusUntil;

    private FindsScreen(Screen parent, FindLog filter) {
        super(Component.literal("Finds"));
        this.parent = parent;
        choose(filter);
    }

    // Opens the list over whatever screen is open. It waits a frame because a chat line that
    // opened it would otherwise close it again. A null log lists every module.
    public static void open(FindLog log) {
        Minecraft mc = OfflineClient.MC;
        mc.schedule(() -> mc.gui.setScreen(new FindsScreen(mc.gui.screen(), log)));
    }

    // The rows a module with a log adds in one line. Saving the finds then opening the list
    // and clearing the dimension you are in.
    public static Setting<?>[] settingsFor(FindLog log) {
        return new Setting<?>[] {
            log.saveSetting(),
            new ActionSetting("Open finds", "Opens the list of everything this module found on this server.",
                () -> open(log)),
            log.clearDimensionSetting()
        };
    }

    private void choose(FindLog log) {
        filter = log;
        clear = log == null ? null : log.clearServerSetting();
        scrollBar.setOffset(0);
        changed();
    }

    private void changed() {
        stale = true;
    }

    private void say(String text) {
        status = text;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    private int viewWidth() {
        return (int) latched.toView(width);
    }

    private int viewHeight() {
        return (int) latched.toView(height);
    }

    private int windowX() {
        return (viewWidth() - WindowParts.width(viewWidth())) / 2;
    }

    private int windowY() {
        return (viewHeight() - WindowParts.height(viewHeight())) / 2;
    }

    private int toolbarY() {
        return windowY() + WindowParts.TITLE_HEIGHT + 5;
    }

    private int contentTop() {
        return toolbarY() + SearchField.HEIGHT + 5;
    }

    private int contentHeight() {
        int bottom = windowY() + WindowParts.height(viewHeight()) - WindowParts.FOOT_HEIGHT - 5;
        return Math.max(ROW_HEIGHT, bottom - contentTop());
    }

    private int listX() {
        return windowX() + WindowParts.SIDEBAR_WIDTH;
    }

    private int listWidth() {
        return WindowParts.width(viewWidth()) - WindowParts.SIDEBAR_WIDTH - MARGIN;
    }

    private int sortX() {
        return windowX() + WindowParts.width(viewWidth()) - MARGIN - SORT_WIDTH;
    }

    private int clearX() {
        return sortX() - 4 - CLEAR_WIDTH;
    }

    private int totalHeight() {
        return rows.size() * ROW_HEIGHT;
    }

    // The rows are worked out again when a find changes or the search or the order does.
    // Twice a second they also catch up with the distances and ages.
    private void refresh() {
        int revisions = 1;
        for (FindLog log : logs) {
            revisions = 31 * revisions + log.revision();
        }
        long now = System.currentTimeMillis();
        if (!stale && revisions == seenRevisions && now - refreshedAt < REFRESH_MS) {
            return;
        }
        stale = false;
        seenRevisions = revisions;
        refreshedAt = now;
        rebuild();
    }

    private void rebuild() {
        rows.clear();
        counts.clear();
        LocalPlayer player = OfflineClient.MC.player;
        Vec3 eye = player == null ? null : player.getEyePosition();
        String dimension = ServerInfo.dimension();
        String query = search.get().trim();
        for (FindLog log : logs) {
            List<Find> all = log.all();
            counts.put(log, all.size());
            if (filter != null && filter != log) {
                continue;
            }
            for (Find find : all) {
                double distance = eye != null && find.dimension().equals(dimension)
                    ? Math.sqrt(log.distanceSqr(find, eye)) : -1;
                String meta = meta(log, find, distance);
                boolean matches = query.isEmpty()
                    || SearchRank.best(query, find.detail(), find.kind(), meta) != SearchRank.NO_MATCH;
                if (matches) {
                    rows.add(new Row(log, find, distance, meta));
                }
            }
        }
        rows.sort(newestFirst ? NEWEST : NEAREST);
        scrollBar.setOffset(ScrollBar.clamp(scrollBar.getOffset(), totalHeight(), contentHeight()));
    }

    // The second line of a row such as 120 64 300 · Nether · 40 blocks · 5 minutes ago.
    private String meta(FindLog log, Find find, double distance) {
        StringBuilder text = new StringBuilder();
        if (filter == null) {
            text.append(log.name()).append(SEPARATOR);
        }
        text.append(BlockUtil.text(find.pos())).append(SEPARATOR).append(ServerInfo.dimensionName(find.dimension()));
        if (distance >= 0) {
            text.append(SEPARATOR).append(Math.round(distance)).append(" blocks");
        }
        long age = System.currentTimeMillis() - find.found();
        return text.append(SEPARATOR).append(ChatUtil.ago(age)).toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiScreenBase.dimBackground(this, context);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        latched.refresh();
        context.pose().pushMatrix();
        context.pose().scale(latched.get(), latched.get());
        renderView(context, (int) latched.toView(mouseX), (int) latched.toView(mouseY));
        context.pose().popMatrix();
    }

    private void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        refresh();
        Font font = OfflineClient.MC.font;
        footer = null;
        // The pointer still rests on the button just clicked. What it did beats its help.
        if (clear != null && clear.isArmed()) {
            showInFooter("Click again to " + clear.confirmWords() + " on this server.", GuiTheme.RED_TEXT);
        }
        if (status != null && System.currentTimeMillis() < statusUntil) {
            showInFooter(status, GuiTheme.GREEN);
        }
        int wx = windowX();
        int wy = windowY();
        int ww = WindowParts.width(viewWidth());
        int wh = WindowParts.height(viewHeight());

        WindowParts.frame(context, wx, wy, ww, wh);
        renderTitle(context, font, wx, wy, ww);
        renderToolbar(context, font, wx, mouseX, mouseY);
        List<String> names = new ArrayList<>();
        names.add(ALL);
        logs.forEach(log -> names.add(log.name()));
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(),
            names, this::tally, filter == null ? 0 : logs.indexOf(filter) + 1, mouseX, mouseY);
        renderList(context, font, mouseX, mouseY);
        renderFooter(context, font, wx, wy, ww, wh);
    }

    // The finds a tab holds on this server. The first tab counts them all.
    private String tally(int tab) {
        if (tab > 0) {
            return String.valueOf(counts.getOrDefault(logs.get(tab - 1), 0));
        }
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        return String.valueOf(total);
    }

    private void renderTitle(GuiGraphicsExtractor context, Font font, int wx, int wy, int ww) {
        int y = WindowParts.titleY(wy);
        String title = "Finds";
        context.text(font, title, wx + 10, y, GuiTheme.accentText(), false);
        String where = ServerInfo.address();
        context.text(font, where == null ? "single player" : where, wx + 14 + font.width(title), y,
            GuiTheme.textFaint(), false);
        String shown = Tally.counted(rows.size(), "find");
        context.text(font, shown, wx + ww - 10 - font.width(shown), y, GuiTheme.textDim(), false);
    }

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int mouseX, int mouseY) {
        int y = toolbarY();
        int searchX = wx + MARGIN;
        int searchRight = (clear == null ? sortX() : clearX()) - 4;
        String matches = search.isEmpty() ? null : Tally.counted(rows.size(), "match");
        search.render(context, font, searchX, y, searchRight - searchX, mouseX, mouseY, matches);

        boolean overSort = SettingWidget.isOver(mouseX, mouseY, sortX(), y, SORT_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, sortX(), y, SORT_WIDTH, SearchField.HEIGHT,
            newestFirst ? "newest first" : "nearest first", overSort, true);
        if (overSort) {
            showInFooter(newestFirst ? "The newest finds come first. Click to put the nearest first."
                : "The nearest finds come first. Click to put the newest first.", GuiTheme.text());
        }
        if (clear != null) {
            renderClear(context, font, y, mouseX, mouseY);
        }
    }

    // Armed it turns red and the bar at the foot asks for the second click.
    private void renderClear(GuiGraphicsExtractor context, Font font, int y, int mouseX, int mouseY) {
        boolean over = SettingWidget.isOver(mouseX, mouseY, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT);
        if (!clear.isArmed()) {
            GuiTheme.button(context, font, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT, "clear", over, true);
            if (over) {
                showInFooter(clear.getDescription(), GuiTheme.text());
            }
            return;
        }
        RenderUtil.roundedBorderedRect(context, clearX(), y, clearX() + CLEAR_WIDTH, y + SearchField.HEIGHT,
            GuiTheme.CORNER, GuiTheme.bgPanel(), GuiTheme.RED);
        context.guiRenderState.up();
        context.centeredText(font, "confirm", clearX() + CLEAR_WIDTH / 2, GuiTheme.textY(y, SearchField.HEIGHT),
            GuiTheme.RED_TEXT);
    }

    private void renderList(GuiGraphicsExtractor context, Font font, int mouseX, int mouseY) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        int w = listWidth();
        int total = totalHeight();
        int rowW = ScrollBar.rowWidth(w, total, h);
        scrollBar.update(mouseY, total, h);

        RenderUtil.roundedRect(context, x, top, x + w, top + h, GuiTheme.CORNER, GuiTheme.bgPanel());
        context.guiRenderState.up();

        chips.clear();
        boolean inView = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        context.enableScissor(x, top, x + rowW, top + h);
        int rowY = top - scrollBar.getOffset();
        for (Row row : rows) {
            if (rowY + ROW_HEIGHT > top && rowY < top + h) {
                renderRow(context, font, row, x, rowY, rowW, mouseX, mouseY, inView);
            }
            rowY += ROW_HEIGHT;
        }
        if (rows.isEmpty()) {
            context.text(font, emptyText(), x + 8, GuiTheme.textY(top, ROW_HEIGHT), GuiTheme.textFaint(), false);
        }
        context.disableScissor();

        if (total > h) {
            int trackX = ScrollBar.trackX(x, w);
            scrollBar.render(context, trackX, top, h, total, ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    private String emptyText() {
        if (!search.isEmpty()) {
            return "no matches";
        }
        return filter == null ? "nothing found on this server yet" : filter.name() + " found nothing here yet";
    }

    // The words of the find over where and when with the buttons at the end.
    private void renderRow(GuiGraphicsExtractor context, Font font, Row row, int x, int y, int w,
                           int mouseX, int mouseY, boolean inView) {
        boolean hovered = inView && SettingWidget.isOver(mouseX, mouseY, x, y, w, ROW_HEIGHT);
        RenderUtil.roundedRect(context, x + 2, y + 1, x + w - 2, y + ROW_HEIGHT - 1, GuiTheme.CORNER,
            hovered ? GuiTheme.bgRowHover() : GuiTheme.bgRow());
        context.guiRenderState.up();

        int chipsLeft = x + w - 4;
        int chipY = y + (ROW_HEIGHT - CHIP_HEIGHT) / 2;
        for (RowAction action : row.here() ? HERE_ACTIONS : ELSEWHERE_ACTIONS) {
            int chipW = font.width(action.label) + CHIP_PAD * 2;
            chipsLeft -= chipW;
            boolean over = inView && SettingWidget.isOver(mouseX, mouseY, chipsLeft, chipY, chipW, CHIP_HEIGHT);
            GuiTheme.button(context, font, chipsLeft, chipY, chipW, CHIP_HEIGHT, action.label, over, hovered);
            chips.add(new Chip(action, row, chipsLeft, chipY, chipW));
            if (over) {
                showInFooter(action.help, GuiTheme.text());
            }
            chipsLeft -= CHIP_GAP;
        }

        int textX = x + 8;
        int room = chipsLeft - 4 - textX;
        context.text(font, SettingWidget.trimEnd(font, row.find().detail(), room), textX, y + 4,
            GuiTheme.text(), false);
        context.text(font, SettingWidget.trimEnd(font, row.meta(), room), textX, y + 4 + LINE_GAP,
            GuiTheme.textFaint(), false);
        if (hovered) {
            showInFooter(row.find().detail(), GuiTheme.text());
        }
    }

    // The first thing asked for this frame wins. A button beats the row it sits on.
    private void showInFooter(String text, int color) {
        if (footer == null) {
            footer = text;
            footerColor = color;
        }
    }

    private void renderFooter(GuiGraphicsExtractor context, Font font, int wx, int wy, int ww, int wh) {
        showInFooter(HINT, GuiTheme.textFaint());
        WindowParts.footer(context, font, wx, wy, ww, wh, footer, footerColor);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        latched.hold(true);
        double mx = latched.toView(event.x());
        double my = latched.toView(event.y());
        return click(mx, my) || super.mouseClicked(event, doubleClick);
    }

    private boolean click(double mx, double my) {
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        int y = toolbarY();
        if (SettingWidget.isOver(mx, my, sortX(), y, SORT_WIDTH, SearchField.HEIGHT)) {
            newestFirst = !newestFirst;
            changed();
            return true;
        }
        if (clear != null && SettingWidget.isOver(mx, my, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT)) {
            if (clear.press()) {
                say(clear.result());
                changed();
            }
            return true;
        }
        int tab = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), logs.size() + 1);
        if (tab >= 0) {
            choose(tab == 0 ? null : logs.get(tab - 1));
            return true;
        }
        return clickList(mx, my);
    }

    private boolean clickList(double mx, double my) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        int w = listWidth();
        if (!SettingWidget.isOver(mx, my, x, top, w, h)) {
            return false;
        }
        int trackX = ScrollBar.trackX(x, w);
        if (totalHeight() > h && ScrollBar.isOverTrack(mx, my, trackX, top, h)) {
            scrollBar.beginDrag((int) my);
            return true;
        }
        for (Chip chip : chips) {
            if (SettingWidget.isOver(mx, my, chip.x(), chip.y(), chip.w(), CHIP_HEIGHT)) {
                act(chip.action(), chip.row());
                return true;
            }
        }
        return true;
    }

    private void act(RowAction action, Row row) {
        Find find = row.find();
        String spot = BlockUtil.text(find.pos());
        switch (action) {
            case GO -> {
                if (Travel.to(find.pos())) {
                    closeAll();
                } else {
                    say("Could not start the walk there");
                }
            }
            case WAYPOINT -> say("Saved the waypoint " + addWaypoint(find));
            case COPY -> {
                OfflineClient.MC.keyboardHandler.setClipboard(spot);
                say("Copied " + spot);
            }
            case FORGET -> {
                row.log().dismiss(find);
                say("Forgot the find at " + spot);
                changed();
            }
        }
    }

    // Named after the kind with the first free number such as stash3.
    private static String addWaypoint(Find find) {
        return WaypointStore.get().markFresh(find.kind().replace(" ", ""), find.pos(), find.dimension(),
            Modules.nextWaypointHue());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        scrollBar.release();
        search.release();
        latched.hold(false);
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        search.drag(latched.toView(event.x()));
        return super.mouseDragged(event, dragX, dragY);
    }

    // Nothing scrolls whilst the thumb is held. The rows would slide out from under it.
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double mx = latched.toView(mouseX);
        double my = latched.toView(mouseY);
        int h = contentHeight();
        if (!OfflineClient.MC.mouseHandler.isLeftPressed()
            && SettingWidget.isOver(mx, my, listX(), contentTop(), listWidth(), h)) {
            scrollBar.scroll(ScrollBar.wheelDelta(scrollY, totalHeight(), h), totalHeight(), h);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return search.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return search.charTyped((char) event.codepoint()) || super.charTyped(event);
    }

    // The search here is always live and takes every character typed.
    @Override
    public void added() {
        super.added();
        textInput.set(true);
    }

    @Override
    public void removed() {
        textInput.set(false);
        super.removed();
    }

    // Back to the screen it was opened over. Opened from chat that is the game.
    @Override
    public void onClose() {
        OfflineClient.MC.gui.setScreen(parent);
    }

    // A walk cannot run behind a screen. The screen this one covers closes its own way and
    // keeps what it saves on the way out.
    private void closeAll() {
        if (parent == null) {
            OfflineClient.MC.gui.setScreen(null);
        } else {
            parent.onClose();
        }
    }
}
