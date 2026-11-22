package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Feeding;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.Targeting;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class TriggerBot extends Module implements Targeting {

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach.", 4.2, 1, 10, 0.05, " blocks");
    private final NumberSetting fov = new NumberSetting("FOV",
        "Only hits what sits within this angle of your view. Small values act as a crosshair check.",
        30, 30, 360, 10, " degrees").min(1).max(360);
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Attacks", TargetPriority.CLOSEST_ANGLE);
    private final EntityFilter filter = EntityFilter.living("Swings at", "hit", true,
        EntityFilter.Pick.ALL, List.of());
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only swings whilst you hold the attack key down.", false);
    private final BoolSetting whilstBlocking = new BoolSetting("Attack whilst blocking",
        "Swings even whilst you hold a shield up.", false);
    private final BoolSetting onlyCrits = new BoolSetting("Only crits",
        "Holds the swing back until the hit would be critical. That takes a jump unless Criticals is on.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SPAM);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    private Entity target;

    public TriggerBot() {
        super("TriggerBot", "Swings at whatever sits near your crosshair.", Category.COMBAT);
        addSettings(range, fov, priority);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(onlyOnClick, whilstBlocking, onlyCrits, faceTarget, swing);
        addSettings(timer.settings());
        searchTags("click aura", "trigger");
    }

    @Override
    public Entity getFought() {
        return target;
    }

    @Override
    protected void onEnable() {
        timer.clear();
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        target = null;
        if (mc.gui.screen() != null || Hands.tied()) {
            return;
        }
        boolean blocking = whilstBlocking.isOn() && mc.player.isBlocking();
        if ((mc.player.isUsingItem() && !blocking) || BlockMiner.handMines()
            || Feeding.pausingCombat() || Modules.maceComboAirborne()) {
            return;
        }
        if (onlyOnClick.isOn() && !mc.options.keyAttack.isDown()) {
            return;
        }
        // Picked before the cooldown is read. Other modules stand aside for the target whilst the cooldown fills.
        target = EntityUtil.bestInReach(range.getValue(), priority.getValue(), this::attackable);
        if (target == null || onlyCrits.isOn() && !critDue(target) || !AttackTimer.charged() || !timer.ready()) {
            return;
        }
        // Only Spam turns ahead of the hit. Server and Client turn in the movement packet after it.
        faceTarget.getValue().face(target.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        timer.strike(target, swing.getValue());
    }

    // A fall makes the hit critical. Criticals does the same from the ground.
    private static boolean critDue(Entity target) {
        Criticals criticals = Modules.active(Criticals.class);
        return DamageUtil.canCrit(mc.player) || criticals != null && criticals.makesCritical(target);
    }

    @Override
    public boolean wouldFight(Entity entity) {
        return targets.attackable(entity, filter);
    }

    private boolean attackable(Entity entity) {
        return wouldFight(entity)
            && (fov.getValue() >= 360 || EntityUtil.lookAngleToNearest(entity) <= fov.getValue() / 2);
    }
}
