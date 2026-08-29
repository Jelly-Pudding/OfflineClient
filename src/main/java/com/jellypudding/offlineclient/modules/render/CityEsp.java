package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.AutoCity;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

// The pick matches what AutoCity would mine.
public final class CityEsp extends Module {

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 16, 0.5, " blocks").max(64);
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to mine.", 4.5, 1, 6, 0.1).min(1);
    private final BoolSetting nearestOnly = new BoolSetting("Nearest only",
        "Only mark the closest enemy.", false);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", true);
    private final BoolSetting throughWalls = new BoolSetting("Through walls",
        "Show boxes behind blocks.", true);
    private final ColorSetting color = new ColorSetting("Color",
        "Box colour.", 0, false);

    private final List<BlockPos> targets = new ArrayList<>();

    public CityEsp() {
        super("CityESP", "Highlights the block that would open up a surrounded enemy.", Category.RENDER);
        addSettings(targetRange, breakRange, nearestOnly, fill, throughWalls, color);
        searchTags("city", "surround", "obsidian", "autocity");
    }

    @Override
    public String getSuffix() {
        return targets.isEmpty() ? null : String.valueOf(targets.size());
    }

    @Override
    protected void onDisable() {
        targets.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        double reach = breakRange.getValue();
        if (nearestOnly.isOn()) {
            Player nearest = EntityUtil.nearestEnemy(targetRange.getValue());
            addTarget(nearest, reach);
            return;
        }
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (EntityUtil.isFriend(player)) {
                continue;
            }
            if (mc.player.distanceTo(player) > targetRange.getValue()) {
                continue;
            }
            addTarget(player, reach);
        }
    }

    private void addTarget(Player target, double reach) {
        if (target == null) {
            return;
        }
        BlockPos pos = AutoCity.cityBlock(target, reach);
        if (pos != null && !targets.contains(pos)) {
            targets.add(pos);
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        // A frame can land after the world has gone.
        if (targets.isEmpty() || !inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        int argb = color.getColor();
        boolean through = throughWalls.isOn();
        for (BlockPos pos : targets) {
            if (BlockUtil.state(pos).isAir()) {
                continue;
            }
            AABB box = DrawBatch.blockBox(pos);
            batch.outlineBox(box, argb, through);
            if (fill.isOn()) {
                batch.solidBox(box, ColorUtil.withAlpha(argb, 50), through);
            }
        }
    }
}
