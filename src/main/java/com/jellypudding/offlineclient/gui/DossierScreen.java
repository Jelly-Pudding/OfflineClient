package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.PlayerLedger;
import com.jellypudding.offlineclient.config.PlayerRecord;
import com.jellypudding.offlineclient.config.PlayerRecord.Fact;
import com.jellypudding.offlineclient.config.PlayerRecord.Place;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.util.Armed;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.SearchRank;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Every player Dossier recorded on this server in one window. The rows narrow to who is online
// or away or to a search. A click on a player opens their record with their gear and their log.
public final class DossierScreen extends ListWindow {

    // The groups down the side.
    private enum Tab {
        ALL("All"),
        ONLINE("Online"),
        AWAY("Away");

        private final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    // The buttons at the end of a row. Walking and marking only work in the dimension you are in.
    private enum Action implements RowList.Button {
        GO("go", "Walks there with the pathfinder and closes this window."),
        WAYPOINT("waypoint", "Saves the spot as a waypoint named after the player."),
        COPY("copy", "Puts the whole record on the clipboard."),
        COPY_SPOT("copy", "Puts the coordinates on the clipboard."),
        FORGET(GuiTheme.CROSS, "Forgets this player and keeps them out of the record until the game restarts."),
        CONFIRM("confirm", "Click again to forget this player.");

        private final String label;
        private final String help;

        Action(String label, String help) {
            this.label = label;
            this.help = help;
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
            return this == CONFIRM;
        }
    }

    // A record on the list and the tab list entry that shows they are online.
    private record Listed(PlayerRecord record, PlayerInfo live) {
    }

    private static final int SORT_WIDTH = 72;
    private static final int BACK_WIDTH = 48;
    private static final int FORGET_WIDTH = 56;
    // Ages and distances move as you play. The rows are worked out again this often.
    private static final long REFRESH_MS = 500;
    private static final String SEPARATOR = " · ";
    private static final String LIST_HINT = "Click a player to open their record. Go walks to where they were "
        + "last seen. Waypoint marks it. Copy takes the record. The cross forgets them.";
    private static final String RECORD_HINT = "Back returns to every player. Go walks to a spot. Waypoint marks "
        + "it. Copy takes the coordinates.";

    private static final Comparator<Listed> BY_NAME =
        Comparator.comparing((Listed listed) -> listed.record().name(), String.CASE_INSENSITIVE_ORDER);
    // Online players by name and then everyone else from the most recently seen.
    private static final Comparator<Listed> RECENT = Comparator.comparing((Listed listed) -> listed.live() == null)
        .thenComparingLong(listed -> listed.live() == null ? -listed.record().lastSeen() : 0)
        .thenComparing(BY_NAME);

    private final RowList<PlayerRecord> players = new RowList<>();
    private final RowList<Fact> record = new RowList<>();
    private final Map<Tab, Integer> counts = new EnumMap<>(Tab.class);

    private Tab tab = Tab.ALL;
    private boolean byName;
    // The player whose record is open. Null whilst every player is listed.
    private UUID opened;
    private String openedName = "";
    // The search of the list kept whilst a record is open.
    private String listSearch = "";
    // The player whose forget was pressed once and their name.
    private final Armed<UUID> armed = new Armed<>();
    private String armedName = "";
    private boolean stale = true;
    private long refreshedAt;

    private DossierScreen(Screen parent) {
        super("Dossier", parent);
    }

    // Opens the window over whatever screen is open.
    public static void open() {
        openOver(DossierScreen::new);
    }

    @Override
    protected void changed() {
        stale = true;
    }

    // The sort button whilst every player is listed and the back button whilst a record is open.
    private int cornerX() {
        return windowX() + windowWidth() - MARGIN - (opened == null ? SORT_WIDTH : BACK_WIDTH);
    }

    private int forgetX() {
        return cornerX() - 4 - FORGET_WIDTH;
    }

    // The rows are worked out again when something here changes and twice a second for the
    // ages and distances.
    private void refresh() {
        long now = System.currentTimeMillis();
        if (!stale && now - refreshedAt < REFRESH_MS) {
            return;
        }
        stale = false;
        refreshedAt = now;
        if (opened == null) {
            listPlayers(now);
        } else {
            listRecord(now);
        }
    }

    private void listPlayers(long now) {
        counts.clear();
        String query = search.get().trim();
        List<Listed> shown = new ArrayList<>();
        for (PlayerRecord each : PlayerLedger.INSTANCE.all()) {
            PlayerInfo live = PlayerLedger.live(each.id());
            Tab group = live == null ? Tab.AWAY : Tab.ONLINE;
            counts.merge(Tab.ALL, 1, Integer::sum);
            counts.merge(group, 1, Integer::sum);
            boolean matches = query.isEmpty() || SearchRank.best(query, each.name(),
                String.join(" ", each.pastNames())) != SearchRank.NO_MATCH;
            if ((tab == Tab.ALL || tab == group) && matches) {
                shown.add(new Listed(each, live));
            }
        }
        shown.sort(byName ? BY_NAME : RECENT);
        List<RowList.Row<PlayerRecord>> rows = new ArrayList<>();
        for (Listed listed : shown) {
            rows.add(playerRow(listed, now));
        }
        players.show(rows);
    }

