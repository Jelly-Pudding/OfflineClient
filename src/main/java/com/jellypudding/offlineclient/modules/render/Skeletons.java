package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.StickFigure;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityFilter;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class Skeletons extends Module {

    private final EntityFilter filter = EntityFilter.living("Draw", "drawn", true,
        EntityFilter.Pick.NONE, List.of());
    private final BoolSetting self = new BoolSetting("Self",
        "Also draw your own skeleton in third person.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an entity can be and still show.", 128, 16, 256, 8, " blocks").min(1);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.TYPE);

    public Skeletons() {
        super("Skeletons", "Draws players and mobs built like them as stick figures through walls.",
            Category.RENDER);
        addSettings(filter.settings());
        addSettings(self, range);
        addSettings(colors.settings());
        searchTags("skeleton esp", "stick figure", "bones", "pose");
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!wanted(entity)) {
                continue;
            }
            StickFigure figure = StickFigure.of(entity);
            if (figure != null) {
                figure.draw(batch, colors.colorOf(entity));
            }
        }
    }

    private boolean wanted(Entity entity) {
        if (mc.player.distanceTo(entity) > range.getValue()) {
            return false;
        }
        if (entity == mc.player) {
            return self.isOn() && Freecam.ownBodyVisible();
        }
        return filter.matches(entity);
    }
}
