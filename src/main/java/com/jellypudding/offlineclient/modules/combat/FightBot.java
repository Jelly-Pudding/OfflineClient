package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.AttackTimer;
import com.jellypudding.offlineclient.util.Chase;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;

// Runs at whatever it is set on and hits it until one of you is dead.
public final class FightBot extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How close a target has to be before the hits start.", 4.25, 1, 6, 0.05).min(1).max(6);
    private final NumberSetting distance = new NumberSetting("Distance",
        "How close the bot walks. Keep it under the range.", 3, 1, 6, 0.05).min(0.5).max(6);
    private final NumberSetting searchRange = new NumberSetting("Search range",
        "How far away a target is picked up.", 32, 8, 64, 1, " blocks").min(1);
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Attacks", TargetPriority.NEAREST);
    private final BoolSetting usePath = new BoolSetting("Use pathfinder",
        "Finds a way round what is in between instead of running straight at the target.", false);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting pauseInContainers = new BoolSetting("Pause in containers",
        "Holds off whilst a chest or another container is open.", true);
    private final EntityFilter filter = EntityFilter.living("Fight", "fought", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    private final Chase chase = new Chase();
    private Entity target;

    public FightBot() {
        super("FightBot", "Runs after a target and fights it for you.", Category.COMBAT);
        addSettings(range, distance, searchRange, priority, usePath, swing, pauseInContainers);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(timer.settings());
        searchTags("fight bot", "auto fight", "pvp bot");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return target == null ? null : target.getName().getString();
    }

    @Override
    protected void onEnable() {
        timer.clear();
        target = null;
    }

    @Override
    protected void onDisable() {
        chase.stop();
        target = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            chase.stop();
            return;
        }
        if (pauseInContainers.isOn() && InventoryUtil.isStorage(mc.gui.screen())) {
            chase.stop();
            return;
        }
        target = EntityUtil.best(searchRange.getValue(), priority.getValue(), this::attackable);
        if (target == null) {
            chase.stop();
            return;
        }
        chase.tick(target, distance.getValue(), usePath.isOn());
        if (!timer.ready() || EntityUtil.reachDistance(mc.player, target) > range.getValue()) {
            return;
        }
        mc.gameMode.attack(mc.player, target);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer.spent();
    }

    private boolean attackable(Entity entity) {
        return entity instanceof LivingEntity living && living.isAlive()
            && !EntityUtil.isFriend(entity) && filter.matches(entity) && targets.allows(entity);
    }
}
