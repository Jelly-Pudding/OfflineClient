package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.render.ClientFonts;
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

// A middle click and undo keep working whilst the marks are hidden.
final class ResetMarks {

    private static final int ZONE_LEFT = 2;

    private static final int MARK = 4;
    private static final int MARK_GAP = 3;
    static final int MARK_ROOM = MARK_GAP + MARK;
    private static final int MARK_DROP = 2;

    private static final int DEFAULT_ROOM = 100;
    private static final float WASH = 0.125f;
    private static final float UNDO_SHADE = 0.6f;

    // The last control a press reset or undid. A second press on it this soon is the tail
    // of a double click and is ignored.
    private static Object lastControl;
    private static long lastActedAt;

    private enum State { CHANGED, HIDDEN, UNDO, NONE }

    private ResetMarks() {
    }

    static boolean shown() {
        return Modules.get(ClickGuiModule.class).showsResetMarks();
    }

    static int shownChanges(SettingBlock block) {
        return shown() ? block.changed() : 0;
    }

    // A tall row only takes part with its name band.
    static boolean inZone(double mx, double my, int rowX, int top) {
        return SettingWidget.isOver(mx, my, rowX + ZONE_LEFT, top,
            SettingWidget.blockContentX(rowX) - rowX - ZONE_LEFT, GuiTheme.SETTING_HEIGHT);
    }

    static boolean onLine(double mx, double my, int rowX, int right, int top, int height) {
        return SettingWidget.isOver(mx, my, rowX + ZONE_LEFT, top, right - rowX - ZONE_LEFT, height);
    }

    static void lightZone(GuiGraphicsExtractor context, int rowX, int top) {
        context.fill(rowX + ZONE_LEFT, top, rowX + SettingWidget.INDENT, top + GuiTheme.SETTING_HEIGHT,
            GuiTheme.HOVER_WASH);
        context.guiRenderState.up();
    }

    static void arrow(GuiGraphicsExtractor context, int rowX, int textTop, boolean forward, int colour) {
        RenderUtil.hookArrow(context, glyphX(rowX, RenderUtil.HOOK_WIDTH), glyphY(textTop, RenderUtil.HOOK_HEIGHT),
            forward, colour);
    }

    static void tick(GuiGraphicsExtractor context, int rowX, int textTop, int colour) {
        RenderUtil.tick(context, glyphX(rowX, RenderUtil.TICK_SIZE), glyphY(textTop, RenderUtil.TICK_SIZE), colour);
    }

    private static int glyphX(int rowX, int width) {
        return rowX + ZONE_LEFT + (SettingWidget.INDENT - ZONE_LEFT - width) / 2;
    }

    private static int glyphY(int textTop, int height) {
        return textTop + (GuiTheme.TEXT_HEIGHT - height) / 2;
    }

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

    static String withCount(String description, int changed) {
        return changed == 0 ? description : description + " " + Tally.counted(changed, "setting") + " changed.";
    }

    // A bind is named after its module.
    private static String nameOf(SettingBlock block, Setting<?> setting) {
        return setting == block.bind() ? block.name() + " bind" : setting.getName();
    }

    // An armed action stands down instead.
    static void middle(SettingBlock block, Setting<?> setting) {
        if (setting instanceof ActionSetting) {
            setting.reset();
        } else if (setting.isChanged()) {
            Defaults.reset(nameOf(block, setting), List.of(setting));
        }
    }

    static boolean tooSoon(Object control) {
        return control == lastControl
            && System.currentTimeMillis() - lastActedAt < MouseHandler.DOUBLE_CLICK_THRESHOLD_MS;
    }

    static void acted(Object control) {
        lastControl = control;
        lastActedAt = System.currentTimeMillis();
    }

    // A setting the layout hides answers to the nearest shown row above it in its family and rings
    // that row whilst it differs.
    static final class Strip {

        private final SettingBlock block;
        private final boolean marksShown;
        private final int left;
        private final int right;
        // Minus one for a shown row and for a hidden setting with no shown row above it.
        private final int[] chargedTo;
        private final int[] hiddenChanged;
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
            foot = ResetFoot.state(block.owner(), changed);
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

        // The bind is left out.
        int changed() {
            return changed;
        }

        int hidden() {
            return hidden;
        }

        ResetFoot.State foot() {
            return foot;
        }

        boolean hasFoot() {
            return ResetFoot.shows(block, foot);
        }

        void wash(GuiGraphicsExtractor context, int row, int x, int top, int width, int height) {
            if (marksShown && fresh[row] > 0) {
                context.fill(x, top, x + width, top + height, ColorUtil.fade(GuiTheme.GREEN, WASH * fresh[row]));
                context.guiRenderState.up();
            }
        }

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

    private static String defaultWords(Setting<?> setting) {
        String text = setting.defaultText();
        Font font = ClientFonts.gui();
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
