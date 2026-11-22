package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.modules.movement.ElytraBoost;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

// The server flies a copy of every gliding player by itself. It keeps a velocity of its own
// for the copy and each tick steps it on from wherever the player last said they were and
// then puts it back. A crash into a wall is charged from that step alone and the speed the
// player really flies at plays no part. This keeps the same velocity by the game's own
// arithmetic to show a module the step before the server takes it.
public final class GlideMirror {

    // No glide carries the copy further than this along any axis in one tick.
    public static final double FARTHEST = 4;

    // A step cut short sideways costs the speed it lost times ten less three.
    private static final double CRASH_SCALE = 10;
    private static final double CRASH_FREE = 3;

    // The most speed a step can lose for nothing.
    public static final double FREE_LOSS = CRASH_FREE / CRASH_SCALE;

    // The server holds another look than the camera's once the two differ by more than this.
    private static final float LOOK_SLACK = 0.01F;

    // A rocket adds this along the look of whoever it rides and pulls their velocity
    // half the way to this speed along it.
    private static final double ROCKET_PUSH = 0.1;
    private static final double ROCKET_SPEED = 1.5;
    private static final double ROCKET_PULL = 0.5;

    // How many ticks of the copy's flight under the present look count as still to come and
    // the change of speed under which it has settled before that. A module keeps the player
    // out of reach of those steps and of none beyond them. Half a second lets a player who
    // has just taken off fly up to things whilst the copy is slow and gives room to move
    // them back as it gathers speed. A long count would hold them as far off from the first
    // tick as a long glide does. The price is that a fall the server gave the copy and this
    // missed is only seen once it has turned into speed.
    private static final int HORIZON = SharedConstants.TICKS_PER_SECOND / 2;
    private static final double SETTLED = 1.0E-4;

    private static GlideMirror instance;

    private final GlideSwitch glide = GlideSwitch.get();
    private final Lagback.Watcher lagback = new Lagback.Watcher();

    private Vec3 velocity = Vec3.ZERO;
    // A velocity the server sent for the player. It is the copy's own. Set on the netty thread.
    private final AtomicReference<Vec3> told = new AtomicReference<>();
    // What explosions have pushed the copy by since the last tick. A blast that does no
    // damage sends its push with the blast alone. Set on the netty thread.
    private final AtomicReference<Vec3> blasts = new AtomicReference<>(Vec3.ZERO);
    private boolean grounded;
    // True whilst the server holds a look some module sent it in place of the camera's.
    private boolean lookHeld;
    // The spin of a riptide throw counts down from its start. A count that has gone up
    // since the last tick is a fresh throw.
    private int spin;

    // One step of the copy. The speed over the ground it had going in and the way it moves.
    public record Step(double speedBefore, Vec3 movement) {

        // What the server would charge for the step from a place. Nought or less is no crash.
        // The copy is granted no climb onto a low edge here. It climbs only on a tick it
        // lands and skimming low it lands on some ticks and not on others.
        public double damage(LocalPlayer player, AABB box) {
            Vec3 kept = kept(swept(player, box));
            boolean sideways = kept.x != movement.x || kept.z != movement.z;
            return sideways ? (speedBefore - kept.horizontalDistance()) * CRASH_SCALE - CRASH_FREE : 0;
        }

        public boolean canHurt() {
            return speedBefore * CRASH_SCALE > CRASH_FREE;
        }

        public boolean blocked(LocalPlayer player, AABB box) {
            return cutSideways(swept(player, box));
        }

        private Vec3 after(LocalPlayer player, AABB box) {
            return kept(travelled(player, box));
        }

        private Vec3 swept(LocalPlayer player, AABB box) {
            Level level = player.level();
            return Entity.collideBoundingBox(player, movement, box, level,
                level.getEntityCollisions(player, box.expandTowards(movement)));
        }

