package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.world.entity.player.PlayerModelPart;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

// Flicks the skin layers on and off. Vanilla only tells the server about the
// layers when the options are saved. The change has to be sent by hand.
public final class SkinDerp extends Module {

    private final NumberSetting chance = new NumberSetting("Chance",
        "How likely each tick is to flip the layers.", 25, 1, 100, 1, "%").min(1).max(100);

    // The layers you had on before the blinking started. Options save them to disk.
    private final Set<PlayerModelPart> shown = EnumSet.noneOf(PlayerModelPart.class);

    public SkinDerp() {
        super("SkinDerp", "Makes your skin layers blink on and off for everyone to see.",
            Category.MISC);
        addSettings(chance);
        searchTags("skin blink", "spooky skin", "troll");
    }

    @Override
    protected void onEnable() {
        shown.clear();
        for (PlayerModelPart part : PlayerModelPart.values()) {
            if (mc.options.isModelPartEnabled(part)) {
                shown.add(part);
            }
        }
    }

    @Override
    protected void onDisable() {
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mc.options.setModelPart(part, shown.contains(part));
        }
        mc.options.broadcastOptions();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || ThreadLocalRandom.current().nextInt(100) >= chance.getInt()) {
            return;
        }
        for (PlayerModelPart part : PlayerModelPart.values()) {
            mc.options.setModelPart(part, !mc.options.isModelPartEnabled(part));
        }
        mc.options.broadcastOptions();
    }
}
