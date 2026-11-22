package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.util.Armed;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Tally;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

final class ResetFoot {

    enum State { ARMED, OFFER, SAID, READY, ABSENT }

    private enum Tool { COPY, PASTE }

    // The foot of the HUD editor keeps to the same time.
    static final long OFFER_MS = 10_000;
    // The offer never runs out under the pointer and lasts this much longer once it leaves.
    private static final long HOLD_MS = 1000;
    private static final int COUNT_GAP = 6;

    private static final String RESET = "reset";
    private static final String PASTE = "paste";

    private static final int TOOL = 9;
    private static final int TOOL_GAP = 3;
    private static final int TOOLS_ROOM = 2 * TOOL + TOOL_GAP + 4;
    // Two sheets one over the other and a clipboard as left top right bottom spans.
    private static final int[][] COPY_ICON = {{2, 0, 9, 1}, {8, 1, 9, 7}, {2, 1, 3, 2}, {7, 6, 8, 7},
        {0, 2, 7, 3}, {0, 8, 7, 9}, {0, 3, 1, 8}, {6, 3, 7, 8}};
    private static final int[][] PASTE_ICON = {{3, 0, 6, 2}, {1, 1, 8, 2}, {1, 8, 8, 9}, {1, 2, 2, 8},
        {7, 2, 8, 8}, {3, 4, 6, 5}, {3, 6, 6, 7}};

    // The slots are static and outlive a screen that is built again.
    private static final class Slot {

        private Object owner;
        private long until;

        private boolean holds(Object block) {
            return owner == block && System.currentTimeMillis() <= until;
        }

        private void take(Object block, long time) {
            owner = block;
            until = time;
        }

        private void clear() {
            owner = null;
        }
    }

    private static final Slot ARMED = new Slot();
    private static final Slot OFFER = new Slot();
    private static final Slot SAID = new Slot();
    private static Defaults.Change offered;
    private static String offeredWord;
    private static int offeredChanged;
    private static String said;
    private static boolean saidDone;

    private ResetFoot() {
    }

    // An armed block with nothing left to reset stands down and an offer ends for good once the
    // block changes again.
    static State state(Object owner, int changed) {
        if (ARMED.holds(owner)) {
            if (resettable(changed)) {
                return State.ARMED;
            }
            ARMED.clear();
        }
        if (OFFER.holds(owner)) {
            if (changed == offeredChanged && Defaults.canUndo(offered)) {
                return State.OFFER;
            }
            OFFER.clear();
        }
        if (SAID.holds(owner)) {
            return State.SAID;
        }
        return resettable(changed) ? State.READY : State.ABSENT;
    }

    private static boolean resettable(int changed) {
        return ResetMarks.shown() && changed > 0;
    }

    private static boolean pressable(State state, int changed) {
        return switch (state) {
            case ARMED, OFFER, READY -> true;
            case SAID -> resettable(changed);
            case ABSENT -> false;
        };
    }

    static boolean shows(SettingBlock block) {
        return shows(block, state(block.owner(), block.changed()));
    }

    static boolean shows(SettingBlock block, State state) {
        return state != State.ABSENT || SettingsCopy.holds(block);
    }

    static void disarm() {
        ARMED.clear();
    }

    static boolean undoKey(KeyEvent event) {
        if (!InputUtil.isUndo(event)) {
            return false;
        }
        ChatUtil.undone(Defaults.undoNewest());
        return true;
    }

    private static void say(Object owner, String words, boolean done) {
        said = words;
        saidDone = done;
        SAID.take(owner, System.currentTimeMillis() + Flash.SHOWN_MS);
    }

    private static void offer(Object owner, Defaults.Change change, String word, int changed) {
        offered = change;
        offeredWord = word;
        offeredChanged = changed;
        OFFER.take(owner, System.currentTimeMillis() + OFFER_MS);
    }

