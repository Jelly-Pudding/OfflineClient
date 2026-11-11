package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.util.Flash;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

// A vanilla button that says what its press did in place of its label for a moment. Words too
// wide for it show as its tip instead. A press leaves the keyboard focus where it was because the
// button sits beside a box being typed in.
public final class FlashButton extends Button.Plain {

    // The game keeps this much room either side of a button's label.
    private static final int LABEL_MARGIN = 2;

    private final Component label;
    private final Tooltip tip;
    private long flashUntil;

    public FlashButton(int x, int y, int width, int height, Component label, String tip,
                       Consumer<FlashButton> press) {
        super(x, y, width, height, label, button -> press.accept((FlashButton) button), DEFAULT_NARRATION);
        this.label = label;
        this.tip = Tooltip.create(Component.literal(tip));
        setTooltip(this.tip);
    }

    public void flash(String words) {
        Component said = Component.literal(words);
        if (OfflineClient.MC.font.width(said) <= getWidth() - 2 * LABEL_MARGIN) {
            setMessage(said);
        } else {
            setTooltip(Tooltip.create(said));
        }
        flashUntil = System.currentTimeMillis() + Flash.SHOWN_MS;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (flashUntil != 0 && System.currentTimeMillis() > flashUntil) {
            flashUntil = 0;
            setMessage(label);
            setTooltip(tip);
        }
        super.extractContents(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldTakeFocusAfterInteraction() {
        return false;
    }
}
