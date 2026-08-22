package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

public final class Tracers extends Module {

    private final BoolSetting players = new BoolSetting("Players",
        "Draw lines to players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Draw lines to mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Draw lines to dropped items.", false);

    public Tracers() {
        super("Tracers", "Draws lines from you to entities around you.", Category.RENDER);
        addSettings(players, mobs, items);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !EntityUtil.matches(entity, players.isOn(), mobs.isOn(), items.isOn())) {
                continue;
            }
            batch.tracer(EntityUtil.lerpedBox(entity, event.getPartialTicks()).getCenter(),
                EntityUtil.colorOf(entity), true);
        }
    }
}
