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
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.Targeting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Hops to a new spot beside the target before every hit. Hard to fight back against.
// A target with no clear spot beside it is hit from where you stand whilst it is in reach.
public final class TpAura extends Module implements Targeting {

    // The hop lands two blocks off the target on either axis or right on it.
    private static final int HOP = 2;

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a target may be.", 4.25, 1, 6, 0.05, " blocks");
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Attacks", TargetPriority.CLOSEST_ANGLE);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting pauseInContainers = new BoolSetting("Pause in containers",
        "Holds off whilst a chest or another container is open.", true);
    private final EntityFilter filter = EntityFilter.living("Attacks", "attacked", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final AttackTimer timer = new AttackTimer();

    private Entity fought;

    public TpAura() {
        super("TpAura", "Teleports around a target whilst hitting it.", Category.COMBAT);
        addSettings(range, priority, swing, pauseInContainers);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(timer.settings());
        searchTags("tp aura", "ender aura", "teleport aura");
    }

    // What the hops go round. Null between fights and once it has died.
    @Override
    public Entity getFought() {
        return fought != null && fought.isAlive() ? fought : null;
    }

    @Override
    protected void onEnable() {
        timer.clear();
    }

    @Override
    protected void onDisable() {
        fought = null;
    }

    // The hops go on whilst the attack cooldown fills. The target stays picked through a hop and
    // through the wait for the next hit. A fresh pick in that wait takes its place.
    @Subscribe
    private void onTick(TickEvent event) {
        if (Hands.tied() || pauseInContainers.isOn() && InventoryUtil.containerOpen()) {
            fought = null;
            return;
        }
        if (Hop.travelling()) {
            return;
        }
        Entity picked = EntityUtil.best(range.getValue(), priority.getValue(), this::wouldFight);
        boolean ready = timer.ready();
        if (picked != null || ready) {
            fought = picked;
        }
        if (picked != null && ready) {
            fight(picked);
        }
    }

    // The hit rides the hop it is charged for and goes out straight after the position that hop
    // sends.
    private void fight(Entity target) {
        Hop.Plan hop = hopBeside(target);
        if (hop != null) {
            if (AttackTimer.charged()) {
                face(target);
                hop.then(() -> strike(target));
            }
            hop.go(result -> { });
        } else if (AttackTimer.charged()
            && EntityUtil.reachDistance(mc.player, target) <= mc.player.entityInteractionRange()) {
            face(target);
            strike(target);
        }
    }

    // One straight hop to the first spot round the target the server takes whole. The spots are
    // tried in a random order. Null when none of them will do.
    private static Hop.Plan hopBeside(Entity target) {
        List<Vec3> spots = new ArrayList<>();
        for (int x = -HOP; x <= HOP; x += HOP) {
            for (int z = -HOP; z <= HOP; z += HOP) {
                spots.add(target.position().add(x, 0, z));
            }
        }
        Collections.shuffle(spots, ThreadLocalRandom.current());
        Hop.Plan plan = Hop.plan();
        for (Vec3 spot : spots) {
            if (plan.attempt(steps -> steps.hopTo(spot))) {
                return plan;
            }
        }
        return null;
    }

    private static void face(Entity target) {
        RotationManager.look(target.getBoundingBox().getCenter(), RotationPriority.ATTACK,
            RotationManager.ENTITY_TOLERANCE);
    }

    // The target may be gone by the time the hop goes out.
    private void strike(Entity target) {
        if (target.isAlive()) {
            timer.strike(target, swing.getValue());
        }
    }

    @Override
    public boolean wouldFight(Entity entity) {
        return targets.attackable(entity, filter);
    }
}
