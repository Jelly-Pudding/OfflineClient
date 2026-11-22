package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.GlideSwitch;
import com.jellypudding.offlineclient.util.Leave;
import com.jellypudding.offlineclient.util.MovementUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// It assumes a clear line to the target.
public final class AutoWasp extends Module {

    public enum Who { NEAREST, NAME }

    public enum OnLoss { SWITCH_OFF, NEW_TARGET, DISCONNECT }

    public enum Approach { STRAIGHT, DIVE }

    private static final int OPEN_DELAY = 4;

    private static final double LANDED = 0.25;

    private static final double HOVER = 1.25;

    private static final double TURN_SLACK = 0.5;

    private static final double TINY = 1.0E-5;

    private final EnumSetting<Who> who = new EnumSetting<>("Target",
        "Who to fly at.", Who.NEAREST)
        .describe(Who.NEAREST, "Flies at the closest player.")
        .describe(Who.NAME, "Flies at the player whose name you type in.");
    private final TextSetting name = new TextSetting("Name",
        "The account name of the player to fly at.", "")
        .under(who, Who.NAME);
    private final BoolSetting onlyFriends = new BoolSetting("Only friends",
        "Only follows players on your friend list.", false)
        .under(who, Who.NEAREST);
    private final NumberSetting horizontalSpeed = new NumberSetting("Horizontal speed",
        "Blocks a tick along the ground.", 2, 0.1, 10, 0.1, " blocks").min(0);
    private final NumberSetting verticalSpeed = new NumberSetting("Vertical speed",
        "Blocks a tick up or down.", 3, 0.1, 10, 0.1, " blocks").min(0);
    private final EnumSetting<Approach> approach = new EnumSetting<>("Approach",
        "How you close in on the target.", Approach.STRAIGHT)
        .describe(Approach.STRAIGHT, "Flies straight at the target.")
        .describe(Approach.DIVE, "Climbs high over the target then dives at it and pulls up again.");
    private final NumberSetting perchHeight = new NumberSetting("Perch height",
        "How far above the target you climb before each dive. The dive starts once the target is this close.",
        18, 4, 60, 1, " blocks")
        .under(approach, Approach.DIVE);
    private final NumberSetting pullUpHeight = new NumberSetting("Pull up height",
        "How far above the target each dive turns back up.", 3, 1, 20, 0.5, " blocks")
        .under(approach, Approach.DIVE);
    private final NumberSetting diveSpeed = new NumberSetting("Dive speed",
        "Blocks a tick down during a dive.", 1.5, 0.1, 4, 0.1, " blocks")
        .under(approach, Approach.DIVE);
    private final BoolSetting avoidLanding = new BoolSetting("Avoid landing",
        "Hovers above a target on the ground to keep your glide going.", true)
        .under(approach, Approach.STRAIGHT);
    private final BoolSetting predictMovement = new BoolSetting("Predict movement",
        "Aims where the target is heading rather than where they are.", true);
    private final EnumSetting<OnLoss> onLoss = new EnumSetting<>("On target loss",
        "What happens when the target vanishes.", OnLoss.SWITCH_OFF)
        .describe(OnLoss.SWITCH_OFF, "Switches the module off.")
        .describe(OnLoss.NEW_TARGET, "Looks for a target again and switches off when there is none.")
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
    private boolean diving;

    public AutoWasp() {
        super("AutoWasp", "Flies you at a player on your elytra.", Category.MOVEMENT);
        addSettings(who, name, onlyFriends, horizontalSpeed, verticalSpeed, approach, perchHeight, pullUpHeight,
            diveSpeed, avoidLanding, predictMovement, onLoss, offsetX, offsetY, offsetZ);
        searchTags("wasp", "elytra chase", "follow", "dive");
    }

    @Override
    public String getSuffix() {
        return EntityUtil.displayNameOf(target);
    }

    // The target it chased belongs to the session it was chosen in.
    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        jumpTimer = 0;
        jumped = false;
        diving = false;
        if (!pickTarget()) {
            disable(noTarget());
        }
    }

    @Override
    protected void onDisable() {
        target = null;
    }

    // Read by LivingEntityMixin. Null whilst the module has nothing to steer.
    public Vec3 glideVelocity() {
        if (target == null || !inGame() || !mc.player.isFallFlying() || !MovementUtil.wearsGlider()) {
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
            double vertical = diving ? diveSpeed.getValue() : verticalSpeed.getValue();
            vy = Math.signum(dy) * Math.min(Math.abs(dy), vertical);
        }
        return new Vec3(vx, vy, vz);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        // A player from a world since left is never marked as removed.
        if (target == null || target.isRemoved() || target.level() != mc.level) {
            targetLost();
            if (!isEnabled()) {
                return;
            }
        }
        if (!MovementUtil.wearsGlider()) {
            return;
        }
        if (mc.player.isFallFlying()) {
            jumped = false;
            jumpTimer = 0;
            turnDive();
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
            GlideSwitch.get().open();
        }
    }

    private void targetLost() {
        ChatUtil.message("AutoWasp lost its target.");
        switch (onLoss.getValue()) {
            case SWITCH_OFF -> setEnabled(false);
            case NEW_TARGET -> {
                if (!pickTarget()) {
                    disable(noTarget());
                }
            }
            case DISCONNECT -> {
                setEnabled(false);
                Leave.QUIT.go("AutoWasp lost its target.");
            }
        }
    }

    private boolean pickTarget() {
        target = (Player) EntityUtil.nearest(Double.MAX_VALUE, entity -> entity instanceof Player player
            && !player.isDeadOrDying() && player.getHealth() > 0 && wanted(player));
        if (target != null) {
            ChatUtil.message("AutoWasp is following " + EntityUtil.nameOf(target) + ".");
        }
        return target != null;
    }

    private String noTarget() {
        if (!who.is(Who.NAME)) {
            return "AutoWasp found no player to follow.";
        }
        return name.isBlank() ? "AutoWasp has no name to look for. Type one under Name."
            : "AutoWasp sees no player called " + name.getValue() + ".";
    }

    private boolean wanted(Player player) {
        if (who.is(Who.NAME)) {
            return name.getValue().equalsIgnoreCase(EntityUtil.nameOf(player));
        }
        return !onlyFriends.isOn() || EntityUtil.isFriend(player);
    }

    private void turnDive() {
        if (!approach.is(Approach.DIVE)) {
            diving = false;
            return;
        }
        Vec3 spot = targetSpot();
        double above = mc.player.getY() - spot.y;
        double away = Math.hypot(spot.x - mc.player.getX(), spot.z - mc.player.getZ());
        if (diving && above <= pullUpHeight.getValue() + TURN_SLACK) {
            diving = false;
        } else if (!diving && above >= perchHeight.getValue() - TURN_SLACK && away <= perchHeight.getValue()) {
            diving = true;
        }
    }

    private Vec3 aimPoint() {
        Vec3 aim = targetSpot();
        if (approach.is(Approach.DIVE)) {
            return aim.add(0, diving ? pullUpHeight.getValue() : perchHeight.getValue(), 0);
        }
        return avoidLanding.isOn() ? hoverAbove(aim) : aim;
    }

    private Vec3 targetSpot() {
        Vec3 aim = target.position().add(offsetX.getValue(), offsetY.getValue(), offsetZ.getValue());
        return predictMovement.isOn() ? aim.add(target.getDeltaMovement()) : aim;
    }

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
