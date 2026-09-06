package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// Flies straight at a chosen player on an elytra. It assumes a clear line.
public final class AutoWasp extends Module {

    public enum OnLoss { SWITCH_OFF, NEW_TARGET, DISCONNECT }

    // Ticks after the jump before the wings open.
    private static final int OPEN_DELAY = 4;

    // How close to the block top the target must stand to count as landed.
    private static final double LANDED = 0.25;

    // Height above a landed target to hover at.
    private static final double HOVER = 1.25;

    private static final double TINY = 1.0E-5;

    private final NumberSetting horizontalSpeed = new NumberSetting("Horizontal speed",
        "Blocks a tick along the ground.", 2, 0.1, 10, 0.1, " blocks").min(0);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Blocks a tick up or down.", 3, 0.1, 10, 0.1, " blocks").min(0);
    private final BoolSetting avoidLanding = new BoolSetting("Avoid landing",
        "Hovers just above a target that stands on the ground so you keep gliding.", true);
    private final BoolSetting predictMovement = new BoolSetting("Predict movement",
        "Aims where the target is heading rather than where they are.", true);
    private final BoolSetting onlyFriends = new BoolSetting("Only friends",
        "Only follows players on your friend list.", false);
    private final EnumSetting<OnLoss> onLoss = new EnumSetting<>("On target loss",
        "What happens when the target vanishes.", OnLoss.SWITCH_OFF)
        .describe(OnLoss.SWITCH_OFF, "Switches the module off.")
        .describe(OnLoss.NEW_TARGET, "Picks the nearest player as the new target.")
        .describe(OnLoss.DISCONNECT, "Leaves the server.");
    private final NumberSetting offsetX = new NumberSetting("Offset x",
        "Blocks east of the target to aim at.", 0, -10, 10, 0.5, " blocks");
    private final NumberSetting offsetY = new NumberSetting("Offset y",
        "Blocks above the target to aim at.", 0, -10, 10, 0.5, " blocks");
    private final NumberSetting offsetZ = new NumberSetting("Offset z",
        "Blocks south of the target to aim at.", 0, -10, 10, 0.5, " blocks");

    private Player target;
    private int jumpTimer;
    private boolean jumped;

    public AutoWasp() {
        super("AutoWasp", "Flies you straight at a player on your elytra.", Category.MOVEMENT);
        addSettings(horizontalSpeed, verticalSpeed, avoidLanding, predictMovement, onlyFriends,
            onLoss, offsetX, offsetY, offsetZ);
        searchTags("wasp", "elytra chase", "follow");
    }

    @Override
    public String getSuffix() {
        return target == null ? null : EntityUtil.nameOf(target);
    }

    @Override
    protected void onEnable() {
        jumpTimer = 0;
        jumped = false;
        if (!pickTarget()) {
            ChatUtil.error("AutoWasp found no player to follow.");
            setEnabled(false);
        }
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    // Read by LivingEntityMixin. The glide velocity that heads for the target
    // or null whilst the module has nothing to steer.
    public Vec3 glideVelocity() {
        if (target == null || !inGame() || !mc.player.isFallFlying() || !wearingWings()) {
            return null;
        }
        Vec3 aim = aimPoint();
        double dx = aim.x - mc.player.getX();
        double dz = aim.z - mc.player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        double along = Math.min(flat, horizontalSpeed.getValue());
        double vx = flat > TINY ? dx / flat * along : 0;
        double vz = flat > TINY ? dz / flat * along : 0;
        double dy = aim.y - mc.player.getY();
        double vy = 0;
        if (Math.abs(dy) > TINY) {
            vy = Math.signum(dy) * Math.min(Math.abs(dy), verticalSpeed.getValue());
        }
        return new Vec3(vx, vy, vz);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (target == null || target.isRemoved()) {
            targetLost();
            if (!isEnabled()) {
                return;
            }
        }
        if (!wearingWings()) {
            return;
        }
        if (mc.player.isFallFlying()) {
            jumped = false;
            jumpTimer = 0;
            return;
        }
        if (mc.player.onGround()) {
            mc.player.jumpFromGround();
            jumped = true;
            jumpTimer = 0;
            return;
        }
        if (!jumped) {
            return;
        }
        // The server refuses a glide start before the jump has cleared the ground.
        if (++jumpTimer >= OPEN_DELAY) {
            jumpTimer = 0;
            mc.player.setJumping(false);
            mc.player.setSprinting(true);
            ElytraFly.sendStartGlide();
        }
    }

    private void targetLost() {
        ChatUtil.message("AutoWasp lost its target.");
        switch (onLoss.getValue()) {
            case SWITCH_OFF -> setEnabled(false);
            case NEW_TARGET -> {
                if (!pickTarget()) {
                    ChatUtil.error("AutoWasp found no new player to follow.");
                    setEnabled(false);
                }
            }
            case DISCONNECT -> mc.player.connection.getConnection()
                .disconnect(Component.literal("§b[§3Offline§b] §fAutoWasp lost its target."));
        }
    }

    private boolean pickTarget() {
        target = (Player) EntityUtil.nearest(Double.MAX_VALUE, entity -> entity instanceof Player player
            && !player.isDeadOrDying() && player.getHealth() > 0
            && (!onlyFriends.isOn() || EntityUtil.isFriend(player)));
        if (target != null) {
            ChatUtil.message("AutoWasp is following " + EntityUtil.nameOf(target) + ".");
        }
        return target != null;
    }

    private boolean wearingWings() {
        return LivingEntity.canGlideUsing(mc.player.getItemBySlot(EquipmentSlot.CHEST),
            EquipmentSlot.CHEST);
    }

    private Vec3 aimPoint() {
        Vec3 aim = target.position().add(offsetX.getValue(), offsetY.getValue(), offsetZ.getValue());
        if (predictMovement.isOn()) {
            aim = aim.add(target.getDeltaMovement());
        }
        if (avoidLanding.isOn()) {
            aim = hoverAbove(aim);
        }
        return aim;
    }

    // A target standing on a block gets a hover point a little above it.
    private Vec3 hoverAbove(Vec3 aim) {
        double half = target.getBbWidth() / 2;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Vec3 corner = aim.relative(side, half).relative(side.getClockWise(), half);
            BlockPos below = BlockPos.containing(corner).below();
            boolean solid = !BlockUtil.state(below).getCollisionShape(mc.level, below).isEmpty();
            if (solid && Math.abs(aim.y - (below.getY() + 1)) <= LANDED) {
                return new Vec3(aim.x, below.getY() + HOVER, aim.z);
            }
        }
        return aim;
    }
}
