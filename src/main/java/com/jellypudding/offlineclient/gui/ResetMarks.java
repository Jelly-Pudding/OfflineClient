package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

// The marks in the strip left of a settings block. A mark says a row differs from how it
// shipped and a press on it puts the row back. The reset row at the foot of a block is ResetFoot.
// The ClickGUI module can hide both. A middle click and undo keep working whilst they are hidden.
final class ResetMarks {

    // The strip starts past the panel's resize band and ends where the rows start. Only its
    // part left of the block's accent rule lights up.
    private static final int ZONE_LEFT = 2;

    // The square mark and the gap between a name and the mark after it.
    private static final int MARK = 4;
    private static final int MARK_GAP = 3;
    // How much room a module or piece row gives up whilst its name carries a mark.
    static final int MARK_ROOM = MARK_GAP + MARK;
    // How far the mark sits below the top of the text it stands beside. Level with lower case.
    private static final int MARK_DROP = 2;

    // A long default in a tooltip is cut to this width.
    private static final int DEFAULT_ROOM = 100;
    // How strongly a row that was just put back is washed green.
    private static final float WASH = 0.125f;
    // The forward arrow whilst the pointer is on its row but not on the arrow.
    private static final float UNDO_SHADE = 0.6f;

    // The last control a press reset or undid. A second press on it this soon is the tail
    // of a double click and is ignored.
    private static Object lastControl;
    private static long lastActedAt;

    private enum State { CHANGED, HIDDEN, UNDO, NONE }

    private ResetMarks() {
    }

    // Before the client builds its modules the marks show.
    static boolean shown() {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        return gui == null || gui.showsResetMarks();
    }

    // How many settings of a block differ from their defaults. Nought whilst the marks are hidden.
    static int shownChanges(SettingBlock block) {
        return shown() ? block.changed() : 0;
    }

    // The hit zone of the strip beside a row. A tall row only takes part with its name band.
    static boolean inZone(double mx, double my, int rowX, int top) {
        return SettingWidget.isOver(mx, my, rowX + ZONE_LEFT, top,
            SettingWidget.blockContentX(rowX) - rowX - ZONE_LEFT, GuiTheme.SETTING_HEIGHT);
    }

    // A whole row from the strip to the right edge of the block.
    static boolean onLine(double mx, double my, int rowX, int right, int top, int height) {
        return SettingWidget.isOver(mx, my, rowX + ZONE_LEFT, top, right - rowX - ZONE_LEFT, height);
    }

    // The part of the strip beside a row that lights up under the pointer.
    static void lightZone(GuiGraphicsExtractor context, int rowX, int top) {
        context.fill(rowX + ZONE_LEFT, top, rowX + SettingWidget.INDENT, top + GuiTheme.SETTING_HEIGHT,
            GuiTheme.HOVER_WASH);
        context.guiRenderState.up();
    }

    // The arrow that puts a row back or brings back what it held.
    static void arrow(GuiGraphicsExtractor context, int rowX, int textTop, boolean forward, int colour) {
        RenderUtil.hookArrow(context, glyphX(rowX, RenderUtil.HOOK_WIDTH), glyphY(textTop, RenderUtil.HOOK_HEIGHT),
            forward, colour);
    }

    static void tick(GuiGraphicsExtractor context, int rowX, int textTop, int colour) {
        RenderUtil.tick(context, glyphX(rowX, RenderUtil.TICK_SIZE), glyphY(textTop, RenderUtil.TICK_SIZE), colour);
    }

    // Centred in the lit part of the strip.
    private static int glyphX(int rowX, int width) {
        return rowX + ZONE_LEFT + (SettingWidget.INDENT - ZONE_LEFT - width) / 2;
    }

    // Centred on the text of the row.
    private static int glyphY(int textTop, int height) {
        return textTop + (GuiTheme.TEXT_HEIGHT - height) / 2;
    }

    // The square after the name of a module or a piece with changed settings.
    static void headerMark(GuiGraphicsExtractor context, int nameRight, int textTop) {
        square(context, nameRight + MARK_GAP, textTop, GuiTheme.accentText(), true);
    }

    // Solid for a row that changed and a ring for a change folded away under it.
    private static void square(GuiGraphicsExtractor context, int x, int textTop, int colour, boolean solid) {
        int y = textTop + MARK_DROP;
        if (solid) {
            RenderUtil.roundedRect(context, x, y, x + MARK, y + MARK, 1, colour);
        } else {
            RenderUtil.roundedOutline(context, x, y, x + MARK, y + MARK, 1, colour);
        }
    }

