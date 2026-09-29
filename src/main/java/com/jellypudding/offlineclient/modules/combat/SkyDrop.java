package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.HeldShot;
import com.jellypudding.offlineclient.util.Hop;
import com.jellypudding.offlineclient.util.ProjectilePath;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

// Carries a shot high over a target and fires it straight down as the drop lands. The
// server hands the shot the whole drop as speed. On Paper the trip there and back fits
// one tick.
public final class SkyDrop extends Module {

    // The feet stop this far over the target's head.
    private static final double HEAD_GAP = 0.05;

    // A shorter drop adds too little to be worth the trip. A vehicle takes no more than
    // two and a half blocks a hop and still gets its throw onto the target.
    private static final double LEAST_DROP = 2;
    private static final double DROP_STEP = 1;

    // A bow or trident shot has to head down at least this fast to strike the head.
    private static final double LEAST_DOWN = 1;

    // Runs ahead of Slingshot. A shot carried over a target is not launched as well.
    private static final int BEFORE_SLINGSHOT = 10;

    private final RegistryListSetting<Item> items = HeldShot.items("Which shots are carried over the target.",
        List.of(Items.BOW, Items.TRIDENT));
    private final NumberSetting range = new NumberSetting("Range",
        "How far away a target may be.", 60, 5, 200, 1, " blocks").min(1);
    private final NumberSetting height = new NumberSetting("Height",
        "How far you drop onto the target before the shot leaves. Each block adds a block a tick to its"
            + " speed. Vanilla allows about twenty and Paper about a hundred and ninety.",
        60, LEAST_DROP, 200, 1, " blocks");
    private final NumberSetting throwsEach = new NumberSetting("Throws",
        "How many go down on the target for each throw you make. A pearl or a wind charge goes once.",
        1, 1, 16, 1).min(1);
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Drops on",
        TargetPriority.CLOSEST_ANGLE);
    private final EntityFilter filter = EntityFilter.living("Target", "targeted", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();

    private final ChatWarning warning = new ChatWarning();

    // The shot kept back until the drop reaches the server.
    private HeldShot held;

    // Where the drop starts and where it lands over the head.
    private record Drop(Vec3 top, Vec3 strike) {
    }

    public SkyDrop() {
        super("SkyDrop", "Carries your throws and arrows high over a target and fires them straight down.",
            Category.COMBAT);
        addSettings(items, range, height, throwsEach, priority);
        addSettings(filter.settings());
        addSettings(targets.settings());
        searchTags("arrow drop", "bow bomb", "potion drop", "sky shot");
    }

    @Override
    protected void onEnable() {
        warning.clear();
    }

    @Subscribe(priority = BEFORE_SLINGSHOT)
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !inGame()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (held != null) {
            if (held.gather(packet)) {
                event.cancel();
            }
            return;
        }
        HeldShot shot = Hop.travelling() ? null : HeldShot.of(packet, items);
        Entity target = shot == null ? null
            : EntityUtil.best(range.getValue(), priority.getValue(), this::targetable);
        if (target == null) {
            return;
        }
        Hop.Plan plan = plan(shot, target);
        if (plan == null) {
            return;
        }
        warning.clear();
        event.cancel();
        held = shot;
        plan.go(this::landed);
    }

    private boolean targetable(Entity entity) {
        return targets.attackable(entity, filter);
    }

    // Up to the top of the drop and down onto the head with the shot and back home. A
    // target on the move is met where it will be once the drop lands. Null after saying
    // why there is no way.
    private Hop.Plan plan(HeldShot shot, Entity target) {
        Hop.Result trouble = Hop.plan().problem();
        if (trouble != null) {
            warning.say(trouble.problem());
            return null;
        }
        Entity mover = Hop.mover(mc.player);
        Vec3 home = mover.position();
        Drop drop = drop(shot, mover, headOf(target));
        if (drop != null) {
            Vec3 drift = EntityUtil.velocityOf(target).multiply(1, 0, 1);
            drop = drop(shot, mover, headOf(target).add(drift.scale(ticksToDrop(drop))));
        }
        if (drop == null) {
            warning.say("There is no room above the target.");
            return null;
        }
        Drop chosen = drop;
        Hop.Plan plan = Hop.plan();
        if (!plan.attempt(steps -> steps.to(chosen.top()).hopTo(chosen.strike()).then(() -> fire(shot))
            .to(home))) {
            warning.say("There is no clear way over the target.");
            return null;
        }
        return plan;
    }

    private static Vec3 headOf(Entity target) {
        AABB box = target.getBoundingBox();
        return new Vec3(box.getCenter().x, box.maxY, box.getCenter().z);
    }

    // Ticks the trip takes to reach the drop. A burst that fits one tick counts as one.
    // Nothing when the way there cannot be counted.
    private static int ticksToDrop(Drop drop) {
        Hop.Plan probe = Hop.plan();
        boolean planned = probe.attempt(steps -> steps.to(drop.top()).hopTo(drop.strike()));
        int ticks = planned ? probe.ticks() : 0;
        return ticks == Integer.MAX_VALUE ? 0 : ticks;
    }

    // The highest drop onto the head that has room at both ends. Null when none does.
    private Drop drop(HeldShot shot, Entity mover, Vec3 head) {
        double highest = Math.min(height.getValue(), Hop.plan().longestDrop());
        for (double fall = highest; fall >= LEAST_DROP; fall -= DROP_STEP) {
            Vec3 strike = strikeSpot(shot, mover, head, fall);
            if (strike == null || !Hop.safeAt(mover, strike)) {
                continue;
            }
            Vec3 top = strike.add(0, fall, 0);
            if (Hop.safeAt(mover, top)) {
                return new Drop(top, strike);
            }
        }
        return null;
    }

    // Where the mover lands over the head for a drop that long. A throw goes out aimed
    // straight down. A bow or trident shot keeps its own speed along the aim and the spot
    // leads it onto the head. Null when that shot would not head down.
    private Vec3 strikeSpot(HeldShot shot, Entity mover, Vec3 head, double fall) {
        Vec3 muzzle = mc.player.getEyePosition().subtract(0, ProjectilePath.BELOW_EYES, 0)
            .subtract(mover.position());
        Vec3 own = shot.thrown() ? Vec3.ZERO : shot.releaseVelocity();
        double down = fall - own.y;
        if (down < LEAST_DOWN) {
            return null;
        }
        // The ticks the shot takes from the muzzle down to the top of the head.
        double reach = (HEAD_GAP + muzzle.y) / down;
        return new Vec3(head.x - muzzle.x - own.x * reach, head.y + HEAD_GAP, head.z - muzzle.z - own.z * reach);
    }

    // Runs straight after the drop reaches the server.
    private void fire(HeldShot shot) {
        if (shot.thrown()) {
            shot.fireAimed(mc.player.getYRot(), RotationManager.STRAIGHT_DOWN, throwsEach.getInt());
        } else {
            shot.fire();
        }
    }

    // A trip cut short before the shot lets it go as it was. A player who left takes
    // nothing along.
    private void landed(Hop.Result result) {
        if (result != Hop.Result.LEFT) {
            held.finish();
        }
        held = null;
        if (result != Hop.Result.MOVED) {
            warning.say(result.problem());
        }
    }
}
