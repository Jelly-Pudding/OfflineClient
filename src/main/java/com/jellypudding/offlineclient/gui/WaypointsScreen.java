package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.config.WaypointStore;
import com.jellypudding.offlineclient.config.WaypointStore.Action;
import com.jellypudding.offlineclient.config.WaypointStore.Colour;
import com.jellypudding.offlineclient.config.WaypointStore.Icon;
import com.jellypudding.offlineclient.config.WaypointStore.Near;
import com.jellypudding.offlineclient.config.WaypointStore.Options;
import com.jellypudding.offlineclient.config.WaypointStore.Waypoint;
import com.jellypudding.offlineclient.modules.render.Waypoints;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Dimension;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WaypointsScreen extends ListWindow {

    // The distance is below nought in another dimension.
    private record Row(Waypoint waypoint, double distance) {

        boolean here() {
            return distance >= 0;
        }
    }

    private enum RowAction implements RowList.Button {
        SHOWN("shown", "Drawn in the world. Click to hide it."),
        HIDDEN("hidden", "Kept but not drawn. Click to show it again."),
        GO("go", "Walks there with the pathfinder and closes this list."),
        EDIT("edit", "Opens the name and the spot and every option of this waypoint."),
        REMOVE(GuiTheme.CROSS, "Removes this waypoint for good.");

        private final String label;
        private final String help;

        RowAction(String label, String help) {
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
            return false;
        }
    }

    private static final int SORT_WIDTH = 72;
    private static final int ADD_WIDTH = 60;
    private static final int BACK_WIDTH = 48;
    private static final int EDIT_WIDTH = 300;
    private static final int EDIT_PAD = 4;
    private static final long REFRESH_MS = 500;
    private static final String SEPARATOR = " · ";
    private static final String ALL = "All";
    private static final String STEM = "Waypoint";
    private static final String LIST_HINT = "Click a waypoint to edit it. Shown and hidden switch it in the world. "
        + "Go walks there. The cross removes it.";
    private static final String EDIT_HINT = "A row with a mark differs from what the Waypoints module gives every "
        + "waypoint. Click the mark to put it back. Escape returns to the list.";

    private static final Comparator<Row> BY_NAME =
        Comparator.comparing((Row row) -> row.waypoint().name(), String.CASE_INSENSITIVE_ORDER);
    private static final Comparator<Row> NEAREST = Comparator.comparing((Row row) -> !row.here())
        .thenComparingDouble(Row::distance).thenComparing(BY_NAME);

    private final RowList<Waypoint> list = new RowList<>();
    private final ScrollBar editScroll = new ScrollBar();
    private final SettingHost host = new SettingHost(this, text -> footer.show(text, GuiTheme.text()));
    private final SettingWidget.Drag drag = new SettingWidget.Drag();
    private final Map<String, Integer> counts = new HashMap<>();

    private List<String> dimensions = List.of();
    // Null lists every dimension.
    private String filter;
    private boolean byName;
    private Editor editor;
    private int pressedButton;
    private boolean pressed;
    private boolean stale = true;
    private long refreshedAt;

    private WaypointsScreen(Screen parent, Waypoint opened) {
        super("Waypoints", parent);
        if (opened != null) {
            editor = new Editor(opened);
        }
    }

    public static void open() {
        openOver(parent -> new WaypointsScreen(parent, null));
    }

    public static void edit(Waypoint waypoint) {
        openOver(parent -> new WaypointsScreen(parent, waypoint));
    }

    @Override
    protected void changed() {
        stale = true;
    }

    private int cornerX() {
        return windowX() + windowWidth() - MARGIN - (editor == null ? SORT_WIDTH : BACK_WIDTH);
    }

    private int addX() {
        return cornerX() - 4 - ADD_WIDTH;
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        if (!stale && now - refreshedAt < REFRESH_MS) {
            return;
        }
        stale = false;
        refreshedAt = now;
        rebuild();
    }

    private void rebuild() {
        WaypointStore store = WaypointStore.get();
        String here = ServerInfo.dimension();
        dimensions = store.dimensions();
        if (dimensions.remove(here)) {
            dimensions.addFirst(here);
        }
        if (filter != null && !dimensions.contains(filter)) {
            filter = null;
        }
        counts.clear();
        LocalPlayer player = OfflineClient.MC.player;
        Vec3 feet = player == null ? null : player.position();
        String query = search.get().trim();
        List<Row> rows = new ArrayList<>();
        for (Waypoint waypoint : store.onServer()) {
            counts.merge(waypoint.dimension(), 1, Integer::sum);
            if (filter != null && !filter.equals(waypoint.dimension())) {
                continue;
            }
            if (query.isEmpty() || SearchRank.score(waypoint.name(), query) != SearchRank.NO_MATCH) {
                rows.add(new Row(waypoint, feet != null && waypoint.dimension().equals(here)
                    ? waypoint.centre().distanceTo(feet) : -1));
            }
        }
        rows.sort(byName ? BY_NAME : NEAREST);
        List<RowList.Row<Waypoint>> shown = new ArrayList<>(rows.size());
        for (Row row : rows) {
            Waypoint waypoint = row.waypoint();
            List<RowAction> buttons = new ArrayList<>();
            buttons.add(waypoint.hidden() ? RowAction.HIDDEN : RowAction.SHOWN);
            if (row.here()) {
                buttons.add(RowAction.GO);
            }
            buttons.add(RowAction.EDIT);
            buttons.add(RowAction.REMOVE);
            shown.add(new RowList.Row<>(waypoint, waypoint.name(),
                waypoint.hidden() ? GuiTheme.textFaint() : waypoint.argb(), detail(row), buttons,
                "Click to edit " + waypoint.name() + "."));
        }
        list.show(shown);
    }

    private String detail(Row row) {
        Waypoint waypoint = row.waypoint();
        StringBuilder text = new StringBuilder(BlockUtil.text(waypoint.pos()));
        if (filter == null) {
            text.append(SEPARATOR).append(ServerInfo.dimensionName(waypoint.dimension()));
        }
        if (row.here()) {
            text.append(SEPARATOR).append(blocks(row.distance()));
        }
        Near rule = Modules.get(Waypoints.class).resolved(waypoint).near();
        if (rule.acts()) {
            text.append(SEPARATOR).append(rule.action() == Action.HIDE ? "hides" : "removes")
                .append(" itself within ").append(blocks(rule.distance()));
        }
        return text.toString();
    }

    private static String blocks(double distance) {
        return Tally.counted(Math.clamp(Math.round(distance), 0, Integer.MAX_VALUE), "block");
    }

    @Override
    protected void renderView(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        if (editor != null && !pressed && !editor.sync()) {
            leaveEditor();
        }
        refresh();
        Font font = ClientFonts.gui();
        footer.begin();
        footer.showStatus();
        int wx = windowX();
        int wy = windowY();
        int ww = windowWidth();
        int wh = windowHeight();

        WindowParts.frame(context, wx, wy, ww, wh);
        if (editor == null) {
            WindowParts.title(context, font, wx, wy, ww, "Waypoints", WindowParts.place(),
                Tally.counted(list.size(), "waypoint"));
        } else {
            WindowParts.title(context, font, wx, wy, ww, "Waypoints", editor.saved.name(),
                ServerInfo.dimensionName(editor.saved.dimension()));
        }
        renderToolbar(context, font, wx, mouseX, mouseY);
        List<String> names = new ArrayList<>();
        names.add(ALL);
        dimensions.forEach(dimension -> names.add(ServerInfo.dimensionName(dimension)));
        WindowParts.tabs(context, font, wx + MARGIN, contentTop(), WindowParts.tabWidth(), contentHeight(), names,
            this::tally, editor != null ? -1 : filter == null ? 0 : dimensions.indexOf(filter) + 1, mouseX, mouseY);
        if (editor == null) {
            list.render(context, font, listX(), contentTop(), listWidth(), contentHeight(), mouseX, mouseY, footer,
                emptyText());
        } else {
            renderEditor(context, mouseX, mouseY);
        }
        footer.render(context, font, wx, wy, ww, wh, editor == null ? LIST_HINT : EDIT_HINT);
    }

    private String tally(int tab) {
        if (tab > 0) {
            return String.valueOf(counts.getOrDefault(dimensions.get(tab - 1), 0));
        }
        int total = 0;
        for (int count : counts.values()) {
            total += count;
        }
        return String.valueOf(total);
    }

    private String emptyText() {
        if (!search.isEmpty()) {
            return "no matches";
        }
        return filter == null ? "no waypoints on this server yet" : "no waypoints here";
    }

    private void renderToolbar(GuiGraphicsExtractor context, Font font, int wx, int mouseX, int mouseY) {
        int y = toolbarY();
        int cornerX = cornerX();
        if (editor != null) {
            toolButton(context, font, cornerX, y, BACK_WIDTH, "back", "Returns to every waypoint.", mouseX, mouseY);
            return;
        }
        int searchX = wx + MARGIN;
        search.render(context, font, searchX, y, addX() - 4 - searchX, mouseX, mouseY,
            search.isEmpty() ? null : Tally.counted(list.size(), "match"));
        toolButton(context, font, addX(), y, ADD_WIDTH, "mark here",
            "Marks where you stand as a new waypoint and opens it.", mouseX, mouseY);
        toolButton(context, font, cornerX, y, SORT_WIDTH, byName ? "a to z" : "nearest first",
            byName ? "Waypoints are sorted by name. Click to put the nearest first."
                : "The nearest waypoints come first. Click to sort them by name.", mouseX, mouseY);
    }

    private void toolButton(GuiGraphicsExtractor context, Font font, int x, int y, int w, String label,
                            String help, int mouseX, int mouseY) {
        boolean over = SettingWidget.isOver(mouseX, mouseY, x, y, w, SearchField.HEIGHT);
        GuiTheme.button(context, font, x, y, w, SearchField.HEIGHT, label, over, true);
        if (over) {
            footer.show(help, GuiTheme.text());
        }
    }

    private int editWidth() {
        return Math.min(listWidth(), EDIT_WIDTH);
    }

    private int editHeight() {
        return SettingWidget.blockHeight(editor.block) + 2 * EDIT_PAD;
    }

    private int editTop() {
        return contentTop() + EDIT_PAD - editScroll.getOffset();
    }

    private void renderEditor(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        int x = listX();
        int top = contentTop();
        int w = listWidth();
        int h = contentHeight();
        int total = editHeight();
        editScroll.update(mouseY, total, h);
        RenderUtil.roundedRect(context, x, top, x + w, top + h, GuiTheme.CORNER, GuiTheme.bgPanel());
        context.guiRenderState.up();

        int rowW = editWidth();
        drag.follow(SettingWidget.blockContentX(x), SettingWidget.blockContentWidth(rowW), mouseX, mouseY);
        boolean inView = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        context.enableScissor(x, top, x + w, top + h);
        SettingWidget.renderBlock(context, editor.block, x, editTop(), rowW, mouseX, mouseY, inView, host);
        context.disableScissor();
        if (total > h) {
            int trackX = ScrollBar.trackX(x, w);
            editScroll.render(context, trackX, top, h, total, ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        pressedButton = event.button();
        pressed = true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        pressed = false;
        return super.mouseReleased(event);
    }

    @Override
    protected boolean click(double mx, double my) {
        host.beginClick();
        int y = toolbarY();
        int tab = WindowParts.tabAt(mx, my, windowX() + MARGIN, contentTop(), WindowParts.tabWidth(),
            contentHeight(), dimensions.size() + 1);
        if (tab >= 0) {
            leaveEditor();
            filter = tab == 0 ? null : dimensions.get(tab - 1);
            list.toTop();
            return true;
        }
        if (editor != null) {
            if (SettingWidget.isOver(mx, my, cornerX(), y, BACK_WIDTH, SearchField.HEIGHT)) {
                leaveEditor();
                return true;
            }
            return clickEditor(mx, my);
        }
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        if (SettingWidget.isOver(mx, my, cornerX(), y, SORT_WIDTH, SearchField.HEIGHT)) {
            byName = !byName;
            changed();
            return true;
        }
        if (SettingWidget.isOver(mx, my, addX(), y, ADD_WIDTH, SearchField.HEIGHT)) {
            markHere();
            return true;
        }
        return list.click(mx, my, this::act);
    }

    private boolean clickEditor(double mx, double my) {
        int x = listX();
        int top = contentTop();
        int h = contentHeight();
        if (!SettingWidget.isOver(mx, my, x, top, listWidth(), h)) {
            return false;
        }
        int trackX = ScrollBar.trackX(x, listWidth());
        if (editHeight() > h && ScrollBar.isOverTrack(mx, my, trackX, top, h)) {
            editScroll.beginDrag((int) my);
            return true;
        }
        SettingWidget.clickBlock(editor.block, mx, my, x, editTop(), editWidth(), pressedButton, host, drag);
        return true;
    }

    private void markHere() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            footer.say("You need to be in a world to mark a waypoint", GuiTheme.RED_TEXT);
            return;
        }
        String dimension = ServerInfo.dimension();
        String name = WaypointStore.get().markFresh(STEM, player.blockPosition(), dimension,
            Modules.nextWaypointColour());
        openEditor(WaypointStore.get().find(name, dimension));
    }

    // A null button is a click on the row itself.
    private void act(Waypoint waypoint, RowList.Button chosen) {
        WaypointStore store = WaypointStore.get();
        switch (chosen == null ? RowAction.EDIT : (RowAction) chosen) {
            case SHOWN, HIDDEN -> store.replace(waypoint, waypoint.withHidden(!waypoint.hidden()));
            case GO -> walkTo(waypoint.pos());
            case EDIT -> openEditor(waypoint);
            case REMOVE -> {
                if (store.remove(waypoint)) {
                    footer.say("Removed the waypoint " + waypoint.name());
                }
            }
        }
        changed();
    }

    // A rename may have rebuilt the rows whilst the list stood aside.
    private void takePicked(BlockPos pos) {
        if (editor != null) {
            footer.say(editor.moveTo(pos));
        }
    }

    private void openEditor(Waypoint waypoint) {
        editor = new Editor(waypoint);
        editScroll.setOffset(0);
    }

    private void leaveEditor() {
        if (editor == null) {
            return;
        }
        settle();
        editor = null;
        changed();
    }

    private void settle() {
        host.commitEditing();
        drag.cancel();
        ResetFoot.disarm();
        editor.sync();
    }

    @Override
    protected void releaseLists() {
        list.release();
        editScroll.release();
        drag.release();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double mx = toView(mouseX);
        double my = toView(mouseY);
        if (editor == null) {
            return list.scroll(mx, my, scrollY) || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int total = editHeight();
        int view = contentHeight();
        if (!OfflineClient.MC.mouseHandler.isLeftPressed()
            && SettingWidget.isOver(mx, my, listX(), contentTop(), listWidth(), view)) {
            editScroll.scroll(ScrollBar.wheelDelta(scrollY, total, view), total, view);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (editor == null) {
            return super.keyPressed(event);
        }
        if (!host.keyPressed(event) && !ResetFoot.undoKey(event) && event.key() == InputConstants.KEY_ESCAPE) {
            leaveEditor();
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return editor == null ? super.charTyped(event) : host.charTyped((char) event.codepoint());
    }

    // The screen also steps aside whilst a block is picked for the open waypoint and comes back after.
    @Override
    public void removed() {
        pressed = false;
        if (editor != null) {
            settle();
        }
        super.removed();
    }

    // Only a row the player moves is written back and one moved onto what the module gives follows
    // the module again.
    private final class Editor {

        private final Options defaults;
        private final TextSetting name;
        private final TextSetting position;
        // Null for a waypoint in a dimension a server added. It stays there.
        private final EnumSetting<Dimension> realm;
        private final BoolSetting shown;
        private final BoolSetting fromName;
        private final ColorSetting colour;
        private final EnumSetting<Icon> icon;
        private final NumberSetting scale;
        private final NumberSetting range;
        private final BoolSetting across;
        private final EnumSetting<Action> near;
        private final NumberSetting nearDistance;
        private final SettingBlock block;

        private Waypoint saved;
        // A row that differs from it is one the player moved.
        private Options shownRows;

        private Editor(Waypoint waypoint) {
            saved = waypoint;
            Waypoints module = Modules.get(Waypoints.class);
            defaults = module.defaults(waypoint);
            Options look = waypoint.options().over(defaults);
            Dimension known = Dimension.of(waypoint.dimension());

            name = new TextSetting("Name", "What the waypoint is called. It is kept as one word.",
                waypoint.name()).noDefault();
            position = new TextSetting("Position", "Three whole numbers for x and y and z.",
                BlockUtil.text(waypoint.pos())).noDefault();
            ActionSetting here = new ActionSetting("Set here", "Moves the waypoint to where you stand.",
                this::moveHere).under(position, () -> true);
            ActionSetting pick = new ActionSetting("Pick block",
                "Closes the list until you click a block and moves the waypoint onto it.", this::pickBlock)
                .under(position, () -> true);
            realm = known == null ? null : new EnumSetting<>("Dimension",
                "The dimension the waypoint is in.", known).noDefault();
            shown = new BoolSetting("Shown", "Draws the waypoint in the world.", !waypoint.hidden()).noDefault();

            Colour tint = waypoint.colour();
            fromName = new BoolSetting("Colour from name",
                "The waypoint takes a colour from its name. Off gives it the colour below.", tint.fromName())
                .noDefault();
            colour = new ColorSetting("Colour", "Colour of the marker and its label.", tint.hueFor(waypoint.name()),
                tint.saturation(), tint.brightness(), tint.rainbow()).noDefault().unless(fromName);

            icon = new EnumSetting<>("Icon", "The shape drawn on the waypoint.", defaults.icon());
            icon.setValue(look.icon());
            scale = Waypoints.scaleRow("Size of the label and icon of the waypoint.", defaults.scale());
            scale.setValue(look.scale());
            range = Waypoints.rangeRow("How far away the waypoint is still drawn. Nought draws it from any distance.",
                defaults.range());
            range.setValue(look.range());
            across = new BoolSetting("Across dimensions",
                "Shows the waypoint on the other side of a nether portal with its coordinates scaled by eight.",
                defaults.mirrored()).visibleWhen(() -> Dimension.otherSide(dimension()) != null);
            across.setValue(look.mirrored());
            near = new EnumSetting<>("When near", "What the waypoint does once you come near it.",
                defaults.near().action())
                .describe(Action.NOTHING, "The waypoint stays when you come near it.")
                .describe(Action.HIDE, "The waypoint hides itself once you come within the distance below. "
                    + "It waits until you have been further away first and only acts whilst Waypoints is on.")
                .describe(Action.DELETE, "The waypoint removes itself once you come within the distance below. "
                    + "It waits until you have been further away first and only acts whilst Waypoints is on.");
            near.setValue(look.near().action());
            nearDistance = new NumberSetting("Near distance", "How close you come before the waypoint acts.",
                usualDistance(defaults.near()), Near.SHORTEST_DISTANCE, 64, 1, " blocks")
                .under(near, Action.HIDE, Action.DELETE);
            nearDistance.setValue(usualDistance(look.near()));
            shownRows = optionRows();

            List<Setting<?>> rows = new ArrayList<>(List.of(name, position, here, pick));
            if (realm != null) {
                rows.add(realm);
            }
            rows.addAll(List.of(shown, fromName, colour, icon, scale, range, across, near, nearDistance));
            block = new SettingBlock(this, waypoint.name(), rows, null,
                "The name and the spot and the colour and the Shown switch stay as they are.");
        }

        private static double usualDistance(Near rule) {
            return rule.acts() ? rule.distance() : Near.USUAL_DISTANCE;
        }

        private String dimension() {
            return realm == null ? saved.dimension() : realm.getValue().id();
        }

        private String moveHere() {
            LocalPlayer player = OfflineClient.MC.player;
            return player == null ? "You are not in a world" : moveTo(player.blockPosition());
        }

        private String pickBlock() {
            BlockPick.start("the spot of " + saved.name(), WaypointsScreen.this::takePicked);
            return null;
        }

        private String moveTo(BlockPos pos) {
            String here = ServerInfo.dimension();
            if (!here.equals(dimension())) {
                Dimension known = Dimension.of(here);
                if (realm == null || known == null) {
                    return "It cannot leave its own dimension";
                }
                realm.setValue(known);
            }
            position.setValue(BlockUtil.text(pos));
            return "Moved to " + BlockUtil.text(pos);
        }

        // The click that ends a rename can open another row for typing. The same row of these
        // rows stays open and keeps whatever was typed in it.
        private void takeTyping(Editor old) {
            for (Setting<?> was : old.block.settings()) {
                if (host.isEditing(was)) {
                    String typed = host.getEditField().get();
                    for (Setting<?> row : block.settings()) {
                        if (row.getName().equals(was.getName())) {
                            openTyping(row, typed);
                        }
                    }
                    return;
                }
            }
        }

        private void openTyping(Setting<?> row, String typed) {
            if (row instanceof TextSetting text) {
                host.startEditing(text);
            } else if (row instanceof NumberSetting number) {
                host.startEditing(number);
            } else {
                return;
            }
            if (!host.getEditField().get().equals(typed)) {
                host.getEditField().set(typed);
            }
        }

        private Colour typedColour() {
            return fromName.isOn() ? Colour.AUTO : Colour.of(colour);
        }

        private Options optionRows() {
            return new Options(icon.getValue(), scale.getValue(), range.getValue(), across.isOn(),
                Near.of(near.getValue(), nearDistance.getValue()));
        }

        // A name or a spot that cannot be read goes back to what it was. False once the waypoint
        // has gone from the store.
        private boolean sync() {
            WaypointStore store = WaypointStore.get();
            if (!store.holds(saved)) {
                return false;
            }
            String typedName = WaypointStore.cleanName(name.getValue());
            BlockPos pos = BlockUtil.parse(position.getValue());
            if (pos == null) {
                footer.say("A position is three whole numbers", GuiTheme.RED_TEXT);
                pos = saved.pos();
            }
            Options rows = optionRows();
            Waypoint wanted = new Waypoint(typedName == null ? saved.name() : typedName, pos.getX(), pos.getY(),
                pos.getZ(), dimension(), saved.server(), typedColour(), !shown.isOn(), saved.found(),
                saved.options().edited(shownRows, rows, defaults));
            if (wanted.equals(saved) || store.replace(saved, wanted)) {
                shownRows = rows;
            } else {
                footer.say("Another waypoint in the " + ServerInfo.dimensionName(wanted.dimension())
                    + " is already called " + wanted.name(), GuiTheme.RED_TEXT);
                wanted = saved;
            }
            boolean moved = !wanted.name().equals(saved.name()) || !wanted.dimension().equals(saved.dimension());
            saved = wanted;
            if (moved) {
                // What the module gives can hang on the name. The rows start again from the waypoint as it stands.
                Editor next = new Editor(wanted);
                next.takeTyping(this);
                editor = next;
                return true;
            }
            name.setValue(saved.name());
            position.setValue(BlockUtil.text(saved.pos()));
            if (realm != null) {
                realm.setValue(Dimension.of(saved.dimension()));
            }
            return true;
        }
    }
}
