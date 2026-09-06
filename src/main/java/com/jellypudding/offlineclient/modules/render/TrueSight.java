package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

// EntityRendererMixin flags them and LivingEntityRendererMixin forces the body to render.
public final class TrueSight extends Module {

    private static volatile TrueSight instance;

    private final EntityFilter filter = EntityFilter.living("Reveal", "revealed", true,
        EntityFilter.Pick.ALL, List.of());
    private final NumberSetting strength = new NumberSetting("Strength",
        "How solid the invisible entity looks.", 0.4, 0.1, 1, 0.05).min(0.05).max(1);

    public TrueSight() {
        super("TrueSight", "Renders invisible entities.", Category.RENDER);
        addSettings(filter.settings());
        addSettings(strength);
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
        return filter.matches(entity);
    }

    public int tint() {
        return ColorUtil.fade(0xFFFFFFFF, strength.getFloat());
    }
}
