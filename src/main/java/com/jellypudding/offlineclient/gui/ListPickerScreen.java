package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.render.ClientFonts;
import com.jellypudding.offlineclient.setting.CountedPickList;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.PickList;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.SearchRank;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Two column picker for a list setting. Clicking an entry moves it across.
// The left column holds what is on offer and the right what is picked. A list
// with a count per entry shows it at the end of each picked row. A list whose
// offer splits into families gets a button for each under the search box.
public final class ListPickerScreen<T> extends Screen implements ClientScreen {

    private record Entry<T>(T value, String name, String label, String id, ItemStack icon) {
    }

    // The buttons under the columns from left to right.
    private enum Button { DONE, ADD, CLEAR, DEFAULTS }

    private static final int COL_WIDTH = 156;
    private static final int COL_GAP = 12;
    private static final int ROW_HEIGHT = 18;
    // Left inset of the name. The icon sits in front of it.
    private static final int LABEL_X = 24;
    private static final int SEARCH_WIDTH = 200;
    private static final int TITLE_Y = 8;
    private static final int SEARCH_TOP = 20;
    private static final int LIST_TOP = 50;
    private static final int BUTTON_WIDTH = 64;
    private static final int BUTTON_HEIGHT = 16;
    private static final int BUTTON_GAP = 8;
    private static final int FOOTER = 34;
    // The family buttons sit between the search box and the column headers.
    private static final int GROUP_TOP = 38;
    private static final int GROUP_HEIGHT = 14;
    private static final int GROUP_GAP = 4;
    // Room either side of a family label.
    private static final int GROUP_PAD = 6;
    // How far the families push the columns down.
    private static final int GROUP_ROW = 20;
    // Space between a count and the name or the row edge.
    private static final int COUNT_GAP = 4;
    // An empty count box still needs something to aim at.
    private static final int MIN_COUNT_WIDTH = 6;
    // More digits than this overflow a whole number and no count comes near them.
    private static final int MAX_DIGITS = 9;
    private static final TextField.Filter DIGITS = c -> c >= '0' && c <= '9';
    private static final String COUNT_HELP = "Click the number to type how many. Enter keeps it.";

    private final Screen parent;
    private final PickList<T> setting;
    // Null for a list without counts.
    private final CountedPickList<T> counted;
    private final List<RegistryListSetting.Group<T>> groups;

    // Every entry on offer sorted by name. Built once.
    private final List<Entry<T>> all = new ArrayList<>();
    private final Map<T, Entry<T>> byValue = new HashMap<>();

    private final List<Entry<T>> available = new ArrayList<>();
    private final List<Entry<T>> chosen = new ArrayList<>();
    private final ScrollBar leftBar = new ScrollBar();
    private final ScrollBar rightBar = new ScrollBar();

    private final SearchField search = SearchField.sticky("type to search", this::searchChanged);
    private final TextInput textInput = new TextInput(this);
    private final TextField countField = new TextField();
    // The picked entry whose count is being typed. Null whilst none is.
    private T editing;
    private String tooltip;

    // A bulk press that changed the list turns its button into undo until the next edit.
    private Button undoing;
    private Defaults.Change undo;

    public ListPickerScreen(Screen parent, PickList<T> setting) {
        super(Component.literal(setting.getName()));
        this.parent = parent;
        this.setting = setting;
        this.counted = setting instanceof CountedPickList<T> list ? list : null;
        this.groups = setting instanceof RegistryListSetting<T> list ? list.groups() : List.of();

        Font font = ClientFonts.guiAtGameScale();
        // Names are trimmed once for the narrowest a column gets. A list of
        // any size overflows and gives up the gutter.
        int room = COL_WIDTH - GuiTheme.SCROLL_GUTTER - LABEL_X - 2;
        for (T value : setting.options()) {
            remember(value, font, room);
        }
        // A pick the offer has since lost still needs a row to be removed from.
        for (T value : setting.chosen()) {
            if (!byValue.containsKey(value)) {
                remember(value, font, room);
            }
        }
        all.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        refresh();
    }

