package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;

// Fills every placed sign with the same four lines and closes the screen.
public final class AutoSign extends Module {

    private static final int LINES = 4;

    private final TextSetting line1 = new TextSetting("Line 1", "Top line of the sign.", "");
    private final TextSetting line2 = new TextSetting("Line 2", "Second line of the sign.", "");
    private final TextSetting line3 = new TextSetting("Line 3", "Third line of the sign.", "");
    private final TextSetting line4 = new TextSetting("Line 4", "Bottom line of the sign.", "");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before the sign is sent. Servers dislike an instant reply.",
        10, 0, 60, 1, " ticks").min(0);

    private AbstractSignEditScreen filled;
    private int timer;
    private int written;

    public AutoSign() {
        super("AutoSign", "Writes your set text onto every sign you place.", Category.WORLD);
        addSettings(line1, line2, line3, line4, delay);
        searchTags("sign", "text");
    }

    @Override
    public String getSuffix() {
        return written == 0 ? null : written + " signed";
    }

    @Override
    protected void onEnable() {
        filled = null;
        timer = 0;
        written = 0;
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!(mc.gui.screen() instanceof AbstractSignEditScreen screen)) {
            filled = null;
            return;
        }
        if (screen != filled) {
            filled = screen;
            timer = delay.getInt();
            String[] lines = {line1.getValue(), line2.getValue(),
                line3.getValue(), line4.getValue()};
            System.arraycopy(lines, 0, screen.messages, 0,
                Math.min(LINES, screen.messages.length));
        }
        if (timer > 0) {
            timer--;
            return;
        }
        // Closing the screen is what sends the finished sign.
        mc.gui.setScreen(null);
        filled = null;
        written++;
    }
}
