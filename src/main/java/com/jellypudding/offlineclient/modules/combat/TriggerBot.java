package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.List;

public final class TriggerBot extends Module {

    // Hitbox reach is shorter than the centre distance. The scan runs wider.
    private static final double SCAN_MARGIN = 4;

    private final NumberSetting range = new NumberSetting("Range",
        "Maximum reach in blocks.", 4.2, 1, 10, 0.05).max(10);
    private final NumberSetting fov = new NumberSetting("FOV",
        "Only hit what sits within this angle of your view. Small values act as a crosshair check.",
        30, 30, 360, 10, " degrees").max(360);
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Attacks", TargetPriority.CLOSEST_ANGLE);
    private final EntityFilter filter = EntityFilter.living("Swing at", "hit", true,
        EntityFilter.Pick.ALL, List.of());
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only swing whilst you hold the attack key down.", false);
    private final BoolSetting whilstBlocking = new BoolSetting("Attack whilst blocking",
        "Swings even whilst you hold a shield up.", false);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SPAM);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    public TriggerBot() {
        super("TriggerBot", "Swings at whatever your crosshair is on.", Category.COMBAT);
        addSettings(range, fov, priority);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(onlyOnClick, whilstBlocking, faceTarget, swing);
        addSettings(timer.settings());
        searchTags("click aura", "trigger");
    }

    @Override
    protected void onEnable() {
        timer.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.gui.screen() != null || mc.player.isSpectator()) {
            return;
        }
        boolean blocking = whilstBlocking.isOn() && mc.player.isBlocking();
        if ((mc.player.isUsingItem() && !blocking) || mc.gameMode.isDestroying() || Modules.eating()) {
            return;
        }
        if (onlyOnClick.isOn() && !mc.options.keyAttack.isDown()) {
            return;
        }
        if (mc.player.getAttackStrengthScale(0.5f) < 1 || !timer.ready()) {
            return;
        }
        Entity target = EntityUtil.best(range.getValue() + SCAN_MARGIN, priority.getValue(),
            this::attackable);
        if (target == null) {
            return;
        }
        // The look packet goes out first. The server sees a fair hit.
        faceTarget.getValue().face(target.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        mc.gameMode.attack(mc.player, target);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer.spent();
    }

    private boolean attackable(Entity entity) {
        if (!targets.attackable(entity, filter)) {
            return false;
        }
        // The crosshair reaches further than the server allows a hit.
        if (EntityUtil.reachDistance(mc.player, entity) > range.getValue()) {
            return false;
        }
        return fov.getValue() >= 360 || EntityUtil.lookAngleTo(entity) <= fov.getValue() / 2;
    }
}
