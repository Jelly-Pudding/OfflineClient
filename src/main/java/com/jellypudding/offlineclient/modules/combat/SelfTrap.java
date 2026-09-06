package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

public final class SelfTrap extends Module {

    public enum Top { TOP, FULL, SIDES, NONE }

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.", BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.NETHERITE_BLOCK));
    private final EnumSetting<Top> top = new EnumSetting<>("Placement",
        "Which blocks go round your upper half.", Top.TOP)
        .describe(Top.TOP, "Covers your head only.")
        .describe(Top.FULL, "Seals your head and the sides at head height.")
        .describe(Top.SIDES, "Seals the sides at head height and leaves your head open.")
        .describe(Top.NONE, "Nothing round your upper half.");
    private final BoolSetting bottom = new BoolSetting("Bottom",
        "Places a block under your feet as well.", false);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placements.", 1, 0, 5, 1, " ticks");
    private final BoolSetting center = new BoolSetting("Centre",
        "Moves you to the middle of your block when the module turns on.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once every spot is filled.", true);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outline the spots still to fill.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0).under(render);

    private int timer;
    private final SlotSwap slots = new SlotSwap();
    private boolean placed;

    // The spots the last tick found. The unobstructed test walks the entity list.
    private List<BlockPos> pending = List.of();

    public SelfTrap() {
        super("SelfTrap", "Places blocks above your head to stop crystals.", Category.COMBAT);
        addSettings(blocks, top, bottom, delay, center, rotate, toggleOff, render);
        addSettings(style.settings());
        searchTags("obsidian", "head", "crystal");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        placed = false;
        pending = List.of();
        if (center.isOn() && inGame()) {
            BlockUtil.centerPlayer();
        }
    }

    @Override
    protected void onDisable() {
        slots.restore();
        pending = List.of();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        List<BlockPos> missing = BlockUtil.trapSpots(mc.player.blockPosition(),
            top.isAny(Top.TOP, Top.FULL), top.isAny(Top.FULL, Top.SIDES), bottom.isOn());
        pending = missing;
        if (missing.isEmpty()) {
            slots.restore();
            if (toggleOff.isOn() && placed) {
                setEnabled(false);
            }
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        int slot = BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);

        BlockPos target = missing.getFirst();
        boolean ok = BlockUtil.placeAny(target, rotate.isOn(), true);
        if (ok) {
            placed = true;
            timer = delay.getInt();
        }
        slots.restore();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn()) {
            return;
        }
        for (BlockPos pos : pending) {
            style.draw(event.getBatch(), pos, false);
        }
    }
}
