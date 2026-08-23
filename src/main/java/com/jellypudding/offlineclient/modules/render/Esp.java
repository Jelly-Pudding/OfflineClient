package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.OfflineClient;
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

import java.util.List;

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

    public enum Coloring {
        DISTANCE("Distance"),
        HEALTH("Health");

        private final String name;

        Coloring(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // The same blue the other modules give friends.
    private static final int FRIEND_COLOR = 0xFF4080FF;

    // Distance in blocks at which a player has faded all the way to green.
    private static final float DISTANCE_FADE = 20;

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float HEALTH_HUE = 120;

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "Outline boxes or the vanilla glow effect.", Style.BOXES);
    private final BoolSetting players = new BoolSetting("Players",
        "Highlight other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Highlight mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Highlight dropped items.", false);
    private final RegistryListSetting<EntityType<?>> entities =
        new RegistryListSetting<EntityType<?>>("Entities",
            "Extra entity types to highlight. Click to pick them.",
            BuiltInRegistries.ENTITY_TYPE, List.of(EntityTypes.END_CRYSTAL));
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an entity can be and still show.", 128, 16, 256, 8, " blocks").min(1);
    private final EnumSetting<Coloring> coloring = new EnumSetting<>("Color mode",
        "Distance fades players from red to green. Health tints anything living by how hurt it is.",
        Coloring.DISTANCE);
    private final BoolSetting friendColor = new BoolSetting("Friend color",
        "Paint friends blue instead.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", false)
        .visibleWhen(() -> style.getValue() != Style.GLOW);

    public Esp() {
        super("ESP", "See entities through walls.", Category.RENDER);
        addSettings(style, players, mobs, items, entities, range, coloring, friendColor, fill);
        searchTags("crystal esp", "wallhack", "boxes");
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
        // The loop below runs for every entity every frame.
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!matches(entity)) {
                continue;
            }
            int color = colorOf(entity);
            var box = EntityUtil.lerpedBox(entity, event.getPartialTicks());
            // Hitboxes expands the reach of a hit.
            double grow = hitboxes == null ? 0 : hitboxes.expansionFor(entity);
            if (grow > 0) {
                box = box.inflate(grow);
            }
            batch.outlineBox(box, color, true);
            if (fill.isOn()) {
                batch.solidBox(box, ColorUtil.withAlpha(color, 40), true);
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
        if (friendColor.isOn() && isFriend(entity)) {
            return FRIEND_COLOR;
        }
        if (coloring.is(Coloring.HEALTH) && entity instanceof LivingEntity living) {
            return healthColor(living);
        }
        if (entity instanceof Player player) {
            return distanceColor(player);
        }
        return EntityUtil.colorOf(entity);
    }

    private boolean isFriend(Entity entity) {
        return entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name());
    }

    private int healthColor(LivingEntity living) {
        float max = living.getMaxHealth();
        if (max <= 0) {
            return EntityUtil.colorOf(living);
        }
        float left = Math.clamp((living.getHealth() + living.getAbsorptionAmount()) / max, 0f, 1f);
        return ColorUtil.hsv(left * HEALTH_HUE, 0.85f, 1f);
    }

    private int distanceColor(Entity entity) {
        float f = mc.player.distanceTo(entity) / DISTANCE_FADE;
        int r = (int) (Math.clamp(2 - f, 0, 1) * 255);
        int g = (int) (Math.clamp(f, 0, 1) * 255);
        return 0xFF000000 | r << 16 | g << 8;
    }
}