        // How far the step really gets. A copy that lands may climb a low edge the way
        // anything that walks does.
        private Vec3 travelled(LocalPlayer player, AABB box) {
            Vec3 got = swept(player, box);
            boolean landed = movement.y < 0 && got.y != movement.y;
            if (!landed || !cutSideways(got)) {
                return got;
            }
            Vec3 climb = new Vec3(movement.x, player.getAttributeValue(Attributes.STEP_HEIGHT), movement.z);
            Vec3 up = Entity.collideBoundingBox(player, climb, box.move(0, got.y, 0), player.level(), List.of());
            return up.horizontalDistanceSqr() > got.horizontalDistanceSqr() ? new Vec3(up.x, got.y, up.z) : got;
        }

        private boolean cutSideways(Vec3 got) {
            return !Mth.equal(movement.x, got.x) || !Mth.equal(movement.z, got.z);
        }

        // The server stops the copy dead along every axis a block cut short. The game tells
        // a cut sideways with a little slack and a cut up or down with none (Entity.move)
        // and this reads them the same way.
        private Vec3 kept(Vec3 got) {
            return new Vec3(Mth.equal(movement.x, got.x) ? movement.x : 0, movement.y == got.y ? movement.y : 0,
                Mth.equal(movement.z, got.z) ? movement.z : 0);
        }
    }

    // The steps of the copy over the ticks still to come whilst the player keeps this look.
    // The one it takes next and the fastest and the last of them. One step can be two of them.
    public record Ahead(Step next, Step fastest, Step last) {

        public boolean canHurt() {
            return fastest.canHurt();
        }

        public boolean hurts(LocalPlayer player, AABB box) {
            return Stream.of(next, fastest, last).distinct().anyMatch(step -> step.damage(player, box) > 0);
        }
    }

    private GlideMirror() {
        OfflineClient.INSTANCE.getEventBus().register(this);
    }

    public static synchronized GlideMirror get() {
        if (instance == null) {
            instance = new GlideMirror();
        }
        return instance;
    }

    public Ahead ahead(LocalPlayer player) {
        int rockets = rockets(player);
        Step next = step(player, velocity, rockets);
        Step fastest = next;
        Step last = next;
        for (int tick = 0; tick < HORIZON; tick++) {
            Step step = step(player, last.movement(), rockets);
            boolean settled = Math.abs(step.speedBefore() - last.speedBefore()) < SETTLED
                && Math.abs(step.movement().y - last.movement().y) < SETTLED;
            last = step;
            if (step.speedBefore() > fastest.speedBefore()) {
                fastest = step;
            }
            if (settled) {
                break;
            }
        }
        return new Ahead(next, fastest, last);
    }

    // The look the server flies the copy along. The camera's own unless some module holds
    // another one there.
    private float yaw(LocalPlayer player) {
        return lookHeld ? RotationManager.serverYaw() : player.getYRot();
    }

    private float pitch(LocalPlayer player) {
        return lookHeld ? RotationManager.serverPitch() : player.getXRot();
    }

    public Vec3 levelLook(LocalPlayer player) {
        return Vec3.directionFromRotation(0, yaw(player));
    }

    // The rockets tick ahead of the player and the glide follows. The game's own arithmetic
    // reads the look off the player and is lent the server's for the length of the call.
    private Step step(LocalPlayer player, Vec3 from, int rockets) {
        float ownYaw = player.getYRot();
        float ownPitch = player.getXRot();
        player.setYRot(yaw(player));
        player.setXRot(pitch(player));
        try {
            Vec3 look = player.getLookAngle();
            for (int i = 0; i < rockets; i++) {
                Vec3 pull = look.scale(ROCKET_SPEED).subtract(from).scale(ROCKET_PULL);
                from = from.add(look.scale(ROCKET_PUSH)).add(pull);
            }
            return new Step(from.horizontalDistance(), player.updateFallFlyingMovement(from));
        } finally {
            player.setYRot(ownYaw);
            player.setXRot(ownPitch);
        }
    }

    // The rockets that push the copy too. One that exists on this client alone does not.
    private static int rockets(LocalPlayer player) {
        ElytraBoost boost = Modules.get(ElytraBoost.class);
        int count = 0;
        for (Entity entity : OfflineClient.MC.level.entitiesForRendering()) {
            if (entity instanceof FireworkRocketEntity rocket && rocket.attachedToEntity == player
                && (boost == null || !boost.isFake(rocket))) {
                count++;
            }
        }
        return count;
    }

