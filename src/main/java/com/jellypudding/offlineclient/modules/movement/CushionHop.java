package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.KeybindSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.CushionItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

// Sitting on a cushion puts you on it whatever lies between. A cushion goes on the block you look
// at and you sit on it from where you are. A wall or a gap of a few blocks is crossed that way.
public final class CushionHop extends Module {

    // A cushion this near the block you look at is the one meant.
    private static final double NEAR = 1;

    private final KeybindSetting hopKey = new KeybindSetting("Hop key",
        "Hops only whilst this key is held. With no key it hops whenever a cushion is in your hand.",
        KeybindSetting.UNBOUND);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks between setting a cushion down and sitting on it and the next one.", 3, 1, 20, 1, " ticks");
    private final BoolSetting breakBehind = new BoolSetting("Break behind",
        "Breaks the cushion you left each time you reach the next. It drops where it stood.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards the cushion you sit on.", true);

    private int wait;

    public CushionHop() {
        super("CushionHop", "Carries you from cushion to cushion along the way you look.", Category.MOVEMENT);
        addSettings(hopKey, delay, breakBehind, rotate);
        searchTags("cushion", "sit", "hop", "climb", "seat");
    }

    @Override
    protected void onEnable() {
        wait = 0;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied() || mc.gui.screen() != null || hopKey.isBound() && !hopKey.isHeld()) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Cushion ahead = aimedCushion();
        if (ahead != null) {
            sitOn(ahead);
        } else if (mc.player.getMainHandItem().getItem() instanceof CushionItem) {
            setDown();
        }
    }

    // The cushion under the crosshair or the one on the block under it. Never the one you sit on.
    private Cushion aimedCushion() {
        if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Cushion cushion) {
            return free(cushion) ? cushion : null;
        }
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed == null) {
            return null;
        }
        Vec3 top = Vec3.atBottomCenterOf(aimed.getBlockPos().above());
        Cushion nearest = null;
        for (Cushion cushion : mc.level.getEntitiesOfClass(Cushion.class, new AABB(aimed.getBlockPos()).inflate(NEAR),
            CushionHop::free)) {
            if (nearest == null || cushion.distanceToSqr(top) < nearest.distanceToSqr(top)) {
                nearest = cushion;
            }
        }
        return nearest;
    }

    private static boolean free(Cushion cushion) {
        return cushion.isAlive() && !cushion.isVehicle();
    }

    private void sitOn(Cushion cushion) {
        Entity left = mc.player.getVehicle();
        if (!EntityUtil.interact(cushion, InteractionHand.MAIN_HAND, rotate.isOn())) {
            return;
        }
        wait = delay.getInt();
        if (breakBehind.isOn() && left instanceof Cushion && left.isAlive()) {
            mc.gameMode.attack(mc.player, left);
            SwingMode.BOTH.hit();
        }
    }

    // On the face you look at first and on top of the block when that face takes none.
    private void setDown() {
        BlockHitResult aimed = BlockUtil.aimedBlock();
        if (aimed == null) {
            return;
        }
        BlockPos block = aimed.getBlockPos();
        if (BlockUtil.interact(block, aimed.getDirection())
            || aimed.getDirection() != Direction.UP && BlockUtil.interact(block, Direction.UP)) {
            wait = delay.getInt();
        }
    }
}
