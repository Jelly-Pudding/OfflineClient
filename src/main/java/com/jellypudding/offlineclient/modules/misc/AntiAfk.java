package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.ThreadLocalRandom;

public final class AntiAfk extends Module {

    private final NumberSetting interval = new NumberSetting("Interval",
        "Seconds between actions.", 30, 5, 120, 5, "s");
    private final BoolSetting jump = new BoolSetting("Jump",
        "Jump in place.", true);
    private final BoolSetting look = new BoolSetting("Look",
        "Turn your head a little.", true);
    private final BoolSetting swing = new BoolSetting("Swing",
        "Swing your arm.", false);

    private int timer;

    public AntiAfk() {
        super("AntiAFK", "Keeps you from being kicked for idling.", Category.MISC);
        addSettings(interval, jump, look, swing);
    }

    @Override
    protected void onEnable() {
        timer = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        timer++;
        if (timer < interval.getInt() * 20) {
            return;
        }
        timer = 0;

        if (jump.isOn() && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
        if (look.isOn()) {
            // The vanilla turn keeps the last frame in step. A raw set snaps the head.
            float delta = ThreadLocalRandom.current().nextFloat(-15f, 15f);
            mc.player.turn(delta / 0.15, 0);
        }
        if (swing.isOn()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