    // The copy steps from the position the tick has just sent. Last of all because a glide
    // command sent behind that position decides which way it steps.
    @Subscribe(priority = Subscribe.LAST)
    private void onPostMotion(PostMotionEvent event) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null || OfflineClient.MC.level == null) {
            return;
        }
        lookHeld = Mth.degreesDifferenceAbs(RotationManager.serverYaw(), player.getYRot()) > LOOK_SLACK
            || Math.abs(RotationManager.serverPitch() - player.getXRot()) > LOOK_SLACK;
        Vec3 heard = told.getAndSet(null);
        if (heard != null) {
            velocity = heard;
        }
        velocity = velocity.add(blasts.getAndSet(Vec3.ZERO)).add(riptide(player));
        // A pull back stops the copy dead.
        if (lagback.happened()) {
            velocity = Vec3.ZERO;
        }
        boolean ground = player.onGround();
        if (glide.glides()) {
            velocity = step(player, velocity, rockets(player)).after(player, player.getBoundingBox());
        } else {
            velocity = fallen(player, ground);
        }
        grounded = ground;
    }

    // A riptide throw pushes the player along their look on both sides and the server sends
    // no velocity for it. The push is the game's own (TridentItem.releaseUsing).
    private Vec3 riptide(LocalPlayer player) {
        int spinning = player.autoSpinAttackTicks;
        boolean thrown = spinning > spin;
        spin = spinning;
        if (!thrown) {
            return Vec3.ZERO;
        }
        float strength = Math.max(EnchantmentHelper.getTridentSpinAttackStrength(player.getMainHandItem(), player),
            EnchantmentHelper.getTridentSpinAttackStrength(player.getOffhandItem(), player));
        return Vec3.directionFromRotation(pitch(player), yaw(player)).scale(strength);
    }

    // Out of a glide the copy falls from where the player is with no keys held and whatever it
    // lands on or runs into stops it. The server jumps it when the player leaves the ground
    // upwards and both then rise alike.
    private Vec3 fallen(LocalPlayer player, boolean ground) {
        double climb = player.getDeltaMovement().y;
        if (grounded && !ground && climb > 0) {
            return new Vec3(velocity.x * LivingEntity.BASE_HORIZONTAL_AIR_DRAG, climb,
                velocity.z * LivingEntity.BASE_HORIZONTAL_AIR_DRAG);
        }
        // A ladder or a ride holds the copy as it holds the player.
        if (player.onClimbable() || player.isPassenger()) {
            return Vec3.ZERO;
        }
        // Water slows the copy and lets it keep no fall. Along the ground it keeps the share of
        // a sprint at the most. A riptide thrown under water is not spent by the time the player
        // is out of it.
        if (player.isInLiquid()) {
            return velocity.multiply(LivingEntity.SPRINTING_WATER_DRAG, LivingEntity.WATER_DRAG,
                LivingEntity.SPRINTING_WATER_DRAG);
        }
        Vec3 kept = new Step(velocity.horizontalDistance(), velocity).after(player, player.getBoundingBox());
        double drag = LivingEntity.BASE_HORIZONTAL_AIR_DRAG;
        if (velocity.y < 0 && kept.y == 0) {
            BlockPos under = player.getBlockPosBelowThatAffectsMyMovement();
            drag *= player.level().getBlockState(under).getBlock().getFriction();
        }
        return new Vec3(kept.x * drag, (kept.y - player.getGravity()) * LivingEntity.BASE_VERTICAL_AIR_DRAG,
            kept.z * drag);
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        Packet<?> packet = event.getPacket();
        LocalPlayer player = OfflineClient.MC.player;
        if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            tell(Vec3.ZERO);
        } else if (player != null && packet instanceof ClientboundSetEntityMotionPacket motion
            && motion.id() == player.getId()) {
            tell(motion.movement());
        } else if (packet instanceof ClientboundExplodePacket blast) {
            blast.playerKnockback().ifPresent(push -> blasts.accumulateAndGet(push, Vec3::add));
        }
    }

    // A velocity from the server has every blast before it in it already. Only a blast heard
    // after it goes on top.
    private void tell(Vec3 movement) {
        told.set(movement);
        blasts.set(Vec3.ZERO);
    }
}
