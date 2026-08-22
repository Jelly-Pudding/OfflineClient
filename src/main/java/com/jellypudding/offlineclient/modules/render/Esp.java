package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.world.entity.Entity;

public final class Esp extends Module {

    public enum Style {
        BOXES("Boxes"),
        GLOW("Glow"),
        BOTH("Both");

        private final String name;

        Style(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "Boxes draws outlines through walls. Glow uses the vanilla outline effect.", Style.BOXES);
    private final BoolSetting players = new BoolSetting("Players",
        "Highlight other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Highlight mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Highlight dropped items.", false);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", false)
        .visibleWhen(() -> style.getValue() != Style.GLOW);

    public Esp() {
        super("ESP", "See entities through walls.", Category.RENDER);
        addSettings(style, players, mobs, items, fill);
    }

    @Override
    public String getSuffix() {
        return style.getValue().toString();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || style.is(Style.GLOW)) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !matches(entity)) {
                continue;
            }
            int color = EntityUtil.colorOf(entity);
            var box = EntityUtil.lerpedBox(entity, event.getPartialTicks());
            batch.outlineBox(box, color, true);
            if (fill.isOn()) {
                batch.solidBox(box, ColorUtil.withAlpha(color, 40), true);
            }
        }
    }

    /**
     * The glow styles work through mixins. One makes the game outline our
     * targets and another sets the outline color per entity.
     */
    public boolean shouldGlow(Entity entity) {
        return isEnabled() && !style.is(Style.BOXES)
            && entity != mc.player && matches(entity);
    }

    public int glowColor(Entity entity) {
        return EntityUtil.colorOf(entity);
    }

    private boolean matches(Entity entity) {
        return EntityUtil.matches(entity, players.isOn(), mobs.isOn(), items.isOn());
    }
}
