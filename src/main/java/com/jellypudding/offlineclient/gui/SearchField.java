package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;

// A one line box to type into. A click lands the caret where it falls and a drag
// selects. The cross at the right end clears the text and lets go of the keys.
public final class SearchField {

    public static final int HEIGHT = 14;
    private static final int INDENT = GuiTheme.PAD;
    private static final int CLEAR_ZONE = 12;
    // A press this close past the last letter still counts as on the text.
    private static final int TEXT_SLACK = 4;

    // What a click landed on. Space is the part of the box the text does not reach.
    public enum Click { MISSED, CLEARED, TEXT, SPACE }

    private final TextField text = new TextField();
    private final String placeholder;
    // Told whenever the text changes however it changed.
    private final Runnable onChange;
    // A sticky field keeps the keys whatever is clicked.
    private final boolean sticky;
    private boolean focused;
    private boolean selecting;

    // Where the box and its text last drew. Clicks are measured against these.
    private int boxX;
    private int boxY;
    private int boxWidth;
    private int textX;
    private int room;

    public SearchField(String placeholder, Runnable onChange) {
        this(placeholder, onChange, false);
    }

    private SearchField(String placeholder, Runnable onChange, boolean sticky) {
        this.placeholder = placeholder;
        this.onChange = onChange;
        this.sticky = sticky;
        focused = sticky;
    }

    // For a screen with nothing else to type into.
    public static SearchField sticky(String placeholder, Runnable onChange) {
        return new SearchField(placeholder, onChange, true);
    }

    public String get() {
        return text.get();
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    public void set(String value) {
        String before = text.get();
        text.set(value);
        changedFrom(before);
    }

    public void clear() {
        set("");
    }

    private void changedFrom(String before) {
        if (!text.get().equals(before)) {
            onChange.run();
        }
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = sticky || focused;
        if (!this.focused) {
            selecting = false;
        }
    }

    // A tally such as a match count sits left of the cross when given.
    public void render(GuiGraphicsExtractor context, Font font, int x, int y, int w,
                       int mouseX, int mouseY, String tally) {
        boxX = x;
        boxY = y;
        boxWidth = w;
        boolean active = focused || !isEmpty();
        int border = active ? GuiTheme.accent() : (over(mouseX, mouseY) ? GuiTheme.textFaint() : GuiTheme.edge());
        RenderUtil.roundedBorderedRect(context, x, y, x + w, y + HEIGHT, GuiTheme.CORNER,
            GuiTheme.bgPanel(), border);
        context.guiRenderState.up();

        textX = x + INDENT;
        int textY = GuiTheme.textY(y, HEIGHT);
        int right = x + w - GuiTheme.PAD;
        if (!isEmpty()) {
            context.text(font, GuiTheme.CROSS, right - CLEAR_ZONE + (CLEAR_ZONE - font.width(GuiTheme.CROSS)) / 2,
                textY, GuiTheme.textDim(), false);
            right -= CLEAR_ZONE;
        }
        if (tally != null) {
            right -= font.width(tally);
            context.text(font, tally, right, textY, GuiTheme.textDim(), false);
            right -= GuiTheme.PAD;
        }
        room = right - textX;
        if (isEmpty() && !focused) {
            context.text(font, placeholder, textX, textY, GuiTheme.textFaint(), false);
        } else {
            text.render(context, font, textX, textY, room, GuiTheme.text(), focused);
        }
    }

    public boolean over(double mx, double my) {
        return SettingWidget.isOver(mx, my, boxX, boxY, boxWidth, HEIGHT);
    }

    private boolean overClear(double mx, double my) {
        return !isEmpty() && SettingWidget.isOver(mx, my,
            boxX + boxWidth - GuiTheme.PAD - CLEAR_ZONE, boxY, CLEAR_ZONE, HEIGHT);
    }

    // A click anywhere else lets go of the keys.
    public Click click(double mx, double my) {
        if (!over(mx, my)) {
            setFocused(false);
            return Click.MISSED;
        }
        if (overClear(mx, my)) {
            clear();
            setFocused(false);
            return Click.CLEARED;
        }
        focused = true;
        selecting = true;
        Font font = OfflineClient.MC.font;
        boolean onText = mx < textX + text.drawnWidth(font, room) + TEXT_SLACK;
        text.click(font, textX, room, mx, false);
        return onText ? Click.TEXT : Click.SPACE;
    }

    // Stretches the selection from the press to the pointer.
    public void drag(double mx) {
        if (selecting) {
            text.click(OfflineClient.MC.font, textX, room, mx, true);
        }
    }

    public void release() {
        selecting = false;
    }

    public boolean keyPressed(KeyEvent event) {
        if (!focused) {
            return false;
        }
        String before = text.get();
        boolean used = text.keyPressed(event, TextField.ANY);
        changedFrom(before);
        return used;
    }

    public boolean charTyped(char typed) {
        if (!focused || !text.charTyped(typed, TextField.ANY)) {
            return false;
        }
        onChange.run();
        return true;
    }
}