    private void remember(T value, Font font, int room) {
        String name = setting.displayName(value);
        Entry<T> entry = new Entry<>(value, name, SettingWidget.trimEnd(font, name, room),
            setting.idOf(value), setting.icon(value));
        all.add(entry);
        byValue.put(value, entry);
    }

    // Add and clear act on what the search shows. Their undo goes once the search changes.
    private void searchChanged() {
        if (undoing == Button.ADD || undoing == Button.CLEAR) {
            stopUndo();
        }
        refresh();
    }

    private void refresh() {
        String query = search.get().trim();
        available.clear();
        for (Entry<T> entry : all) {
            if (!setting.isChosen(entry.value())) {
                available.add(entry);
            }
        }
        chosen.clear();
        if (editing != null && !setting.isChosen(editing)) {
            editing = null;
        }
        for (T value : setting.chosen()) {
            Entry<T> entry = byValue.get(value);
            if (entry != null) {
                chosen.add(entry);
            }
        }
        // Both columns narrow to the search. The chosen column keeps its pick order.
        if (!query.isEmpty()) {
            narrow(available, query, true);
            narrow(chosen, query, false);
        }
        int view = listHeight();
        leftBar.setOffset(ScrollBar.clamp(leftBar.getOffset(), available.size() * ROW_HEIGHT, view));
        rightBar.setOffset(ScrollBar.clamp(rightBar.getOffset(), chosen.size() * ROW_HEIGHT, view));
    }

    // The registry id is the weaker field. A namespaced search still finds the entry.
    private static <T> void narrow(List<Entry<T>> list, String query, boolean rank) {
        if (rank) {
            List<Entry<T>> matches = SearchRank.rank(list,
                entry -> SearchRank.best(query, entry.name(), entry.id()));
            list.clear();
            list.addAll(matches);
            return;
        }
        list.removeIf(entry -> SearchRank.best(query, entry.name(), entry.id()) == SearchRank.NO_MATCH);
    }

    private int listTop() {
        return groups.isEmpty() ? LIST_TOP : LIST_TOP + GROUP_ROW;
    }

    private int listHeight() {
        return Math.max(ROW_HEIGHT, height - listTop() - FOOTER);
    }

    private int groupWidth(RegistryListSetting.Group<T> group) {
        return ClientFonts.guiAtGameScale().width(group.label()) + 2 * GROUP_PAD;
    }

    // The family buttons sit in a row centred over the columns.
    private int groupX(int index) {
        int row = -GROUP_GAP;
        int before = 0;
        for (int i = 0; i < groups.size(); i++) {
            if (i == index) {
                before = row + GROUP_GAP;
            }
            row += groupWidth(groups.get(i)) + GROUP_GAP;
        }
        return width / 2 - row / 2 + before;
    }

    // The family under the mouse or minus one.
    private int groupAt(double mx, double my) {
        for (int i = 0; i < groups.size(); i++) {
            if (SettingWidget.isOver(mx, my, groupX(i), GROUP_TOP, groupWidth(groups.get(i)), GROUP_HEIGHT)) {
                return i;
            }
        }
        return -1;
    }

    private boolean whollyChosen(RegistryListSetting.Group<T> group) {
        return group.members().stream().allMatch(setting::isChosen);
    }

    // A press picks whatever the family still lacks. A family that is all picked is dropped.
    private void pressGroup(RegistryListSetting.Group<T> group) {
        stopUndo();
        boolean drop = whollyChosen(group);
        Defaults.record(setting.getName(), setting.asSetting(), () -> {
            if (drop) {
                setting.removeAll(group.members());
            } else {
                setting.addAll(group.members());
            }
        });
        refresh();
    }

    private int leftX() {
        return width / 2 - COL_WIDTH - COL_GAP / 2;
    }

    private int rightX() {
        return width / 2 + COL_GAP / 2;
    }

