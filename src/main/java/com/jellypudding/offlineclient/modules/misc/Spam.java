package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends one chat line on a timer. A message starting with a slash is
 * sent as a command instead.
 */
public final class Spam extends Module {

    private final TextSetting message = new TextSetting("Message",
        "The line to send. Click to type it.", "");
    private final NumberSetting delay = new NumberSetting("Delay",
        "Seconds between messages.", 10, 1, 120, 1, "s").min(1);
    private final BoolSetting randomise = new BoolSetting("Randomise",
        "Varies the delay so it looks less robotic.", false);

    private int timer;

    public Spam() {
        super("Spam", "Sends a chat message on a timer.", Category.MISC);
        addSettings(message, delay, randomise);
        searchTags("auto message", "advert");
    }

    @Override
    protected void onEnable() {
        // Enabling never fires instantly.
        timer = 20;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || message.isBlank() || mc.getConnection() == null) {
            return;
        }
        if (--timer > 0) {
            return;
        }
        timer = next();
        String text = message.getValue().trim();
        if (text.length() > 256) {
            text = text.substring(0, 256);
        }
        if (text.startsWith("/")) {
            mc.getConnection().sendCommand(text.substring(1));
        } else {
            mc.getConnection().sendChat(text);
        }
    }

    private int next() {
        int ticks = delay.getInt() * 20;
        if (randomise.isOn()) {
            ticks = (int) (ticks * ThreadLocalRandom.current().nextDouble(0.5, 1.5));
        }
        return Math.max(ticks, 20);
    }
}
