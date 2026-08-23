package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.RenderUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class Radar extends Module {

    private static final int BACKGROUND = 0x80101018;
    private static final int BORDER = 0xFF3A3A4A;
    private static final int GRID = 0x40FFFFFF;
    private static final int SELF = 0xFFFFFFFF;

    public enum Corner {
        TOP_LEFT("Top left"),
        TOP_RIGHT("Top right"),
        BOTTOM_LEFT("Bottom left"),
        BOTTOM_RIGHT("Bottom right");

        private final String label;

        Corner(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final NumberSetting size = new NumberSetting("Size",
        "Width of the radar in pixels.", 100, 60, 220, 10, " px").min(40).max(400);
    private final NumberSetting range = new NumberSetting("Range",
        "How far the edge of the radar reaches.", 64, 16, 256, 8, " blocks").min(4);
    private final EnumSetting<Corner> corner = new EnumSetting<>("Corner",
        "Which corner the radar sits in.", Corner.TOP_RIGHT);
    private final NumberSetting margin = new NumberSetting("Margin",
        "Gap between the radar and the screen edge.", 6, 0, 40, 1, " px").min(0);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn the radar so the way you face points up.", true);
    private final BoolSetting players = new BoolSetting("Players",
        "Show other players.", true);
    private final BoolSetting mobs = new BoolSetting("Mobs",
        "Show mobs.", false);
    private final BoolSetting items = new BoolSetting("Items",
        "Show dropped items.", false);
    private final BoolSetting names = new BoolSetting("Names",
        "Write player names next to their dot.", true);
    private final BoolSetting height = new BoolSetting("Height tint",
        "Fade dots that are far above or below you.", true);

    public Radar() {
        super("Radar", "Draws a small map of nearby entities on your screen.", Category.RENDER);
        addSettings(size, range, corner, margin, rotate, players, mobs, items, names, height);
        searchTags("minimap", "entity map");
    }

    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!inGame()) {
            return;
        }
        GuiGraphicsExtractor context = event.getContext();
        int box = size.getInt();
        int gap = margin.getInt();
        int left = switch (corner.getValue()) {
            case TOP_LEFT, BOTTOM_LEFT -> gap;
            case TOP_RIGHT, BOTTOM_RIGHT -> context.guiWidth() - box - gap;
        };
        int top = switch (corner.getValue()) {
            case TOP_LEFT, TOP_RIGHT -> gap;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> context.guiHeight() - box - gap;
        };
        int centerX = left + box / 2;
        int centerY = top + box / 2;

        RenderUtil.borderedRect(context, left, top, left + box, top + box, BACKGROUND, BORDER);
        context.fill(centerX, top + 2, centerX + 1, top + box - 2, GRID);
        context.fill(left + 2, centerY, left + box - 2, centerY + 1, GRID);

        double blocks = range.getValue();
        double pixelsPerBlock = (box / 2.0) / blocks;
        double yaw = Math.toRadians(mc.player.getYRot());
        boolean turn = rotate.isOn();
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        Vec3 eye = mc.player.position();
        Font font = mc.font;

        context.guiRenderState.up();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !EntityUtil.matches(entity, players.isOn(), mobs.isOn(), items.isOn())) {
                continue;
            }
            double dx = entity.getX() - eye.x;
            double dz = entity.getZ() - eye.z;
            if (dx * dx + dz * dz > blocks * blocks) {
                continue;
            }
            // Yaw zero looks south.
            double rx = turn ? -(dx * cos + dz * sin) : dx;
            double rz = turn ? dx * sin - dz * cos : dz;
            int px = centerX + (int) Math.round(rx * pixelsPerBlock);
            int py = centerY + (int) Math.round(rz * pixelsPerBlock);
            if (px < left + 1 || px > left + box - 2 || py < top + 1 || py > top + box - 2) {
                continue;
            }

            int color = EntityUtil.colorOf(entity);
            if (height.isOn()) {
                double drop = Math.abs(entity.getY() - eye.y);
                color = ColorUtil.fade(color, (float) Math.clamp(1 - drop / 40.0, 0.35, 1));
            }
            boolean isPlayer = entity instanceof Player;
            int dot = isPlayer ? 2 : 1;
            context.fill(px - dot, py - dot, px + dot + 1, py + dot + 1, color);
            if (isPlayer && names.isOn()) {
                String name = entity.getName().getString();
                int width = font.width(name);
                int textX = px - width / 2;
                if (width < box - 2) {
                    textX = Math.clamp(textX, left + 1, left + box - width - 1);
                }
                context.text(font, name, textX, py - font.lineHeight - 2, color, true);
            }
        }

        context.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, SELF);
    }
}