    // The buttons sit in a row centred under the columns.
    private int buttonX(Button button) {
        int row = Button.values().length * (BUTTON_WIDTH + BUTTON_GAP) - BUTTON_GAP;
        return width / 2 - row / 2 + button.ordinal() * (BUTTON_WIDTH + BUTTON_GAP);
    }

    private int buttonY() {
        return listTop() + listHeight() + 8;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // The search box always takes the keys.
    @Override
    public boolean isTyping() {
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        GuiTheme.dimBackground(this, context);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTicks) {
        tooltip = null;
        Font font = ClientFonts.guiAtGameScale();

        context.centeredText(font, setting.getName(), width / 2, TITLE_Y, GuiTheme.text());
        search.render(context, font, width / 2 - SEARCH_WIDTH / 2, SEARCH_TOP, SEARCH_WIDTH,
            mouseX, mouseY, null);

        int hoveredGroup = groupAt(mouseX, mouseY);
        for (int i = 0; i < groups.size(); i++) {
            RegistryListSetting.Group<T> group = groups.get(i);
            GuiTheme.button(context, font, groupX(i), GROUP_TOP, groupWidth(group), GROUP_HEIGHT,
                group.label(), i == hoveredGroup, true);
            if (i == hoveredGroup) {
                tooltip = whollyChosen(group) ? "Takes every " + group.noun() + " off the list."
                    : "Picks every " + group.noun() + ".";
            }
        }

        renderColumn(context, font, leftX(), "available", available, leftBar, mouseX, mouseY, false);
        renderColumn(context, font, rightX(), "chosen", chosen, rightBar, mouseX, mouseY, true);

        for (Button button : Button.values()) {
            boolean over = overButton(mouseX, mouseY, button);
            GuiTheme.button(context, font, buttonX(button), buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT,
                label(button), over, live(button));
            if (over && tip(button) != null) {
                tooltip = tip(button);
            }
        }

        if (tooltip != null && !tooltip.isEmpty()) {
            RenderUtil.tooltip(context, font, List.of(tooltip), mouseX, mouseY, width, height,
                GuiTheme.bgTooltip(), GuiTheme.text());
        }
    }

    private boolean overButton(double mx, double my, Button button) {
        return SettingWidget.isOver(mx, my, buttonX(button), buttonY(), BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    // Add and clear act on what the search left in view. A narrowed list is picked in one go.
    private String label(Button button) {
        if (button == undoing) {
            return "undo";
        }
        return switch (button) {
            case DONE -> "done";
            case ADD -> search.isEmpty() ? "add all" : "add shown";
            case CLEAR -> search.isEmpty() ? "clear all" : "clear shown";
            case DEFAULTS -> "defaults";
        };
    }

    private boolean live(Button button) {
        return button == undoing || switch (button) {
            case DONE -> true;
            case ADD -> !available.isEmpty();
            case CLEAR -> !chosen.isEmpty();
            case DEFAULTS -> !setting.asSetting().isDefault();
        };
    }

    // Done and the bulk buttons say what they do on their face.
    private String tip(Button button) {
        if (button == undoing) {
            return "Brings back the list you had.";
        }
        return button == Button.DEFAULTS ? "Puts the list back to how it started." : null;
    }

    // Defaults saves every bulk press and can take it back. Only a press that changed the list
    // turns into undo.
    private void press(Button button) {
        if (button == Button.DONE) {
            onClose();
            return;
        }
        if (button == undoing) {
            Defaults.undo(undo);
            stopUndo();
        } else {
            Defaults.Change change = switch (button) {
                case ADD -> Defaults.record(setting.getName(), setting.asSetting(),
                    () -> setting.addAll(available.stream().map(Entry::value).toList()));
                case CLEAR -> Defaults.record(setting.getName(), setting.asSetting(), this::clearShown);
                default -> Defaults.reset(setting.getName(), List.of(setting.asSetting()));
            };
            if (change != null) {
                undoing = button;
                undo = change;
            }
        }
        refresh();
    }

    private void clearShown() {
        if (search.isEmpty()) {
            setting.clear();
        } else {
            chosen.forEach(entry -> setting.remove(entry.value()));
        }
    }

    private void stopUndo() {
        undoing = null;
        undo = null;
    }

    private void renderColumn(GuiGraphicsExtractor context, Font font, int x, String header,
                              List<Entry<T>> list, ScrollBar bar, int mouseX, int mouseY,
                              boolean picked) {
        int top = listTop();
        int h = listHeight();
        int total = list.size() * ROW_HEIGHT;
        bar.update(mouseY, total, h);
        boolean overflow = total > h;
        int rowW = ScrollBar.rowWidth(COL_WIDTH, total, h);

        context.text(font, header, x + 2, top - 11, GuiTheme.textDim(), false);
        String tally = String.valueOf(list.size());
        context.text(font, tally, x + COL_WIDTH - font.width(tally), top - 11,
            GuiTheme.textFaint(), false);
        RenderUtil.roundedBorderedRect(context, x - 1, top - 1, x + COL_WIDTH + 1, top + h + 1,
            GuiTheme.CORNER, GuiTheme.bgPanel(), GuiTheme.edge());
        context.guiRenderState.up();

        boolean mouseInside = SettingWidget.isOver(mouseX, mouseY, x, top, rowW, h);
        context.enableScissor(x, top, x + rowW, top + h);
        int rowY = top - bar.getOffset();
        for (Entry<T> entry : list) {
            if (rowY + ROW_HEIGHT > top && rowY < top + h) {
                boolean hovered = mouseInside && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
                boolean counts = picked && counted != null;
                int countLeft = counts ? countLeft(font, entry.value(), x + rowW) : x + rowW;
                if (hovered) {
                    context.fill(x, rowY, x + rowW, rowY + ROW_HEIGHT, GuiTheme.bgRowHover());
                    tooltip = counts && mouseX >= countLeft ? COUNT_HELP : entry.id();
                }
                context.fill(x, rowY + ROW_HEIGHT - 1, x + rowW, rowY + ROW_HEIGHT, GuiTheme.RULE);
                context.guiRenderState.up();
                if (!entry.icon().isEmpty()) {
                    context.item(entry.icon(), x + 3, rowY + 1);
                }
                context.guiRenderState.up();
                String label = entry.label();
                if (counts) {
                    renderCount(context, font, entry.value(), countLeft, x + rowW, rowY,
                        hovered && mouseX >= countLeft);
                    label = SettingWidget.trimEnd(font, entry.name(), countLeft - COUNT_GAP - x - LABEL_X);
                }
                context.text(font, label, x + LABEL_X, GuiTheme.textY(rowY, ROW_HEIGHT - 1),
                    hovered ? GuiTheme.accentText() : GuiTheme.text(), false);
            }
            rowY += ROW_HEIGHT;
        }
        if (list.isEmpty()) {
            context.text(font, "nothing here", x + 6, GuiTheme.textY(top, ROW_HEIGHT),
                GuiTheme.textFaint(), false);
        }
        context.disableScissor();

        if (overflow) {
            int trackX = ScrollBar.trackX(x, COL_WIDTH);
            bar.render(context, trackX, top, h, total,
                ScrollBar.isOverTrack(mouseX, mouseY, trackX, top, h));
        }
    }

    // The count at the end of a picked row or the number being typed there.
    private String countText(T value) {
        return value.equals(editing) ? countField.get() : GuiTheme.CROSS + counted.count(value);
    }

    // Where a picked row's count starts. It ends a gap in from the row's right edge.
    private int countLeft(Font font, T value, int rowRight) {
        return rowRight - COUNT_GAP - Math.max(MIN_COUNT_WIDTH, font.width(countText(value)));
    }

    private void renderCount(GuiGraphicsExtractor context, Font font, T value, int left, int rowRight,
                             int rowY, boolean hovered) {
        int textY = GuiTheme.textY(rowY, ROW_HEIGHT - 1);
        int right = rowRight - COUNT_GAP;
        if (value.equals(editing)) {
            countField.render(context, font, left, textY, right - left, GuiTheme.accentText(), true);
        } else {
            context.text(font, countText(value), left, textY,
                hovered ? GuiTheme.accentText() : GuiTheme.textDim(), false);
        }
        context.fill(left, textY + GuiTheme.TEXT_HEIGHT + 1, right, textY + GuiTheme.TEXT_HEIGHT + 2,
            value.equals(editing) || hovered ? GuiTheme.accentText() : GuiTheme.scrollThumb());
    }

    private void startCount(T value) {
        stopUndo();
        editing = value;
        countField.set(String.valueOf(counted.count(value)));
        countField.selectAll();
    }

    // An empty box keeps the count it had.
    private void commitCount() {
        if (editing == null) {
            return;
        }
        String typed = countField.get();
        if (!typed.isEmpty()) {
            counted.setCount(editing, typed.length() > MAX_DIGITS ? Integer.MAX_VALUE : Integer.parseInt(typed));
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
        }
        editing = null;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        commitCount();

        for (Button button : Button.values()) {
            if (overButton(mx, my, button)) {
                press(button);
                return true;
            }
        }
        if (search.click(mx, my) != SearchField.Click.MISSED) {
            return true;
        }
        int group = groupAt(mx, my);
        if (group >= 0) {
            pressGroup(groups.get(group));
            return true;
        }
        if (clickColumn(mx, my, leftX(), available, leftBar, true)) {
            return true;
        }
        if (clickColumn(mx, my, rightX(), chosen, rightBar, false)) {
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean clickColumn(double mx, double my, int x, List<Entry<T>> list,
                                ScrollBar bar, boolean adding) {
        int top = listTop();
        int h = listHeight();
        if (!SettingWidget.isOver(mx, my, x, top, COL_WIDTH, h)) {
            return false;
        }
        int total = list.size() * ROW_HEIGHT;
        int trackX = ScrollBar.trackX(x, COL_WIDTH);
        if (total > h && ScrollBar.isOverTrack(mx, my, trackX, top, h)) {
            bar.beginDrag((int) my);
            return true;
        }
        int index = (int) ((my - top + bar.getOffset()) / ROW_HEIGHT);
        if (index >= 0 && index < list.size()) {
            Entry<T> entry = list.get(index);
            int rowRight = x + ScrollBar.rowWidth(COL_WIDTH, total, h);
            if (!adding && counted != null && mx >= countLeft(ClientFonts.guiAtGameScale(), entry.value(), rowRight)) {
                startCount(entry.value());
                return true;
            }
            stopUndo();
            if (adding) {
                setting.add(entry.value());
            } else {
                setting.remove(entry.value());
            }
            OfflineClient.INSTANCE.getConfigManager().saveSoon();
            refresh();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        search.drag(event.x());
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        leftBar.release();
        rightBar.release();
        search.release();
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dy = ScrollBar.wheelDelta(scrollY);
        int h = listHeight();
        if (SettingWidget.isOver(mouseX, mouseY, leftX(), listTop(), COL_WIDTH, h)) {
            leftBar.scroll(dy, available.size() * ROW_HEIGHT, h);
            return true;
        }
        if (SettingWidget.isOver(mouseX, mouseY, rightX(), listTop(), COL_WIDTH, h)) {
            rightBar.scroll(dy, chosen.size() * ROW_HEIGHT, h);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (editing != null) {
            if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                commitCount();
            } else if (key == InputConstants.KEY_ESCAPE) {
                editing = null;
            } else {
                countField.keyPressed(event, DIGITS);
            }
            return true;
        }
        if (key == InputConstants.KEY_ESCAPE) {
            onClose();
            return true;
        }
        return search.keyPressed(event) || super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editing != null) {
            countField.charTyped((char) event.codepoint(), DIGITS);
            return true;
        }
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

    // The same ClickGUI instance is kept with its panel positions.
    @Override
    public void onClose() {
        commitCount();
        OfflineClient.INSTANCE.getConfigManager().saveNow();
        OfflineClient.MC.gui.setScreen(parent);
    }
}
