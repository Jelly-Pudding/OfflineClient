package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;

// Fills every placed sign with the same four lines and closes the screen.
public final class AutoSign extends Module {

    private static final int LINES = 4;

    public enum Source { TYPED, FIRST_SIGN }

    private final EnumSetting<Source> source = new EnumSetting<>("Source",
        "Where the text comes from.", Source.TYPED)
        .describe(Source.TYPED, "The four lines set below.")
        .describe(Source.FIRST_SIGN, "Copies whatever you write on the next sign by hand.");
    private final TextSetting line1 = new TextSetting("Line 1", "Top line of the sign.", "")
        .under(source, Source.TYPED);
    private final TextSetting line2 = new TextSetting("Line 2", "Second line of the sign.", "")
        .under(source, Source.TYPED);
    private final TextSetting line3 = new TextSetting("Line 3", "Third line of the sign.", "")
        .under(source, Source.TYPED);
    private final TextSetting line4 = new TextSetting("Line 4", "Bottom line of the sign.", "")
        .under(source, Source.TYPED);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait before the sign is sent. Servers dislike an instant reply.",
        10, 0, 60, 1, " ticks").min(0);

    private AbstractSignEditScreen filled;
    private String[] learned;
    private int timer;
    private int written;

    public AutoSign() {
        super("AutoSign", "Writes your set text onto every sign you place.", Category.WORLD);
        addSettings(source, line1, line2, line3, line4, delay);
        searchTags("sign", "text");
    }

    @Override
    public String getSuffix() {
        if (source.is(Source.FIRST_SIGN) && learned == null) {
            return "waiting for a sign";
        }
        return written == 0 ? null : written + " signed";
    }

    @Override
    protected void onEnable() {
        filled = null;
        learned = null;
        timer = 0;
        written = 0;
    }

    // The lines you send by hand become the template for every sign after it.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundSignUpdatePacket packet)) {
            return;
        }
        boolean first = learned == null;
        learned = packet.getLines().clone();
        if (first && source.is(Source.FIRST_SIGN)) {
            ChatUtil.message("§bAutoSign §7copied that sign.");
        }
    }

    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (!(mc.gui.screen() instanceof AbstractSignEditScreen screen)) {
            filled = null;
            return;
        }
        String[] lines = lines();
        // Nothing learned yet so the screen is left for you to type into.
        if (lines == null) {
            return;
        }
        if (screen != filled) {
            filled = screen;
            timer = delay.getInt();
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

    private String[] lines() {
        if (source.is(Source.FIRST_SIGN)) {
            return learned;
        }
        return new String[]{line1.getValue(), line2.getValue(), line3.getValue(), line4.getValue()};
    }
}
