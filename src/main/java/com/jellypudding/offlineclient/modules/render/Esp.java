package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class Esp extends Module {

    public enum Style { BOXES, GLOW, BOTH }

    public enum Coloring { DISTANCE, HEALTH }

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float HEALTH_HUE = 120;

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "How the entities are marked.", Style.BOXES)
        .describe(Style.BOXES, "Draws an outline box round each one.")
        .describe(Style.GLOW, "Uses the vanilla glow effect.")
        .describe(Style.BOTH, "Boxes and the glow together.");
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Also draws a line from you to each one.", false);
    private final BoolSetting players = new BoolSetting("Players",
        "Highlight other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Highlight mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Highlight dropped items.", false);
    private final RegistryListSetting<EntityType<?>> entities =
        new RegistryListSetting<>("Entities",
            "Extra entity types to highlight.",
            BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.END_CRYSTAL));
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an entity can be and still show.", 128, 16, 256, 8, " blocks").min(1);
    private final EnumSetting<Coloring> coloring = new EnumSetting<>("Colour mode",
        "How the colour is chosen.", Coloring.DISTANCE)
        .describe(Coloring.DISTANCE, "Red up close fading to green far away.")
        .describe(Coloring.HEALTH, "Green at full health down to red near death.");
    private final BoolSetting friendColor = new BoolSetting("Friend colour",
        "Paint friends blue instead.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", false)
        .under(style, Style.BOXES, Style.BOTH);

    public Esp() {
        super("ESP", "See entities through walls.", Category.RENDER);
        addSettings(style, fill, tracers, players, mobs, items, entities, range, coloring, friendColor);
        searchTags("crystal esp", "wallhack", "boxes");
    }

    @Override
    public String getSuffix() {
        return style.getValueString();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        boolean boxes = !style.is(Style.GLOW);
        if (!inGame() || (!boxes && !tracers.isOn())) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!matches(entity)) {
                continue;
            }
            int color = colorOf(entity);
            AABB box = EntityUtil.lerpedBox(entity, event.getPartialTicks());
            // Hitboxes expands the reach of a hit.
            double grow = hitboxes == null ? 0 : hitboxes.expansionFor(entity);
            if (grow > 0) {
                box = box.inflate(grow);
            }
            if (boxes) {
                batch.outlineBox(box, color, true);
                if (fill.isOn()) {
                    batch.solidBox(box, ColorUtil.withAlpha(color, 40), true);
                }
            }
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), color, true);
            }
        }
    }

    // The glow styles work through mixins that pick the targets and set the outline colour.
    public boolean shouldGlow(Entity entity) {
        return isEnabled() && !style.is(Style.BOXES) && matches(entity);
    }

    public int glowColor(Entity entity) {
        return colorOf(entity);
    }

    private boolean matches(Entity entity) {
        if (mc.player == null || entity == mc.player) {
            return false;
        }
        if (mc.player.distanceTo(entity) > range.getValue()) {
            return false;
        }
        if (entity instanceof Player player) {
            return players.isOn() && player.isAlive() && !player.isSpectator();
        }
        if (entity instanceof ItemEntity) {
            return items.isOn() || entities.contains(entity.getType());
        }
        if (entity instanceof LivingEntity living) {
            return living.isAlive() && (mobs.isOn() || entities.contains(entity.getType()));
        }
        return entities.contains(entity.getType());
    }

    private int colorOf(Entity entity) {
        if (friendColor.isOn() && EntityUtil.isFriend(entity)) {
            return EntityUtil.FRIEND_COLOR;
        }
        if (coloring.is(Coloring.HEALTH) && entity instanceof LivingEntity living) {
            return healthColor(living);
        }
        if (entity instanceof Player) {
            return EntityUtil.distanceColor(entity);
        }
        return EntityUtil.colorOf(entity);
    }

    private int healthColor(LivingEntity living) {
        float max = living.getMaxHealth();
        if (max <= 0) {
            return EntityUtil.colorOf(living);
        }
        float left = Math.clamp(EntityUtil.totalHealth(living) / max, 0f, 1f);
        return ColorUtil.hsv(left * HEALTH_HUE, 0.85f, 1f);
    }
}
