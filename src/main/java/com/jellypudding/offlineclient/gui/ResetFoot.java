package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.setting.Defaults;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.Flash;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.concurrent.TimeUnit;

// The row at the foot of a settings block that puts every changed setting back. It waits for
// a second press first and afterwards offers to undo the reset in the same place.
final class ResetFoot {

    enum State { ARMED, OFFER, UNDONE, READY, ABSENT }

    private static final long CONFIRM_MS = TimeUnit.SECONDS.toMillis(ActionSetting.CONFIRM_SECONDS);
    private static final long OFFER_MS = 10_000;
    // The offer never runs out under the pointer and lasts this much longer once it leaves.
    private static final long HOLD_MS = 1000;
    // Between the label and the count.
    private static final int COUNT_GAP = 6;

    // One block at a time. The slots are static and outlive a screen that is built again.
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
    private static final Slot UNDONE = new Slot();
    // The reset the offer would take back.
    private static Defaults.Change offered;

    private ResetFoot() {
    }

    // The first state that applies. An armed block with nothing left to reset stands down and
    // an offer ends for good once the block changes again.
    static State state(Object owner, int changed) {
        if (ARMED.holds(owner)) {
            if (changed > 0) {
                return State.ARMED;
            }
            ARMED.clear();
        }
        if (OFFER.holds(owner)) {
            if (changed == 0 && Defaults.canUndo(offered)) {
                return State.OFFER;
            }
            OFFER.clear();
        }
        if (UNDONE.holds(owner)) {
            return State.UNDONE;
        }
        return changed > 0 ? State.READY : State.ABSENT;
    }

    // True whilst the block ends with the reset row. The ClickGUI module can hide it.
    static boolean shows(SettingBlock block) {
        return ResetMarks.shown() && state(block.owner(), block.changed()) != State.ABSENT;
    }

    // Leaves whatever waits for a second press. A screen closing does this.
    static void disarm() {
        ARMED.clear();
    }

    static void render(GuiGraphicsExtractor context, Font font, ResetMarks.Strip strip, int top, int cx, int cw,
                       int mouseX, int mouseY, boolean hoverAllowed, SettingWidget.Host host) {
        int rowX = strip.left();
        boolean hovered = hoverAllowed
            && ResetMarks.onLine(mouseX, mouseY, rowX, strip.right(), top, GuiTheme.SETTING_HEIGHT);
        if (hovered) {
            context.fill(cx, top, cx + cw, top + GuiTheme.SETTING_HEIGHT, GuiTheme.HOVER_WASH);
            ResetMarks.lightZone(context, rowX, top);
        }
        State state = strip.foot();
        if (hovered && state == State.OFFER) {
            OFFER.until = Math.max(OFFER.until, System.currentTimeMillis() + HOLD_MS);
        }
        int textTop = GuiTheme.textY(top, GuiTheme.SETTING_HEIGHT);
        String label;
        int ink;
        switch (state) {
            case ARMED -> {
                ResetMarks.arrow(context, rowX, textTop, false, GuiTheme.RED_TEXT);
                label = "Click again";
                ink = GuiTheme.RED_TEXT;
            }
            case OFFER -> {
                float strength = Flash.strength(OFFER.until);
                ResetMarks.tick(context, rowX, textTop, ColorUtil.fade(GuiTheme.GREEN, strength));
                label = "Undo reset";
                ink = ColorUtil.fade(hovered ? GuiTheme.text() : GuiTheme.accentText(), strength);
            }
            case UNDONE -> {
                float strength = Flash.strength(UNDONE.until);
                ResetMarks.tick(context, rowX, textTop, ColorUtil.fade(GuiTheme.GREEN, strength));
                label = "Undone";
                ink = ColorUtil.lerp(GuiTheme.textDim(), GuiTheme.GREEN, strength);
            }
            default -> {
                ResetMarks.arrow(context, rowX, textTop, false, hovered ? GuiTheme.text() : GuiTheme.accentText());
                label = "Reset settings";
                ink = hovered ? GuiTheme.text() : GuiTheme.textDim();
            }
        }
        int room = cw - 2 * GuiTheme.PAD;
        context.text(font, SettingWidget.trimEnd(font, label, room), cx + GuiTheme.PAD, textTop, ink, false);
        if (state == State.ARMED || state == State.READY) {
            String count = count(font, label, strip.changed(), room);
            if (count != null) {
                context.text(font, count, cx + cw - GuiTheme.PAD - font.width(count), textTop,
                    state == State.ARMED ? GuiTheme.RED_TEXT : GuiTheme.accentText(), false);
            }
        }
        if (hovered) {
            host.setTooltip(tooltip(strip, state));
        }
    }

    // The count with its word whilst it fits beside the label and the bare number after that.
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
            case ARMED -> "Click again within " + Tally.counted(ActionSetting.CONFIRM_SECONDS, "second")
                + " to put " + (changed == 1 ? "it" : "them") + " back.";
            case OFFER -> "Click to undo the reset. " + InputUtil.undoKeys() + " does the same.";
            default -> changed == 0 ? null : readyTip(strip, changed);
        };
    }

    // What a reset would do and what it leaves alone. A tip cut short loses the general part first.
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

    // The first press arms the row and the second resets. A press on the offer takes the reset back.
    static void press(SettingBlock block, int button) {
        Object owner = block.owner();
        if (ResetMarks.tooSoon(owner)) {
            return;
        }
        boolean main = InputUtil.isLeft(button) || InputUtil.isRight(button);
        long now = System.currentTimeMillis();
        switch (state(owner, block.changed())) {
            case ARMED -> {
                if (InputUtil.isMiddle(button)) {
                    ARMED.clear();
                } else if (main) {
                    ARMED.clear();
                    offered = Defaults.reset(block.name(), block.settings());
                    OFFER.take(owner, now + OFFER_MS);
                    ResetMarks.acted(owner);
                }
            }
            case OFFER -> {
                if (main) {
                    Defaults.undo(offered);
                    OFFER.clear();
                    UNDONE.take(owner, now + Flash.SHOWN_MS);
                    ResetMarks.acted(owner);
                }
            }
            case UNDONE, READY -> {
                if (main) {
                    ARMED.take(owner, now + CONFIRM_MS);
                }
            }
            case ABSENT -> {
            }
        }
    }
}