    static void render(GuiGraphicsExtractor context, Font font, ResetMarks.Strip strip, int top, int cx, int cw,
                       int mouseX, int mouseY, boolean hoverAllowed, SettingWidget.Host host) {
        int rowX = strip.left();
        boolean tools = SettingsCopy.holds(strip.block());
        boolean onRow = hoverAllowed
            && ResetMarks.onLine(mouseX, mouseY, rowX, strip.right(), top, GuiTheme.SETTING_HEIGHT);
        int end = textEnd(strip.right(), tools);
        boolean onText = onRow && (!tools || mouseX < end);
        Tool tool = onRow && !onText ? toolAt(mouseX, strip.right()) : null;
        State state = strip.foot();
        boolean hovered = onText && pressable(state, strip.changed());
        int room = end - cx - GuiTheme.PAD;
        if (hovered) {
            context.fill(cx, top, tools ? end : cx + cw, top + GuiTheme.SETTING_HEIGHT, GuiTheme.HOVER_WASH);
            ResetMarks.lightZone(context, rowX, top);
        }
        if (hovered && state == State.OFFER) {
            OFFER.until = Math.max(OFFER.until, System.currentTimeMillis() + HOLD_MS);
        }
        int textTop = GuiTheme.textY(top, GuiTheme.SETTING_HEIGHT);
        String label = "";
        int ink = GuiTheme.textDim();
        switch (state) {
            case ARMED -> {
                ResetMarks.arrow(context, rowX, textTop, false, GuiTheme.RED_TEXT);
                label = "Click again";
                ink = GuiTheme.RED_TEXT;
            }
            case OFFER -> {
                float strength = Flash.strength(OFFER.until);
                ResetMarks.tick(context, rowX, textTop, ColorUtil.fade(GuiTheme.GREEN, strength));
                label = "Undo " + offeredWord;
                ink = ColorUtil.fade(hovered ? GuiTheme.text() : GuiTheme.accentText(), strength);
            }
            case SAID -> {
                float strength = Flash.strength(SAID.until);
                if (saidDone) {
                    ResetMarks.tick(context, rowX, textTop, ColorUtil.fade(GuiTheme.GREEN, strength));
                }
                label = said;
                ink = ColorUtil.lerp(GuiTheme.textDim(), saidDone ? GuiTheme.GREEN : GuiTheme.RED_TEXT, strength);
            }
            case READY -> {
                ResetMarks.arrow(context, rowX, textTop, false, hovered ? GuiTheme.text() : GuiTheme.accentText());
                label = font.width("Reset settings") <= room ? "Reset settings" : "Reset";
                ink = hovered ? GuiTheme.text() : GuiTheme.textDim();
            }
            case ABSENT -> {
            }
        }
        context.text(font, SettingWidget.trimEnd(font, label, room), cx + GuiTheme.PAD, textTop, ink, false);
        if (state == State.ARMED || state == State.READY) {
            String count = count(font, label, strip.changed(), room);
            if (count != null) {
                context.text(font, count, end - font.width(count), textTop,
                    state == State.ARMED ? GuiTheme.RED_TEXT : GuiTheme.accentText(), false);
            }
        }
        if (tools) {
            for (Tool each : Tool.values()) {
                renderTool(context, each, strip.right(), top, each == tool);
            }
        }
        if (tool != null) {
            context.requestCursor(CursorTypes.POINTING_HAND);
            host.setTooltip(toolTip(tool, strip.block()));
        } else if (hovered) {
            host.setTooltip(tooltip(strip, state));
        }
    }

    private static int textEnd(int right, boolean tools) {
        return right - GuiTheme.PAD - (tools ? TOOLS_ROOM : 0);
    }

    private static int toolX(Tool tool, int right) {
        return right - GuiTheme.PAD - (Tool.values().length - tool.ordinal()) * (TOOL + TOOL_GAP) + TOOL_GAP;
    }

    private static Tool toolAt(double mx, int right) {
        for (Tool tool : Tool.values()) {
            int x = toolX(tool, right);
            if (mx >= x - 1 && mx < x + TOOL + 1) {
                return tool;
            }
        }
        return null;
    }

    private static void renderTool(GuiGraphicsExtractor context, Tool tool, int right, int top, boolean lit) {
        int x = toolX(tool, right);
        int y = top + (GuiTheme.SETTING_HEIGHT - TOOL) / 2;
        if (lit) {
            context.fill(x - 2, y - 2, x + TOOL + 2, y + TOOL + 2, GuiTheme.HOVER_WASH);
            context.guiRenderState.up();
        }
        int ink = lit ? GuiTheme.text() : GuiTheme.textFaint();
        for (int[] span : tool == Tool.COPY ? COPY_ICON : PASTE_ICON) {
            context.fill(x + span[0], y + span[1], x + span[2], y + span[3], ink);
        }
    }

