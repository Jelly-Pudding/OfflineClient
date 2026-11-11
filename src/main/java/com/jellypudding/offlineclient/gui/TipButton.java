package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

// A vanilla button that says what a press would do. The words are asked for afresh on every frame
// that shows them because what a press does can change whilst the screen is up. An empty answer
// shows no tooltip.
public final class TipButton extends Button.Plain {

    private final Supplier<String> tip;
    private String shown = "";

    public TipButton(int x, int y, int width, int height, Component label, Runnable press, Supplier<String> tip) {
        super(x, y, width, height, label, button -> press.run(), DEFAULT_NARRATION);
        this.tip = tip;
    }

    @Override
    protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (showsTip()) {
            String words = tip.get();
            if (!words.equals(shown)) {
                shown = words;
                setTooltip(words.isEmpty() ? null : Tooltip.create(Component.literal(words)));
            }
        }
        super.extractTooltipForNextRenderPass(graphics, mouseX, mouseY);
    }

    // The game shows a tooltip under the pointer and on a button the keyboard moved to. A click
    // leaves the button focused with no tooltip.
    private boolean showsTip() {
        return isHovered() || (isFocused() && OfflineClient.MC.getLastInputType().isKeyboard());
    }
}
