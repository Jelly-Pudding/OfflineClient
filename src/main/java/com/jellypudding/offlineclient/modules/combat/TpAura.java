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
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Hops to a new spot beside the target before every hit. Hard to fight back against.
public final class TpAura extends Module {

    // The hop lands two blocks off the target on either axis or right on it.
    private static final int HOP = 2;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a target may be.", 4.25, 1, 6, 0.05).min(1).max(6);
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Attacks", TargetPriority.CLOSEST_ANGLE);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting pauseInContainers = new BoolSetting("Pause in containers",
        "Holds off whilst a chest or another container is open.", true);
    private final EntityFilter filter = EntityFilter.living("Attack", "attacked", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    public TpAura() {
        super("TpAura", "Teleports around a target whilst hitting it.", Category.COMBAT);
        addSettings(range, priority, swing, pauseInContainers);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(timer.settings());
        searchTags("tp aura", "ender aura", "teleport aura");
    }

    @Override
    protected void onEnable() {
        timer.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || !timer.ready()) {
            return;
        }
        if (pauseInContainers.isOn() && InventoryUtil.isStorage(mc.gui.screen())) {
            return;
        }
        Entity target = EntityUtil.best(range.getValue(), priority.getValue(), this::attackable);
        if (target == null) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        mc.player.setPos(target.getX() + random.nextInt(3) * HOP - HOP, target.getY(),
            target.getZ() + random.nextInt(3) * HOP - HOP);
        if (mc.player.getAttackStrengthScale(0) < 1) {
            return;
        }
        RotationManager.look(target.getBoundingBox().getCenter(), RotationPriority.ATTACK,
            RotationManager.ENTITY_TOLERANCE);
        mc.gameMode.attack(mc.player, target);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer.spent();
    }

    private boolean attackable(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive()
            && !EntityUtil.isFriend(entity) && filter.matches(entity) && targets.allows(entity);
    }
}
