package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.EntityFilter;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.FaceMode;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.HotbarLoan;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.MovementUtil;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SprintPause;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetFilter;
import com.jellypudding.offlineclient.util.TargetPriority;
import com.jellypudding.offlineclient.util.WindLaunch;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

// Launches off a wind charge beside the target and smashes it with a mace on the way
// down. The server scales the smash by the fall it watched. The hit waits for the last
// tick before the ground or before the target slips out of reach.
public final class MaceCombo extends Module {

    // Room the launch needs above your head. A lower ceiling cuts the fall short.
    private static final double HEADROOM = 4;

    // A combo still in the air after this long has missed.
    private static final int GIVE_UP_TICKS = 80;

    // Steering stops this close. Nearer only pushes you into the target.
    private static final double STEER_STOP = 1.5;

    // How far a new target is looked for once the first one is gone mid air.
    private static final double AIR_SCAN = 16;

    private static final Predicate<ItemStack> MACE = stack -> stack.getItem() instanceof MaceItem;

    private final NumberSetting range = new NumberSetting("Range",
        "How close a target has to be before the launch.", 3, 1, 6, 0.25, " blocks").min(0.5);
    private final EnumSetting<TargetPriority> priority =
        TargetPriority.setting("Smashes", TargetPriority.NEAREST);
    private final EntityFilter filter = EntityFilter.living("Smash", "smashed", true,
        EntityFilter.Pick.NONE, List.of());
    private final TargetFilter targets = new TargetFilter();
    private final BoolSetting steer = new BoolSetting("Steer",
        "Pulls you towards the target in the air to keep it in reach when you come down.", true);
    private final BoolSetting onlyOnClick = new BoolSetting("Only on click",
        "Only launches whilst you hold the attack key down.", false);
    private final BoolSetting fromInventory = new BoolSetting("Take from inventory",
        "Also takes the mace and wind charges from the rest of your inventory and puts them back afterwards.",
        true);
    private final EnumSetting<FaceMode> faceTarget = FaceMode.setting(FaceMode.SPAM);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);

    private final WindLaunch launch = new WindLaunch();
    private final HotbarLoan loan = new HotbarLoan();
    private final SprintPause sprintPause = new SprintPause();
    private LivingEntity target;
    private boolean airborne;
    private int airTicks;

    public MaceCombo() {
        super("MaceCombo", "Launches you off a wind charge and lands a mace smash on the target as you fall.",
            Category.COMBAT);
        addSettings(range, priority);
        addSettings(filter.settings());
        addSettings(targets.settings());
        addSettings(steer, onlyOnClick, fromInventory, faceTarget, swing);
        searchTags("mace", "wind charge", "smash", "combo");
    }

    @Override
    public String getSuffix() {
        return target == null ? null : target.getName().getString();
    }

    // The modules that hit or swap weapons for you hold off whilst this is true. Their hit would spend the
    // cooldown or end the fall early and a swap would take the mace away.
    public boolean isAirborne() {
        return airborne || launch.busy();
    }

    @Override
    protected void onEnable() {
        airborne = false;
        target = null;
    }

    @Override
    protected void onDisable() {
        finish();
        launch.stop();
        sprintPause.resume();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || mc.player.isDeadOrDying()) {
            loan.forget();
            launch.stop();
            airborne = false;
            target = null;
            return;
        }
        if (launch.tick()) {
            takeMace();
            airborne = true;
            airTicks = 0;
            return;
        }
        if (airborne) {
            fly();
        } else if (!launch.busy()) {
            tryLaunch();
        }
    }

    private void tryLaunch() {
        if (mc.gui.screen() != null || Modules.feedersPauseCombat()
            || (onlyOnClick.isOn() && !mc.options.keyAttack.isDown())) {
            return;
        }
        if (maceSlot() == -1 || !WindLaunch.canThrow(fromInventory.isOn()) || !roomAbove()) {
            return;
        }
        LivingEntity picked = pick(range.getValue());
        if (picked != null && launch.start(fromInventory.isOn())) {
            target = picked;
        }
    }

    private boolean roomAbove() {
        return mc.level.noCollision(mc.player, mc.player.getBoundingBox().expandTowards(0, HEADROOM, 0));
    }

    // The hotbar comes first and the mace already in hand before that.
    private int maceSlot() {
        int slot = InventoryUtil.hotbarSlot(MACE);
        if (slot == -1 && fromInventory.isOn()) {
            slot = InventoryUtil.findSlot(MACE, InventoryUtil.WHOLE_INVENTORY);
        }
        return slot;
    }

    // The damage from the held item only counts once the server has ticked with it.
    // The mace goes up whilst you rise.
    private void takeMace() {
        if (loan.select(maceSlot())) {
            mc.gameMode.ensureHasSentCarriedItem();
        }
    }

    private void fly() {
        if (mc.player.onGround() || mc.player.isInWater() || ++airTicks > GIVE_UP_TICKS) {
            finish();
            return;
        }
        if (target == null || !wanted(target)) {
            target = pick(AIR_SCAN);
        }
        if (target == null) {
            return;
        }
        if (steer.isOn()) {
            steerTowards(target);
        }
        Vec3 velocity = mc.player.getDeltaMovement();
        if (velocity.y >= 0 || mc.player.fallDistance <= MaceItem.SMASH_ATTACK_FALL_THRESHOLD
            || !MACE.test(mc.player.getMainHandItem())) {
            return;
        }
        double reach = mc.player.entityInteractionRange();
        if (EntityUtil.reachDistance(mc.player, target) <= reach && lastChance(target, velocity, reach)) {
            smash(target);
        }
    }

    // The fall is longest on the last tick before the ground or before the target slips out
    // of reach. The hit goes out before this tick's move and the server measures it there.
    private boolean lastChance(LivingEntity target, Vec3 velocity, double reach) {
        if (!mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(0, velocity.y, 0))) {
            return true;
        }
        Vec3 nextEye = mc.player.getEyePosition().add(velocity);
        AABB nextBox = target.getBoundingBox().move(EntityUtil.velocityOf(target));
        return nextBox.distanceToSqr(nextEye) > reach * reach;
    }

    // Air control alone is too weak to follow anyone. The pace never passes a sprint.
    private void steerTowards(LivingEntity target) {
        Vec3 offset = target.position().subtract(mc.player.position());
        double distance = offset.horizontalDistance();
        if (distance <= STEER_STOP) {
            return;
        }
        double pace = Math.min(distance - STEER_STOP,
            MovementUtil.withSpeedEffects(mc.player, MovementUtil.SPRINT_SPEED));
        mc.player.setDeltaMovement(offset.x / distance * pace, mc.player.getDeltaMovement().y,
            offset.z / distance * pace);
    }

    // A sprinting player never lands a critical hit and a critical hit multiplies the smash.
    private void smash(LivingEntity victim) {
        faceTarget.getValue().face(victim.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        sprintPause.pause();
        mc.gameMode.attack(mc.player, victim);
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        finish();
    }

    private void finish() {
        airborne = false;
        target = null;
        loan.release();
    }

    // Within is measured to the hitbox the way reach is.
    private LivingEntity pick(double within) {
        return (LivingEntity) EntityUtil.bestInReach(within, priority.getValue(), this::wanted);
    }

    private boolean wanted(Entity entity) {
        return targets.attackable(entity, filter);
    }

    // The attack packet has gone out by this point.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        sprintPause.resume();
    }
}
