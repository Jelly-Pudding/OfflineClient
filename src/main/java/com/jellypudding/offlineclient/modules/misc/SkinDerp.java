package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.concurrent.ThreadLocalRandom;

// Flicks the skin layers on and off. The server relays the change to everyone.
public final class SkinDerp extends Module {

    private final NumberSetting chance = new NumberSetting("Chance",
        "How likely each tick is to flip the layers.", 25, 1, 100, 1, "%").min(1).max(100);

    public SkinDerp() {
        super("SkinDerp", "Makes your skin layers blink on and off for everyone to see.",
            Category.MISC);
        addSettings(chance);
        searchTags("skin blink", "spooky skin", "troll");
    }

    @Override
    protected void onDisable() {
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mc.options.setModelPart(part, true);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
            return;
        }
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mc.options.setModelPart(part, !mc.options.isModelPartEnabled(part));
        }
    }
}