    private static String toolTip(Tool tool, SettingBlock block) {
        String name = block.name();
        if (tool == Tool.COPY) {
            return "Copies the settings of " + name + " to the clipboard as text. Keys and secrets stay out of it.";
        }
        String place = block.owner() instanceof HudElement ? " A copy of another piece leaves it where it is." : "";
        return "Reads a copy of settings from the clipboard into " + name + "." + place + " " + InputUtil.undoKeys()
            + " takes it back.";
    }

    private static String count(Font font, String label, int changed, int room) {
        int free = room - font.width(label) - COUNT_GAP;
        String full = changed + " changed";
        if (font.width(full) <= free) {
            return full;
        }
        String bare = String.valueOf(changed);
        return font.width(bare) <= free ? bare : null;
    }

    private static String tooltip(ResetMarks.Strip strip, State state) {
        int changed = strip.changed();
        return switch (state) {
            case ARMED -> "Click again within " + Tally.counted(Armed.CONFIRM_SECONDS, "second")
                + " to put " + (changed == 1 ? "it" : "them") + " back.";
            case OFFER -> "Click to undo the " + offeredWord + ". " + InputUtil.undoKeys() + " does the same.";
            default -> changed == 0 ? null : readyTip(strip, changed);
        };
    }

    private static String readyTip(ResetMarks.Strip strip, int changed) {
        int hidden = strip.hidden();
        String tip = changed == 1 ? "Puts the changed setting back to its default."
            : "Puts the " + Tally.counted(changed, "changed setting") + " back to their defaults.";
        if (hidden > 0) {
            tip += changed == 1 ? " It is hidden right now."
                : " " + hidden + " of them " + (hidden == 1 ? "is" : "are") + " hidden right now.";
        }
        return tip + " " + strip.block().keeps();
    }

    static void press(SettingBlock block, int button, double mx, int right) {
        Object owner = block.owner();
        if (ResetMarks.tooSoon(owner)) {
            return;
        }
        if (SettingsCopy.holds(block) && mx >= textEnd(right, true)) {
            Tool tool = toolAt(mx, right);
            if (tool != null && InputUtil.isLeft(button)) {
                use(tool, block);
            }
            return;
        }
        boolean main = InputUtil.isLeft(button) || InputUtil.isRight(button);
        switch (state(owner, block.changed())) {
            case ARMED -> {
                if (InputUtil.isMiddle(button)) {
                    ARMED.clear();
                } else if (main) {
                    ARMED.clear();
                    offer(owner, Defaults.reset(block.name(), block.settings()), RESET, 0);
                    ResetMarks.acted(owner);
                }
            }
            case OFFER -> {
                if (main) {
                    Defaults.undo(offered);
                    OFFER.clear();
                    say(owner, "Undone", true);
                    ResetMarks.acted(owner);
                }
            }
            case READY, SAID -> {
                if (main && resettable(block.changed())) {
                    ARMED.take(owner, System.currentTimeMillis() + Armed.CONFIRM_MS);
                }
            }
            case ABSENT -> {
            }
        }
    }

    private static void use(Tool tool, SettingBlock block) {
        Object owner = block.owner();
        String name = block.name();
        if (tool == Tool.COPY) {
            int copied = SettingsCopy.copy(block);
            say(owner, "Copied", true);
            ChatUtil.message("§7Copied §f" + Tally.counted(copied, "setting") + "§7 of §b" + name
                + "§7 to the clipboard.");
        } else {
            SettingsCopy.Pasted pasted = SettingsCopy.paste(block);
            ARMED.clear();
            OFFER.clear();
            if (pasted.change() == null) {
                say(owner, "Not pasted", false);
                ChatUtil.error(pasted.line());
            } else {
                offer(owner, pasted.change(), PASTE, block.changed());
                ChatUtil.undoable("§7" + pasted.line(), pasted.change());
            }
        }
        ResetMarks.acted(owner);
    }
}