    // The name and how long since they were online over where they were last seen.
    private RowList.Row<PlayerRecord> playerRow(Listed listed, long now) {
        PlayerRecord each = listed.record();
        String status;
        if (listed.live() != null) {
            status = "online";
        } else if (each.lastOnline() > 0) {
            status = "last online " + ChatUtil.ago(now - each.lastOnline());
        } else {
            status = "never in the tab list";
        }
        Place place = each.place();
        String detail = place == null ? "never seen in the world"
            : "seen at " + BlockUtil.text(place.pos()) + SEPARATOR + PlayerRecord.where(place, now);
        List<Action> buttons = new ArrayList<>(here(place) ? List.of(Action.GO, Action.WAYPOINT) : List.of());
        buttons.add(Action.COPY);
        buttons.add(armed.isArmed(each.id()) ? Action.CONFIRM : Action.FORGET);
        return new RowList.Row<>(each, each.name() + SEPARATOR + status,
            listed.live() == null ? GuiTheme.text() : GuiTheme.GREEN, detail, buttons,
            "Click to open the record of " + each.name() + ".");
    }

    // Every fact and then the log. A player forgotten meanwhile takes the window back to the list.
    private void listRecord(long now) {
        PlayerRecord shown = PlayerLedger.INSTANCE.get(opened);
        if (shown == null) {
            back();
            return;
        }
        openedName = shown.name();
        PlayerInfo live = PlayerLedger.live(opened);
        String query = search.get().trim();
        List<RowList.Row<Fact>> rows = new ArrayList<>();
        for (Fact fact : shown.facts(live, now)) {
            addFact(rows, fact, GuiTheme.text(), query);
        }
        for (Fact fact : shown.history(now)) {
            addFact(rows, fact, GuiTheme.textDim(), query);
        }
        record.show(rows);
    }

    private static void addFact(List<RowList.Row<Fact>> rows, Fact fact, int color, String query) {
        String text = fact.place() == null ? fact.text() : fact.text() + " " + BlockUtil.text(fact.place().pos());
        if (!query.isEmpty() && SearchRank.best(query, text, fact.detail()) == SearchRank.NO_MATCH) {
            return;
        }
        List<Action> buttons;
        if (fact.place() == null) {
            buttons = List.of();
        } else if (here(fact.place())) {
            buttons = List.of(Action.GO, Action.WAYPOINT, Action.COPY_SPOT);
        } else {
            buttons = List.of(Action.COPY_SPOT);
        }
        rows.add(new RowList.Row<>(fact, text, color, fact.detail(), buttons, fact.plain()));
    }

    // True for a place in the dimension you are in.
    private static boolean here(Place place) {
        return place != null && place.dimension().equals(ServerInfo.dimension());
    }

    private void openRecord(PlayerRecord shown) {
        opened = shown.id();
        openedName = shown.name();
        listSearch = search.get();
        search.clear();
        record.toTop();
        changed();
    }

    private void back() {
        opened = null;
        search.set(listSearch);
        changed();
    }

    @Override
    protected void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        refresh();
        Font font = ClientFonts.gui();
        footer.begin();
        if (armed.isArmed()) {
            footer.show("Click confirm to forget " + armedName + ".", GuiTheme.RED_TEXT);
        }
        footer.showStatus();
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();