    // A description with how many settings differ from their defaults after it.
    static String withCount(String description, int changed) {
        return changed == 0 ? description : description + " " + Tally.counted(changed, "setting") + " changed.";
    }

    // What chat calls the reset of one row. A bind is named after its module.
    private static String nameOf(SettingBlock block, Setting<?> setting) {
        return setting == block.bind() ? block.name() + " bind" : setting.getName();
    }

    // A middle press on a row puts that row back. An armed action stands down instead.
    static void middle(SettingBlock block, Setting<?> setting) {
        if (setting instanceof ActionSetting) {
            setting.reset();
        } else if (setting.isChanged()) {
            Defaults.reset(nameOf(block, setting), List.of(setting));
        }
    }

    // True for a press that lands too soon after one on the same control that changed something.
    static boolean tooSoon(Object control) {
        return control == lastControl
            && System.currentTimeMillis() - lastActedAt < MouseHandler.DOUBLE_CLICK_THRESHOLD_MS;
    }

    static void acted(Object control) {
        lastControl = control;
        lastActedAt = System.currentTimeMillis();
    }

    // One block's strip worked out once for each draw and each press. A setting the layout hides
    // answers to the nearest shown row above it in its family and rings that row whilst it differs.
    static final class Strip {

        private final SettingBlock block;
        private final boolean marksShown;
        private final int left;
        private final int right;
        // The shown row that answers for each hidden setting. Minus one for a shown row and
        // for a hidden setting with no shown row above it.
        private final int[] chargedTo;
        // How many changed settings each shown row answers for.
        private final int[] hiddenChanged;
        // The freshest reset or undo of each shown row and what it answers for.
        private final float[] fresh;
        private final int changed;
        private final int hidden;
        private final ResetFoot.State foot;

        Strip(SettingBlock block, boolean[] shown, int left, int right) {
            this.block = block;
            marksShown = shown();
            this.left = left;
            this.right = right;
            int rows = block.rows();
            chargedTo = new int[rows];
            hiddenChanged = new int[rows];
            fresh = new float[rows];
            Map<Setting<?>, Integer> index = null;
            int changedCount = 0;
            int hiddenCount = 0;
            for (int i = 0; i < rows; i++) {
                Setting<?> setting = block.row(i);
                chargedTo[i] = -1;
                if (!shown[i] && setting.getParent() != null) {
                    if (index == null) {
                        index = indexOf(block);
                    }
                    chargedTo[i] = shownAncestor(setting, index, shown);
                }
                if (setting.isChanged() && setting != block.bind()) {
                    changedCount++;
                    if (!shown[i]) {
                        hiddenCount++;
                    }
                    if (chargedTo[i] >= 0) {
                        hiddenChanged[chargedTo[i]]++;
                    }
                }
                int answers = shown[i] ? i : chargedTo[i];
                if (answers >= 0) {
                    fresh[answers] = Math.max(fresh[answers], Defaults.freshness(setting));
                }
            }
            changed = changedCount;
            hidden = hiddenCount;
            foot = marksShown ? ResetFoot.state(block.owner(), changed) : ResetFoot.State.ABSENT;
        }

        private static Map<Setting<?>, Integer> indexOf(SettingBlock block) {
            Map<Setting<?>, Integer> index = new IdentityHashMap<>();
            for (int i = 0; i < block.rows(); i++) {
                index.put(block.row(i), i);
            }
            return index;
        }

        // The walk stops at a parent outside the block such as the switch of a HUD piece.
        private static int shownAncestor(Setting<?> setting, Map<Setting<?>, Integer> index, boolean[] shown) {
            for (Setting<?> up = setting.getParent(); up != null; up = up.getParent()) {
                Integer at = index.get(up);
                if (at == null) {
                    return -1;
                }
                if (shown[at]) {
                    return at;
                }
            }
            return -1;
        }

        // The row and every hidden setting it answers for.
        private List<Setting<?>> family(int row) {
            List<Setting<?>> family = new ArrayList<>();
            family.add(block.row(row));
            for (int i = 0; i < chargedTo.length; i++) {
                if (chargedTo[i] == row) {
                    family.add(block.row(i));
                }
            }
            return family;
        }

        // Undo is only asked about for the row under the pointer and for rows still lit up.
        private State state(int row, boolean askUndo) {
            if (block.row(row).isChanged()) {
                return State.CHANGED;
            }
            if (hiddenChanged[row] > 0) {
                return State.HIDDEN;
            }
            if (askUndo) {
                for (Setting<?> setting : family(row)) {
                    if (Defaults.canUndo(setting)) {
                        return State.UNDO;
                    }
                }
            }
            return State.NONE;
        }

