package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.WorldToScreen;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class Tracers extends Module {

    public enum Style { LINES, OFFSCREEN }

    public enum Target { HEAD, BODY, FEET }

    private final EnumSetting<Style> style = new EnumSetting<>("Style", "How entities are pointed out.", Style.LINES)
        .describe(Style.LINES, "A line from you to each entity.")
        .describe(Style.OFFSCREEN, "An arrow on a ring round the screen centre for each entity out of view.");
    private final EnumSetting<Target> target = new EnumSetting<>("Target",
        "Where on the entity the line ends.", Target.BODY)
        .describe(Target.HEAD, "The top of the entity.")
        .describe(Target.BODY, "The middle of the entity.")
        .describe(Target.FEET, "The bottom of the entity.")
        .under(style, Style.LINES);
    private final BoolSetting stem = new BoolSetting("Stem",
        "Also draw a line from the entity's feet to its head.", true).under(style, Style.LINES);
    private final BoolSetting names = new BoolSetting("Names",
        "Show the player's name where their tracer ends.", true).under(style, Style.LINES);
    private final NumberSetting arrowDistance = new NumberSetting("Arrow distance",
        "Radius in pixels of the ring the arrows sit on.", 200, 0, 500, 10, " px").under(style, Style.OFFSCREEN);
    private final NumberSetting arrowSize = new NumberSetting("Arrow size",
        "Size of each arrow in pixels.", 10, 2, 50, 1, " px").under(style, Style.OFFSCREEN);
    private final BoolSetting blink = new BoolSetting("Blink",
        "Arrows pulse in and out.", true).under(style, Style.OFFSCREEN);
    private final NumberSetting blinkSpeed = new NumberSetting("Blink speed",
        "How fast the arrows pulse.", 4, 1, 15, 0.5)
        .under(blink, () -> style.is(Style.OFFSCREEN) && blink.isOn());
    private final EntityFilter filter = new EntityFilter("Draw lines to", "given a line", true,
        EntityFilter.Pick.NONE, EntityFilter.Pick.NONE, List.of());
    private final NumberSetting maxDistance = new NumberSetting("Max distance",
        "Entities further away than this get nothing.", 256, 0, 256, 8, " blocks").min(0);
    private final BoolSetting selfInFreecam = new BoolSetting("Self in Freecam",
        "Point to your own body whilst Freecam is on.", true);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Friends get nothing.", false);
    private final BoolSetting showInvisible = new BoolSetting("Show invisible",
        "Invisible entities get a line too.", true);
    private final EntityColors colors = new EntityColors(EntityColors.Mode.DISTANCE);

    public Tracers() {
        super("Tracers", "Draws lines from you to entities around you.", Category.RENDER);
        addSettings(style, target, stem, names, arrowDistance, arrowSize, blink, blinkSpeed);
        addSettings(filter.settings());
        addSettings(maxDistance, selfInFreecam, ignoreFriends, showInvisible);
        addSettings(colors.settings());
    }

    private boolean wanted(Entity entity) {
        if (entity == mc.player) {
            if (!selfInFreecam.isOn() || !filter.wantsPlayers() || !Modules.enabled(Freecam.class)) {
                return false;
            }
        } else if (!filter.matches(entity)) {
            return false;
        }
        if (ignoreFriends.isOn() && EntityUtil.isFriend(entity)) {
            return false;
        }
        if (!showInvisible.isOn() && entity.isInvisible()) {
            return false;
        }
        return mc.player.distanceTo(entity) <= maxDistance.getValue();
    }

    private Vec3 endOf(AABB box) {
        double x = (box.minX + box.maxX) / 2;
        double z = (box.minZ + box.maxZ) / 2;
        return switch (target.getValue()) {
            case HEAD -> new Vec3(x, box.maxY, z);
            case BODY -> new Vec3(x, (box.minY + box.maxY) / 2, z);
            case FEET -> new Vec3(x, box.minY, z);
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame() || !style.is(Style.LINES)) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!wanted(entity)) {
                continue;
            }
            AABB box = EntityUtil.lerpedBox(entity, event.getPartialTicks());
            int color = colors.colorOf(entity);
            batch.tracer(endOf(box), color, true);
            if (stem.isOn()) {
                double x = (box.minX + box.maxX) / 2;
                double z = (box.minZ + box.maxZ) / 2;
                batch.line(new Vec3(x, box.minY, z), new Vec3(x, box.maxY, z), color, true);
            }
        }
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame() || !WorldToScreen.update()) {
            return;
        }
        if (style.is(Style.OFFSCREEN)) {
            drawArrows(event);
        } else if (names.isOn() && filter.wantsPlayers()) {
            drawNames(event);
        }
    }

    private void drawNames(Render2DEvent event) {
        Font font = mc.font;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Player player) || !wanted(entity)) {
                continue;
            }
            Vec3 screen = WorldToScreen.project(endOf(EntityUtil.lerpedBox(entity, event.getPartialTicks())));
            if (screen == null) {
                continue;
            }
            String name = EntityUtil.displayNameOf(player);
            GuiGraphicsExtractor context = event.getContext();
            context.guiRenderState.up();
            context.text(font, name, (int) screen.x - font.width(name) / 2,
                (int) screen.y - 4, colors.colorOf(entity), true);
        }
    }

    // A pulse from a quarter to full strength.
    private float blinkStrength() {
        if (!blink.isOn()) {
            return 1;
        }
        double wave = Math.sin(System.currentTimeMillis() / 1000.0 * blinkSpeed.getValue());
        return (float) (0.625 + 0.375 * wave);
    }

    private void drawArrows(Render2DEvent event) {
        GuiGraphicsExtractor context = event.getContext();
        double centreX = mc.getWindow().getGuiScaledWidth() / 2.0;
        double centreY = mc.getWindow().getGuiScaledHeight() / 2.0;
        float strength = blinkStrength();
        context.guiRenderState.up();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!wanted(entity)) {
                continue;
            }
            Vec3 middle = EntityUtil.lerpedBox(entity, event.getPartialTicks()).getCenter();
            if (WorldToScreen.onScreen(middle)) {
                continue;
            }
            Vec3 direction = WorldToScreen.directionTo(middle);
            drawArrow(context, centreX + direction.x * arrowDistance.getValue(),
                centreY + direction.y * arrowDistance.getValue(), direction,
                ColorUtil.fade(colors.colorOf(entity), strength));
        }
    }

    // A triangle pointing along the direction with its tip furthest from the centre.
    private void drawArrow(GuiGraphicsExtractor context, double x, double y, Vec3 direction, int color) {
        double size = arrowSize.getValue();
        double dx = direction.x;
        double dy = direction.y;
        // The tip leads and the two tail corners sit back and to either side.
        double tipX = x + dx * size;
        double tipY = y + dy * size;
        double backX = x - dx * size;
        double backY = y - dy * size;
        double sideX = -dy * size;
        double sideY = dx * size;
        RenderUtil.triangle(context, tipX, tipY, backX + sideX, backY + sideY,
            backX - sideX, backY - sideY, color);
    }
}
