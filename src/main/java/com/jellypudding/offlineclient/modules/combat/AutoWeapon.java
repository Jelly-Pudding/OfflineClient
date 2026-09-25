package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.AttackEntityEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.WeaponUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class AutoWeapon extends Module {

    public enum Prefer { SWORD, AXE, SPEED }

    private final EnumSetting<Prefer> prefer = new EnumSetting<>("Prefer",
        "Which weapon wins when both are close in damage.", Prefer.SWORD)
        .describe(Prefer.SWORD, "The sword unless the axe hits much harder.")
        .describe(Prefer.AXE, "The axe unless the sword hits much harder.")
        .describe(Prefer.SPEED, "Whichever swings fastest. More hits a second.");
    private final BoolSetting hover = new BoolSetting("Swap on hover",
        "Switches as soon as your crosshair rests on a target. The weapon is in hand before you click.",
        false);
    private final NumberSetting threshold = new NumberSetting("Threshold",
        "How much more damage the other kind must deal after their armour to win.", 2, 0, 10, 0.5)
        .under(prefer, Prefer.SWORD, Prefer.AXE);
    private final BoolSetting antiBreak = new BoolSetting("Anti break",
        "Skip weapons that are about to break.", true);
    private final BoolSetting switchBack = new BoolSetting("Switch back",
        "Return to the slot you had selected once you stop fighting.", false);
    private final NumberSetting releaseTime = new NumberSetting("Release time",
        "Ticks without an attack before switching back.", 20, 1, 100, 1, " ticks")
        .under(switchBack);

    private final SlotSwap slots = new SlotSwap();
    private int timer;

    public AutoWeapon() {
        super("AutoWeapon", "Switches to your strongest sword or axe when you attack.", Category.COMBAT);
        addSettings(prefer, threshold, hover, antiBreak, switchBack, releaseTime);
        searchTags("auto sword", "auto axe", "weapon switch");
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.WEAPON_SWAP;
    }

    @Override
    protected void onEnable() {
        slots.forget();
        timer = 0;
    }

    @Override
    protected void onDisable() {
        if (switchBack.isOn() && inGame()) {
            slots.restore();
        } else {
            slots.forget();
        }
    }

    // AutoTool stands aside whilst this is true.
    public boolean isHoldingWeapon() {
        return isEnabled() && slots.isHolding();
    }

    @Subscribe
    private void onAttack(AttackEntityEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            return;
        }
        if (!(event.getTarget() instanceof LivingEntity target)) {
            return;
        }

        arm(target);
    }

    private void arm(LivingEntity target) {
        int best = bestSlot(target);
        if (best != -1 && best != InventoryUtil.selectedSlot()) {
            slots.select(best);
        }
        timer = releaseTime.getInt();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (hover.isOn() && !mc.player.isDeadOrDying()
            && mc.hitResult instanceof EntityHitResult hit
            && hit.getEntity() instanceof LivingEntity target && target.isAlive()
            && !EntityUtil.isFriend(target)) {
            arm(target);
            return;
        }
        if (!slots.isHolding()) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            // Respawn hands out a fresh inventory.
            slots.forget();
            timer = 0;
            return;
        }
        if (!switchBack.isOn()) {
            slots.forget();
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }
        slots.restore();
    }

    // Read by KillAura ahead of its hits.
    public int bestSlot(LivingEntity target) {
        if (prefer.is(Prefer.SPEED)) {
            return WeaponUtil.fastestWeaponSlot(antiBreak.isOn());
        }
        return WeaponUtil.bestWeaponSlot(target, prefer.is(Prefer.SWORD), threshold.getValue(), antiBreak.isOn());
    }
}