        // A block armed for a reset shows in red what the reset would put back. Its bind stays.
        private boolean red(int row) {
            return foot == ResetFoot.State.ARMED && block.row(row) != block.bind();
        }

        SettingBlock block() {
            return block;
        }

        int left() {
            return left;
        }

        int right() {
            return right;
        }

        // How many settings differ from their defaults. The bind is left out.
        int changed() {
            return changed;
        }

        // How many of the changed settings the layout does not show.
        int hidden() {
            return hidden;
        }

        ResetFoot.State foot() {
            return foot;
        }

        boolean hasFoot() {
            return foot != ResetFoot.State.ABSENT;
        }

        // A green wash under a row that was just put back or brought back.
        void wash(GuiGraphicsExtractor context, int row, int x, int top, int width, int height) {
            if (marksShown && fresh[row] > 0) {
                context.fill(x, top, x + width, top + height, ColorUtil.fade(GuiTheme.GREEN, WASH * fresh[row]));
                context.guiRenderState.up();
            }
        }

        // The mark beside a row. With the pointer on the row it turns into the arrow that acts.
        void mark(GuiGraphicsExtractor context, int row, int top, int height, int textTop, int mouseX, int mouseY,
                  boolean hoverAllowed, SettingWidget.Host host) {
            if (!marksShown) {
                return;
            }
            boolean onRow = hoverAllowed && onLine(mouseX, mouseY, left, right, top, height);
            State state = state(row, onRow || fresh[row] > 0);
            if (state == State.NONE) {
                return;
            }
            boolean onArrow = onRow && inZone(mouseX, mouseY, left, top);
            if (onRow) {
                lightZone(context, left, top);
            }
            if (state == State.UNDO) {
                arrow(context, left, textTop, true, onArrow ? GuiTheme.GREEN
                    : ColorUtil.fade(GuiTheme.GREEN, onRow ? UNDO_SHADE : fresh[row]));
            } else {
                int colour = red(row) ? GuiTheme.RED_TEXT : onArrow ? GuiTheme.text() : GuiTheme.accentText();
                if (onRow) {
                    arrow(context, left, textTop, false, colour);
                } else {
                    square(context, glyphX(left, MARK), textTop, colour, state == State.CHANGED);
                }
            }
            if (onArrow) {
                context.requestCursor(CursorTypes.POINTING_HAND);
                host.setTooltip(tooltip(row, state));
            }
        }

        // A press on the arrow beside a row. Nothing happens beside a row with nothing to do or
        // whilst the marks are hidden.
        void press(int row, int button) {
            Setting<?> setting = block.row(row);
            if (!marksShown || tooSoon(setting)) {
                return;
            }
            boolean main = InputUtil.isLeft(button) || InputUtil.isRight(button);
            switch (state(row, true)) {
                case CHANGED -> {
                    if (main || InputUtil.isMiddle(button)) {
                        Defaults.reset(nameOf(block, setting), List.of(setting));
                        acted(setting);
                    }
                }
                case HIDDEN -> {
                    if (main) {
                        Defaults.reset(nameOf(block, setting), family(row));
                        acted(setting);
                    }
                }
                case UNDO -> {
                    if (main) {
                        Defaults.undo(family(row));
                        acted(setting);
                    }
                }
                case NONE -> {
                }
            }
        }

        private String tooltip(int row, State state) {
            int under = hiddenChanged[row];
            return switch (state) {
                case CHANGED -> backTip(block.row(row)) + (under == 0 ? ""
                    : " " + Tally.counted(under, "hidden setting") + " under it changed too.");
                case HIDDEN -> Tally.counted(under, "hidden setting") + " under it changed. Click to put "
                    + (under == 1 ? "it" : "them") + " back.";
                case UNDO -> "Click to undo the reset.";
                case NONE -> null;
            };
        }
    }

    private static String backTip(Setting<?> setting) {
        String start = defaultWords(setting);
        return start == null ? "Click here or middle click the row to put it back to its default."
            : "The default is " + start + ". Click here or middle click the row to put it back.";
    }

    // A long default is cut short. Typed text keeps the quotes round it.
    private static String defaultWords(Setting<?> setting) {
        String text = setting.defaultText();
        Font font = OfflineClient.MC.font;
        if (text == null || font.width(text) <= DEFAULT_ROOM) {
            return text;
        }
        boolean quoted = text.length() > 1 && text.startsWith("\"") && text.endsWith("\"");
        if (!quoted) {
            return SettingWidget.trimEnd(font, text, DEFAULT_ROOM);
        }
        String inside = text.substring(1, text.length() - 1);
        return "\"" + SettingWidget.trimEnd(font, inside, DEFAULT_ROOM - font.width("\"\"")) + "\"";
    }
}
