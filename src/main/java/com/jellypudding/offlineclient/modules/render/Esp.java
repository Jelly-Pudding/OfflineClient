package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.Hitboxes;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WireframeRenderer;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class Esp extends Module {

    public enum Style { BOXES, WIREFRAME, FLAT, GLOW }

    public enum Size { ACCURATE, FANCY }

    public enum Neutral { ALWAYS, WHEN_ANGRY, NEVER }

    // How much a fancy box grows on every side and how far it lifts.
    private static final double FANCY_GROW = 0.05;

    private final EnumSetting<Style> style = new EnumSetting<>("Style",
        "How the entities are marked.", Style.BOXES)
        .describe(Style.BOXES, "A box round each one.")
        .describe(Style.WIREFRAME, "The shape of the model itself drawn as boxes.")
        .describe(Style.FLAT, "A flat rectangle on the screen round each one.")
        .describe(Style.GLOW, "The vanilla glow effect.");
    private final BoxStyle shape = BoxStyle.shapeOnly(BoxStyle.Shape.LINES);
    private final EnumSetting<Size> size = new EnumSetting<>("Box size",
        "How big the box around an entity is.", Size.FANCY)
        .describe(Size.ACCURATE, "The hitbox itself.")
        .describe(Size.FANCY, "A little larger and lifted so it sits clear of the model.")
        .under(style, Style.BOXES, Style.WIREFRAME, Style.FLAT);
    private final BoolSetting alsoGlow = new BoolSetting("Also glow",
        "Add the vanilla glow on top of the boxes.", false)
        .under(style, Style.BOXES, Style.WIREFRAME, Style.FLAT);
    private final BoolSetting tracers = new BoolSetting("Tracers",
        "Also draws a line from you to each one.", false);
    private final EntityFilter filter = new EntityFilter("Highlight", "highlighted", true,
        EntityFilter.Pick.NONE, EntityFilter.Pick.NONE, List.of(EntityTypes.END_CRYSTAL));
    private final BoolSetting ignoreSleeping = new BoolSetting("Ignore sleeping",
        "Players lying in a bed get nothing.", false);
    private final BoolSetting ignoreInvisible = new BoolSetting("Ignore invisible",
        "Invisible entities get nothing.", false);
    private final BoolSetting ignorePets = new BoolSetting("Ignore pets",
        "Tamed and saddled and trusting animals get nothing.", false);
    private final BoolSetting armourStands = new BoolSetting("Armour stands",
        "Also mark armour stands.", true);
    private final EnumSetting<Neutral> neutral = new EnumSetting<>("Neutral mobs",
        "Endermen and piglins and wolves and the like.", Neutral.ALWAYS)
        .describe(Neutral.ALWAYS, "Mark them like any other mob.")
        .describe(Neutral.WHEN_ANGRY, "Only mark them once they are angry.")
        .describe(Neutral.NEVER, "Leave them out.");
    private final BoolSetting self = new BoolSetting("Self",
        "Also mark your own body in third person.", false);
    private final NumberSetting range = new NumberSetting("Range",
        "Furthest an entity can be and still show.", 128, 16, 256, 8, " blocks").min(1);
    private final NumberSetting nearFade = new NumberSetting("Near fade",
        "Entities closer than this to the camera fade out so a box does not fill your screen.",
        3, 0, 12, 0.5, " blocks").min(0).max(32);
    private final BoolSetting highlightTarget = new BoolSetting("Highlight target",
        "The entity under your crosshair gets its own colour even if its kind is not picked.", false);
    private final ColorSetting targetColor = new ColorSetting("Target colour",
        "Colour of the entity under your crosshair.", 0, 0f, 0.8f, false)
        .under(highlightTarget);
    private final BoolSetting targetHitbox = new BoolSetting("Target hitbox",
        "Draw the hitbox of the entity under your crosshair as lines.", true)
        .under(highlightTarget);
    private final ColorSetting hitboxColor = new ColorSetting("Hitbox colour",
        "Colour of that hitbox.", 180, 0.5f, 0.8f, false)
        .under(targetHitbox);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.TYPE);

    public Esp() {
        super("ESP", "See entities through walls.", Category.RENDER);
        addSettings(style);
        addSettings(shape.settings());
        addSettings(size, alsoGlow, tracers);
        addSettings(filter.settings());
        addSettings(ignoreSleeping, ignoreInvisible, ignorePets, armourStands, neutral, self,
            range, nearFade, highlightTarget, targetColor, targetHitbox, hitboxColor);
        addSettings(colors.settings());
        searchTags("crystal esp", "wallhack", "boxes", "wireframe");
    }

    @Override
    public String getSuffix() {
        return style.getValueString();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        boolean shapes = style.isAny(Style.BOXES, Style.WIREFRAME);
        if (!inGame() || (!shapes && !tracers.isOn() && !highlightTarget.isOn())) {
            return;
        }
        DrawBatch batch = event.getBatch();
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        float partial = event.getPartialTicks();
        for (Entity entity : mc.level.entitiesForRendering()) {
            boolean target = isTarget(entity);
            if (!target && !matches(entity)) {
                continue;
            }
            int line = fadedColor(entity, target);
            int fill = ColorUtil.fade(line, shape.fillShare());
            AABB box = grownBox(entity, partial, hitboxes);
            if (style.is(Style.WIREFRAME)) {
                WireframeRenderer.draw(batch, entity, partial, shape, line, fill, true);
            } else if (style.is(Style.BOXES)) {
                shape.draw(batch, box, line, fill, true);
            }
            if (target && targetHitbox.isOn()) {
                batch.outlineBox(EntityUtil.lerpedBox(entity, partial), hitboxColor.getColor(), true);
            }
            if (tracers.isOn()) {
                batch.tracer(box.getCenter(), line, true);
            }
        }
    }

    // A rectangle round the projected corners of each box.
    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!style.is(Style.FLAT) || !inGame() || !WorldToScreen.update()) {
            return;
        }
        Hitboxes hitboxes = Modules.get(Hitboxes.class);
        float partial = event.getPartialTicks();
        for (Entity entity : mc.level.entitiesForRendering()) {
            boolean target = isTarget(entity);
            if (!target && !matches(entity)) {
                continue;
            }
            double[] bounds = screenBounds(grownBox(entity, partial, hitboxes));
            if (bounds == null) {
                continue;
            }
            int line = fadedColor(entity, target);
            drawFlat(event.getContext(), bounds, line, ColorUtil.fade(line, shape.fillShare()));
        }
    }

    private void drawFlat(GuiGraphicsExtractor context, double[] bounds, int line, int fill) {
        int x1 = (int) Math.floor(bounds[0]);
        int y1 = (int) Math.floor(bounds[1]);
        int x2 = (int) Math.ceil(bounds[2]);
        int y2 = (int) Math.ceil(bounds[3]);
        if (shape.drawsSides()) {
            context.fill(x1, y1, x2, y2, fill);
        }
        if (shape.drawsLines()) {
            context.outline(x1, y1, x2 - x1, y2 - y1, line);
        }
    }

    // The screen rectangle that holds all eight corners or null when any sit behind the camera.
    private static double[] screenBounds(AABB box) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY, (i & 4) == 0 ? box.minZ : box.maxZ);
            Vec3 screen = WorldToScreen.project(corner);
            if (screen == null) {
                return null;
            }
            minX = Math.min(minX, screen.x);
            minY = Math.min(minY, screen.y);
            maxX = Math.max(maxX, screen.x);
            maxY = Math.max(maxY, screen.y);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    // Hitboxes expands the reach of a hit and a fancy box sits a little outside the model.
    private AABB grownBox(Entity entity, float partial, Hitboxes hitboxes) {
        AABB box = EntityUtil.lerpedBox(entity, partial);
        double grow = hitboxes == null ? 0 : hitboxes.expansionFor(entity);
        if (grow > 0) {
            box = box.inflate(grow);
        }
        return size.is(Size.ACCURATE) ? box : box.inflate(FANCY_GROW).move(0, FANCY_GROW, 0);
    }

    // The colour for the entity faded down as it comes close to the camera.
    private int fadedColor(Entity entity, boolean target) {
        int color = target ? targetColor.getColor() : colors.colorOf(entity);
        double fade = nearFade.getValue();
        if (fade <= 0) {
            return color;
        }
        double away = Math.sqrt(entity.distanceToSqr(DrawBatch.cameraPos()));
        return ColorUtil.fade(color, (float) Math.clamp(away / fade, 0, 1));
    }

    private boolean isTarget(Entity entity) {
        return highlightTarget.isOn() && entity == mc.crosshairPickEntity && entity != mc.player;
    }

    // The glow styles work through mixins that pick the targets and set the outline colour.
    public boolean shouldGlow(Entity entity) {
        return isEnabled() && (style.is(Style.GLOW) || alsoGlow.isOn()) && (matches(entity) || isTarget(entity));
    }

    public int glowColor(Entity entity) {
        return isTarget(entity) ? targetColor.getColor() : colors.colorOf(entity);
    }

    private boolean wantedMob(Mob mob) {
        if (ignorePets.isOn() && EntityUtil.isPet(mob)) {
            return false;
        }
        if (!EntityUtil.isNeutral(mob)) {
            return true;
        }
        return switch (neutral.getValue()) {
            case ALWAYS -> true;
            case WHEN_ANGRY -> !EntityUtil.isCalm(mob);
            case NEVER -> false;
        };
    }

    private boolean matches(Entity entity) {
        if (mc.player == null || mc.player.distanceTo(entity) > range.getValue()) {
            return false;
        }
        if (entity == mc.player) {
            return self.isOn() && (Modules.enabled(Freecam.class) || !mc.options.getCameraType().isFirstPerson());
        }
        if (ignoreInvisible.isOn() && entity.isInvisible()) {
            return false;
        }
        if (ignoreSleeping.isOn() && entity instanceof Player player && player.isSleeping()) {
            return false;
        }
        if (!armourStands.isOn() && entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof Mob mob && !wantedMob(mob)) {
            return false;
        }
        return filter.matches(entity);
    }
}
