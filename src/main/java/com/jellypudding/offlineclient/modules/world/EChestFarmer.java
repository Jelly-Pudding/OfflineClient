package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * An ender chest breaks into eight obsidian without silk touch. The chest
 * goes on the block you look at and is mined straight back out.
 */
public final class EChestFarmer extends Module {

    private static final int TARGET_COLOR = 0xFFE03030;

    private final BoolSetting stopAtAmount = new BoolSetting("Stop at amount",
        "Turns off once you hold enough obsidian.", false);
    private final BoolSetting countExisting = new BoolSetting("Count existing",
        "Obsidian you already carry counts toward the amount.", false).under(stopAtAmount);
    private final NumberSetting amount = new NumberSetting("Amount",
        "How much obsidian to gather.", 64, 8, 256, 8).min(1).max(2304).under(stopAtAmount);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn toward the chest on the server side.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outlines where the chest goes.", true);

    private BlockPos target;
    private int startCount;
    private final SlotSwap slots = new SlotSwap();

    public EChestFarmer() {
        super("EChestFarmer", "Places and breaks ender chests to farm obsidian.", Category.WORLD);
        addSettings(stopAtAmount, countExisting, amount, rotate, render);
        searchTags("obsidian farm", "ender chest");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        target = null;
        startCount = obsidianCount();
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        if (target == null && !pickTarget()) {
            return;
        }
        if (BlockUtil.distanceTo(target) > mc.player.blockInteractionRange()) {
            ChatUtil.error("The chest spot is out of reach.");
            target = null;
            return;
        }
        if (stopAtAmount.isOn() && gathered() >= amount.getInt()) {
            ChatUtil.message("§bEChestFarmer §7gathered enough obsidian.");
            setEnabled(false);
            return;
        }
        BlockState state = BlockUtil.state(target);
        if (state.is(Blocks.ENDER_CHEST)) {
            breakChest();
        } else if (state.canBeReplaced()) {
            placeChest();
        }
    }

    // The block above whatever the crosshair rests on.
    private boolean pickTarget() {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos above = hit.getBlockPos().above();
        BlockState state = BlockUtil.state(above);
        if (!state.canBeReplaced() && !state.is(Blocks.ENDER_CHEST)) {
            return false;
        }
        target = above.immutable();
        return true;
    }

    // Silk touch would give the chest back whole.
    private void breakChest() {
        int slot = ItemUtil.bestToolSlot(BlockUtil.state(target), 1,
            stack -> ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) == 0,
            InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            ChatUtil.error("No pickaxe without Silk Touch in the hotbar.");
            setEnabled(false);
            return;
        }
        slots.select(slot);
        BlockMiner.mine(target, rotate.isOn());
    }

    private void placeChest() {
        BlockMiner.release();
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.ENDER_CHEST));
        if (slot == -1) {
            ChatUtil.error("No ender chests in the hotbar.");
            setEnabled(false);
            return;
        }
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support == null) {
            return;
        }
        slots.select(slot);
        BlockUtil.place(target, support, rotate.isOn(), true);
    }

    private int gathered() {
        return obsidianCount() - (countExisting.isOn() ? 0 : startCount);
    }

    private int obsidianCount() {
        return InventoryUtil.count(Items.OBSIDIAN, InventoryUtil.WHOLE_INVENTORY);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn() && target != null) {
            event.getBatch().outlineBox(new AABB(target).deflate(1 / 16.0), TARGET_COLOR, false);
        }
    }
}
