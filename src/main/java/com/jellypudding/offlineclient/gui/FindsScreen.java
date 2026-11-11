package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.FindLog;
import com.jellypudding.offlineclient.config.FindLog.Find;
import com.jellypudding.offlineclient.config.FindSource;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SearchRank;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Every find the modules logged on this server in one window. The rows narrow to a module or
// to a search and each can walk there or mark a waypoint or copy the spot or forget the find.
public final class FindsScreen extends ListWindow {

    // One find with what the list needs to sort and draw it. The distance is below nought
    // for a find in another dimension.
    private record Row(FindLog log, Find find, double distance, String meta) {

        boolean here() {
            return distance >= 0;
        }
    }

    // The buttons at the end of a row. Walking and marking only work in the dimension you are in.
    private enum RowAction implements RowList.Button {
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

        @Override
        public String label() {
            return label;
        }

        @Override
        public String help() {
            return help;
        }

        @Override
        public boolean warns() {
            return false;
        }
    }

    private static final int SORT_WIDTH = 72;
    private static final int CLEAR_WIDTH = 48;
    // Distances and ages move as you do. The rows are worked out again this often.
    private static final long REFRESH_MS = 500;
    private static final String SEPARATOR = " · ";
    private static final String ALL = "All";
    private static final String HINT = "Go walks to a find. Waypoint marks it. Copy takes the coordinates. "
        + "The cross forgets it.";

    private static final List<RowAction> HERE_ACTIONS = List.of(RowAction.values());
    private static final List<RowAction> ELSEWHERE_ACTIONS =
        HERE_ACTIONS.stream().filter(action -> !action.hereOnly).toList();

    private static final Comparator<Row> NEAREST = Comparator.comparing((Row row) -> !row.here())
        .thenComparingDouble(Row::distance)
        .thenComparing(Comparator.comparingLong((Row row) -> row.find().found()).reversed());
    private static final Comparator<Row> NEWEST =
        Comparator.comparingLong((Row row) -> row.find().found()).reversed();

    private final RowList<Row> list = new RowList<>();
    private final List<FindLog> logs = FindSource.logs();
    private final Map<FindLog, Integer> counts = new HashMap<>();

    // Null lists every module.
    private FindLog filter;
    // Clears the module in the filter after a second click.
    private ActionSetting clear;
    private boolean newestFirst;
    private boolean stale = true;
    private int seenRevisions;
    private long refreshedAt;

    private FindsScreen(Screen parent, FindLog filter) {
        super("Finds", parent);
        choose(filter);
    }

    // Opens the list over whatever screen is open. A null log lists every module.
    public static void open(FindLog log) {
        openOver(parent -> new FindsScreen(parent, log));
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
        list.toTop();
        changed();
    }

    @Override
    protected void changed() {
        stale = true;
    }

    private int sortX() {
        return windowX() + windowWidth() - MARGIN - SORT_WIDTH;
    }

    private int clearX() {
        return sortX() - 4 - CLEAR_WIDTH;
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
        List<Row> rows = new ArrayList<>();
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
        List<RowList.Row<Row>> shown = new ArrayList<>(rows.size());
        for (Row row : rows) {
            shown.add(new RowList.Row<>(row, row.find().detail(), GuiTheme.text(), row.meta(),
                row.here() ? HERE_ACTIONS : ELSEWHERE_ACTIONS, row.find().detail()));
        }
        list.show(shown);
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
    protected void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        refresh();
        Font font = ClientFonts.gui();
        footer.begin();
        if (clear != null && clear.isArmed()) {
            footer.show("Click again to " + clear.confirmWords() + " on this server.", GuiTheme.RED_TEXT);
        }
        footer.showStatus();
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();

        WindowParts.frame(context, wx, wy, ww, wh);
        WindowParts.title(context, font, wx, wy, ww, "Finds", WindowParts.place(), Tally.counted(list.size(), "find"));
        renderToolbar(context, font, wx, mouseX, mouseY);
        List<String> names = new ArrayList<>();
        names.add(ALL);
        logs.forEach(log -> names.add(log.name()));
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(),
            names, this::tally, filter == null ? 0 : logs.indexOf(filter) + 1, mouseX, mouseY);
        list.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY, footer,
            emptyText());
        footer.render(context, font, wx, wy, ww, wh, HINT);
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

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int mouseX, int mouseY) {
        int y = toolbarY();
        int searchX = wx + MARGIN;
        int searchRight = (clear == null ? sortX() : clearX()) - 4;
        String matches = search.isEmpty() ? null : Tally.counted(list.size(), "match");
        search.render(context, font, searchX, y, searchRight - searchX, mouseX, mouseY, matches);

        boolean overSort = SettingWidget.isOver(mouseX, mouseY, sortX(), y, SORT_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, sortX(), y, SORT_WIDTH, SearchField.HEIGHT,
            newestFirst ? "newest first" : "nearest first", overSort, true);
        if (overSort) {
            footer.show(newestFirst ? "The newest finds come first. Click to put the nearest first."
                : "The nearest finds come first. Click to put the newest first.", GuiTheme.text());
        }
        if (clear != null) {
            renderClear(context, font, y, mouseX, mouseY);
        }
    }

    // Armed it turns red and the bar at the foot asks for the second click.
    private void renderClear(GuiGraphicsExtractor context, Font font, int y, int mouseX, int mouseY) {
        if (clear.isArmed()) {
            RowList.warning(context, font, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT, "confirm");
            return;
        }
        boolean over = SettingWidget.isOver(mouseX, mouseY, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, clearX(), y, CLEAR_WIDTH, SearchField.HEIGHT, "clear", over, true);
        if (over) {
            footer.show(clear.getDescription(), GuiTheme.text());
        }
    }

    private String emptyText() {
        if (!search.isEmpty()) {
            return "no matches";
        }
        return filter == null ? "nothing found on this server yet" : filter.name() + " found nothing here yet";
    }

    @Override
    protected boolean click(double mx, double my) {
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
                footer.say(clear.result());
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
        return list.click(mx, my, this::act);
    }

    // A click on the row itself does nothing.
    private void act(Row row, RowList.Button button) {
        if (button == null) {
            return;
        }
        Find find = row.find();
        switch ((RowAction) button) {
            case GO -> walkTo(find.pos());
            case WAYPOINT -> footer.say("Saved the waypoint " + addWaypoint(find));
            case COPY -> copySpot(find.pos());
            case FORGET -> {
                row.log().dismiss(find);
                footer.say("Forgot the find at " + BlockUtil.text(find.pos()));
                changed();
            }
        }
    }

    // Named after the kind with the first free number such as stash3.
    private static String addWaypoint(Find find) {
        return WaypointStore.get().markFresh(find.kind().replace(" ", ""), find.pos(), find.dimension(),
            Modules.nextWaypointColour());
    }

    @Override
    protected void releaseLists() {
        list.release();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return list.scroll(toView(mouseX), toView(mouseY), scrollY)
            || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
