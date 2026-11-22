package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.Rebreak;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

// An ender chest breaks into eight obsidian without silk touch.
public final class EChestFarmer extends Module {

    private final BoolSetting stopAtAmount = new BoolSetting("Stop at amount",
        "Turns off once you hold enough obsidian.", false);
    private final BoolSetting countExisting = new BoolSetting("Count existing",
        "Obsidian you already carry counts towards the amount.", false).under(stopAtAmount);
    private final NumberSetting amount = new NumberSetting("Amount",
        "How much obsidian to gather.", 64, 8, 256, 8).min(1).max(2304).under(stopAtAmount);
    private final BoolSetting instant = new BoolSetting("Instant rebreak",
        "Breaks every chest after the first at once with a single packet. The first is mined the usual way.",
        false);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns towards the chest on the server side.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outlines where the chest goes.", true);
    private final BoxStyle targetBox = new BoxStyle(BoxStyle.Shape.BOTH, 0).under(render);

    private BlockPos target;
    private int startCount;
    private final SlotSwap slots = new SlotSwap();
    private final Rebreak rebreak = new Rebreak();
    private final ChatWarning outOfReach = new ChatWarning();

    public EChestFarmer() {
        super("EChestFarmer", "Places and breaks ender chests to farm obsidian.", Category.WORLD);
        addSettings(stopAtAmount, countExisting, amount, instant, rotate, swing, render);
        addSettings(targetBox.settings());
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
        outOfReach.clear();
    }

    @Override
    protected void onDisable() {
        BlockMiner.release();
        slots.restoreIfMine();
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied() || mc.gui.screen() != null) {
            return;
        }
        if (Hands.taken()) {
            BlockMiner.release();
            return;
        }
        if (target == null && !pickTarget()) {
            return;
        }
        if (!reachable(target)) {
            target = null;
            return;
        }
        // Stepping onto the spot would leave every placement refused.
        if (BlockUtil.intersectsPlayer(target)) {
            target = null;
            return;
        }
        if (stopAtAmount.isOn() && gathered() >= amount.getInt()) {
            tell("gathered enough obsidian.");
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

    private boolean pickTarget() {
        BlockHitResult hit = BlockUtil.aimedBlock();
        if (hit == null) {
            return false;
        }
        BlockPos above = hit.getBlockPos().above();
        BlockState state = BlockUtil.state(above);
        if (BlockUtil.intersectsPlayer(above)) {
            return false;
        }
        if (!state.canBeReplaced() && !state.is(Blocks.ENDER_CHEST)) {
            return false;
        }
        if (!reachable(above)) {
            return false;
        }
        target = above.immutable();
        return true;
    }

    // A centre within reach keeps the block the chest is clicked onto within the server's reach as
    // well.
    private boolean reachable(BlockPos pos) {
        if (!BlockUtil.inReach(pos)) {
            outOfReach.say("The chest spot is out of reach.");
            return false;
        }
        outOfReach.clear();
        return true;
    }

    // Silk touch would give the chest back whole.
    private void breakChest() {
        int slot = ItemUtil.bestToolSlot(BlockUtil.state(target), 1,
            stack -> ItemUtil.enchantLevel(Enchantments.SILK_TOUCH, stack) == 0,
            InventoryUtil.HOTBAR_SIZE);
        if (slot == -1) {
            disable("No pickaxe without Silk Touch in the hotbar.");
            return;
        }
        slots.select(slot);
        if (instant.isOn() && rebreak.strike(target)) {
            return;
        }
        BlockMiner.mineWithHeld(target, rotate.isOn(), swing.getValue());
    }

    private void placeChest() {
        BlockMiner.release();
        rebreak.emptied();
        int slot = InventoryUtil.hotbarSlot(stack -> stack.is(Items.ENDER_CHEST));
        if (slot == -1) {
            disable("No ender chests in the hotbar.");
            return;
        }
        Direction support = BlockUtil.findPlaceSupport(target);
        if (support == null) {
            return;
        }
        slots.select(slot);
        if (BlockUtil.place(target, support, rotate.isOn(), false)) {
            swing.getValue().use();
        }
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
            targetBox.draw(event.getBatch(), target, false);
        }
    }
}
