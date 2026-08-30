package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// EntityRendererMixin flags them and LivingEntityRendererMixin forces the body to render.
public final class TrueSight extends Module {

    private static volatile TrueSight instance;

    private final BoolSetting players = new BoolSetting("Players",
        "Show invisible players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Show invisible mobs.", true);
    private final NumberSetting strength = new NumberSetting("Strength",
        "How solid the invisible entity looks.", 0.4, 0.1, 1, 0.05).min(0.05).max(1);

    public TrueSight() {
        super("TrueSight", "Renders invisible entities.", Category.RENDER);
        addSettings(players, mobs, strength);
        searchTags("invisible", "potion", "reveal");
        instance = this;
    }

    // Null before the client has started.
    public static TrueSight get() {
        return instance;
    }

    public boolean applies(Entity entity) {
        if (!isEnabled() || entity == mc.player || !entity.isInvisible()) {
            return false;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return false;
        }
        if (entity instanceof Player player) {
            return players.isOn() && !player.isSpectator();
        }
        return mobs.isOn();
    }

    public int tint() {
        return ColorUtil.fade(0xFFFFFFFF, strength.getFloat());
    }
}
