package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

// Sheep and anything else that takes shears is clipped as it wanders past.
public final class AutoShearer extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How close an animal has to be.", 5, 1, 6, 0.1).max(6);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Never uses shears that are about to break.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the animal on the server side.", true);

    private final SlotSwap slots = new SlotSwap();
    private int sheared;

    public AutoShearer() {
        super("AutoShearer", "Shears every sheep that comes near you.", Category.WORLD);
        addSettings(range, antiBreak, rotate);
        searchTags("sheep", "wool", "shears");
    }

    @Override
    public String getSuffix() {
        return sheared == 0 ? null : sheared + " sheared";
    }

    @Override
    protected void onEnable() {
        sheared = 0;
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.gui.screen() != null) {
            return;
        }
        Entity target = EntityUtil.nearest(range.getValue(), AutoShearer::ready);
        if (target == null) {
            slots.restoreIfMine();
            return;
        }
        int slot = InventoryUtil.hotbarSlot(stack ->
            stack.is(Items.SHEARS) && (!antiBreak.isOn() || !ItemUtil.nearlyBroken(stack)));
        if (slot == -1) {
            return;
        }
        slots.select(slot);
        if (rotate.isOn()) {
            BlockUtil.faceVector(target.getBoundingBox().getCenter());
        }
        EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
        if (mc.gameMode.interact(mc.player, target, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            sheared++;
        }
        slots.restoreIfMine();
    }

    private static boolean ready(Entity entity) {
        return entity instanceof Shearable shearable && entity.isAlive() && shearable.readyForShearing();
    }
}
