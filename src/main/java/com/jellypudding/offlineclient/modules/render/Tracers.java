package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class Tracers extends Module {

    private final BoolSetting players = new BoolSetting("Players",
        "Draw lines to players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Draw lines to mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Draw lines to dropped items.", false);
    private final BoolSetting names = new BoolSetting("Names",
        "Show the player's name where their tracer ends.", true)
        .visibleWhen(players::isOn);

    public Tracers() {
        super("Tracers", "Draws lines from you to entities around you.", Category.RENDER);
        addSettings(players, mobs, items, names);
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

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!names.isOn() || !players.isOn() || !inGame() || !WorldToScreen.update()) {
            return;
        }
        Font font = mc.font;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || entity == mc.player
                || !EntityUtil.matches(entity, true, false, false)) {
                continue;
            }
            Vec3 top = EntityUtil.lerpedBox(entity, event.getPartialTicks()).getCenter();
            Vec3 screen = WorldToScreen.project(top);
            if (screen == null) {
                continue;
            }
            String name = player.getGameProfile().name();
            GuiGraphicsExtractor context = event.getContext();
            context.guiRenderState.up();
            context.text(font, name, (int) screen.x - font.width(name) / 2,
                (int) screen.y - 4, EntityUtil.colorOf(entity), true);
        }
    }
}
