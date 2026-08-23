package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SpawnUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class SpawnProofer extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How far you can reach to place.", 4.5, 1, 6, 0.1).min(1);
    private final NumberSetting light = new NumberSetting("Light",
        "Highest block light a spot may have.", 0, 0, 15, 1).min(0).max(15);
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "What to place on a dark spot. Click to pick.", BuiltInRegistries.BLOCK,
        List.of(Blocks.TORCH, Blocks.SOUL_TORCH));
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one round.", 1, 1, 8, 1).min(1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 2, 0, 20, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block.", true);
    private final BoolSetting render = new BoolSetting("Show targets",
        "Outline the spots waiting to be blocked off.", true);

    private final List<BlockPos> targets = new ArrayList<>();
    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public SpawnProofer() {
        super("SpawnProofer", "Lights up or fills the spots mobs would spawn in.", Category.WORLD);
        addSettings(range, light, blocks, perTick, delay, rotate, render);
        searchTags("torch", "spawn proof", "light");
    }

    @Override
    public String getSuffix() {
        return targets.isEmpty() ? null : String.valueOf(targets.size());
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        targets.clear();
    }

    @Override
    protected void onDisable() {
        slots.restore();
        targets.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        targets.clear();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        collect();
        if (targets.isEmpty()) {
            slots.restore();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findBlockSlot(blocks::contains);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        int placed = 0;
        for (BlockPos pos : targets) {
            if (placed >= perTick.getInt()) {
                break;
            }
            // The floor under the spot is always a sturdy face to click.
            BlockUtil.place(pos, Direction.DOWN, rotate.isOn(), true);
            placed++;
        }
        if (placed > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    // Dark spots in reach. Nearest first.
    private void collect() {
        int maxLight = light.getInt();
        for (BlockPos pos : BlockUtil.positionsWithin(range.getValue())) {
            if (BlockUtil.intersectsPlayer(pos) || !BlockUtil.isReplaceable(pos)) {
                continue;
            }
            if (SpawnUtil.spawnable(pos, maxLight)) {
                targets.add(pos.immutable());
            }
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        int next = perTick.getInt();
        for (int i = 0; i < targets.size(); i++) {
            int color = i < next ? 0xFFFFD040 : 0x80FFD040;
            event.getBatch().outlineBox(new AABB(targets.get(i)).deflate(0.002), color, false);
        }
    }
}