        WindowParts.frame(context, wx, wy, ww, wh);
        renderTitle(context, font, wx, wy, ww);
        renderToolbar(context, font, wx, mouseX, mouseY);
        List<String> names = new ArrayList<>();
        for (Tab each : Tab.values()) {
            names.add(each.label);
        }
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(), names,
            index -> String.valueOf(counts.getOrDefault(Tab.values()[index], 0)),
            opened == null ? tab.ordinal() : -1, mouseX, mouseY);
        if (opened == null) {
            players.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY,
                footer, emptyText());
        } else {
            record.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY,
                footer, search.isEmpty() ? "nothing on record" : "no matches");
        }
        footer.render(context, font, wx, wy, ww, wh, opened == null ? LIST_HINT : RECORD_HINT);
    }

    private String emptyText() {
        if (!PlayerLedger.INSTANCE.ready()) {
            return "reading the records";
        }
        if (!search.isEmpty()) {
            return "no matches";
        }
        return switch (tab) {
            case ALL -> "nobody on record on this server yet";
            case ONLINE -> "nobody online is on record";
            case AWAY -> "everyone on record is online";
        };
    }

    // The server and how many players are listed or the player whose record is open.
    private void renderTitle(GuiGraphicsExtractor context, Font font, int wx, int wy, int ww) {
        if (opened == null) {
            WindowParts.title(context, font, wx, wy, ww, "Dossier", WindowParts.place(),
                Tally.counted(players.size(), "player"));
        } else {
            WindowParts.title(context, font, wx, wy, ww, "Dossier", openedName,
                PlayerLedger.live(opened) == null ? "away" : "online");
        }
    }

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int mouseX, int mouseY) {
        int y = toolbarY();
        int searchX = wx + MARGIN;
        int cornerX = cornerX();
        int searchRight = (opened == null ? cornerX : forgetX()) - 4;
        int shown = opened == null ? players.size() : record.size();
        search.render(context, font, searchX, y, searchRight - searchX, mouseX, mouseY,
            search.isEmpty() ? null : Tally.counted(shown, "match"));
        if (opened == null) {
            boolean over = SettingWidget.isOver(mouseX, mouseY, cornerX, y, SORT_WIDTH, SearchField.HEIGHT);
            GuiTheme.button(context, font, cornerX, y, SORT_WIDTH, SearchField.HEIGHT,
                byName ? "a to z" : "recent first", over, true);
            if (over) {
                footer.show(byName ? "Players are sorted by name. Click to put the most recently seen first."
                    : "Online players come first and then whoever was seen most recently. Click to sort them by name.",
                    GuiTheme.text());
            }
            return;
        }
        boolean overBack = SettingWidget.isOver(mouseX, mouseY, cornerX, y, BACK_WIDTH, SearchField.HEIGHT);
        GuiTheme.button(context, font, cornerX, y, BACK_WIDTH, SearchField.HEIGHT, "back", overBack, true);
        if (overBack) {
            footer.show("Returns to every player on record.", GuiTheme.text());
        }
        boolean warns = armed.isArmed(opened);
        boolean overForget = SettingWidget.isOver(mouseX, mouseY, forgetX(), y, FORGET_WIDTH, SearchField.HEIGHT);
        if (warns) {
            RowList.warning(context, font, forgetX(), y, FORGET_WIDTH, SearchField.HEIGHT, "confirm");
        } else {
            GuiTheme.button(context, font, forgetX(), y, FORGET_WIDTH, SearchField.HEIGHT, "forget", overForget, true);
        }
        if (overForget) {
            footer.show((warns ? Action.CONFIRM : Action.FORGET).help(), warns ? GuiTheme.RED_TEXT : GuiTheme.text());
        }
    }

    @Override
    protected boolean click(double mx, double my) {
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        int y = toolbarY();
        if (opened == null && SettingWidget.isOver(mx, my, cornerX(), y, SORT_WIDTH, SearchField.HEIGHT)) {
            byName = !byName;
            changed();
            return true;
        }
        if (opened != null && SettingWidget.isOver(mx, my, cornerX(), y, BACK_WIDTH, SearchField.HEIGHT)) {
            back();
            return true;
        }
        if (opened != null && SettingWidget.isOver(mx, my, forgetX(), y, FORGET_WIDTH, SearchField.HEIGHT)) {
            forgetOrArm(opened, openedName);
            return true;
        }
        int index = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), Tab.values().length);
        if (index >= 0) {
            tab = Tab.values()[index];
            if (opened != null) {
                back();
            }
            players.toTop();
            changed();
            return true;
        }
        return opened == null ? players.click(mx, my, this::pressPlayer) : record.click(mx, my, this::pressFact);
    }

    // A null button is a click on the row itself.
    private void pressPlayer(PlayerRecord pressed, RowList.Button button) {
        if (button == null) {
            openRecord(pressed);
            return;
        }
        Place place = pressed.place();
        switch ((Action) button) {
            case GO -> walkTo(place.pos());
            case WAYPOINT -> footer.say("Saved the waypoint " + addWaypoint(pressed.name(), place));
            case COPY -> {
                long now = System.currentTimeMillis();
                OfflineClient.MC.keyboardHandler.setClipboard(pressed.plainText(PlayerLedger.live(pressed.id()), now));
                footer.say("Copied the record of " + pressed.name());
            }
            case FORGET, CONFIRM -> forgetOrArm(pressed.id(), pressed.name());
            default -> {
            }
        }
    }

    private void pressFact(Fact pressed, RowList.Button button) {
        Place place = pressed.place();
        if (button == null || place == null) {
            return;
        }
        switch ((Action) button) {
            case GO -> walkTo(place.pos());
            case WAYPOINT -> footer.say("Saved the waypoint " + addWaypoint(openedName, place));
            case COPY_SPOT -> copySpot(place.pos());
            default -> {
            }
        }
    }

    // The first press arms the forget and a second one within a few seconds forgets.
    private void forgetOrArm(UUID id, String name) {
        if (!armed.press(id)) {
            armedName = name;
            changed();
            return;
        }
        PlayerLedger.INSTANCE.forgetPlayer(id);
        footer.say("Forgot " + name);
        if (id.equals(opened)) {
            back();
        }
        changed();
    }

    // Named after the player with the first free number such as Steve2.
    private static String addWaypoint(String name, Place place) {
        return WaypointStore.get().markFresh(name, place.pos(), place.dimension(), Modules.nextWaypointColour());
    }

    @Override
    protected void releaseLists() {
        players.release();
        record.release();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double mx = toView(mouseX);
        double my = toView(mouseY);
        RowList<?> shown = opened == null ? players : record;
        return shown.scroll(mx, my, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // Escape on a record goes back to every player.
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE && opened != null) {
            back();
            return true;
        }
        return super.keyPressed(event);
    }
}
