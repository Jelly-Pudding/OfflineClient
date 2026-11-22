package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.RightClickEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Hands;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class ArrowDodge extends Module {

    private static final double DIAGONAL = Math.sqrt(0.5);

    private static final Vec3[] DIRECTIONS = {
        new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1),
        new Vec3(DIAGONAL, 0, DIAGONAL), new Vec3(-DIAGONAL, 0, DIAGONAL),
        new Vec3(DIAGONAL, 0, -DIAGONAL), new Vec3(-DIAGONAL, 0, -DIAGONAL)
    };

    private static final int GROWTH_TRIES = 6;

    // The server refuses a hit past about six blocks.
    private static final double HIT_REACH = 6;

    // Ticks before the same projectile is hit again. The server's word on where a punched one
    // went takes a moment to come back.
    private static final int HIT_AGAIN_TICKS = 5;

    // Ticks a raised shield stays up after the last threat. A flight that grazes the edge of the
    // box comes and goes from one tick to the next.
    private static final int SHIELD_HOLD_TICKS = 3;

    // Runs before the modules that act on a click.
    private static final int BEFORE_CLICKS = 100;

    private record Segment(Vec3 from, Vec3 to) {
    }

    public enum Move { VELOCITY, PACKET }

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a projectile is still watched.", 32, 8, 64, 4, " blocks").min(1);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 40, 5, 120, 5, " ticks").min(1);
    private final NumberSetting margin = new NumberSetting("Margin",
        "Extra space kept around you.", 0.4, 0, 2, 0.1, " blocks");
    private final EnumSetting<Move> move = new EnumSetting<>("Move",
        "How the sidestep is made.", Move.VELOCITY)
        .describe(Move.VELOCITY, "Pushes you sideways and lets the game carry you.")
        .describe(Move.PACKET, "Steps sideways at once with a position packet.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How far each sidestep goes.", 0.35, 0.05, 1.5, 0.05, " blocks").min(0.01);
    private final RegistryListSetting<EntityType<?>> projectiles = new RegistryListSetting<>("Projectiles",
        "Kinds of projectile that are dodged or blocked. Click to pick them.", BuiltInRegistries.ENTITY_TYPE,
        List.of(EntityTypes.ARROW, EntityTypes.SPECTRAL_ARROW, EntityTypes.TRIDENT))
        .only(type -> EntityUtil.builds(type, Projectile.class));
    private final BoolSetting shield = new BoolSetting("Block with shield",
        "Raises a shield held in either hand against a projectile about to hit you and keeps it up until the"
            + " projectile has gone. One the shield cannot stop in time is still stepped from.", false);
    private final BoolSetting faceIt = new BoolSetting("Face it",
        "Turns you towards the projectile on the server side whilst the shield is up. A shield only stops what"
            + " comes from the front.", true).under(shield);
    private final BoolSetting hitBack = new BoolSetting("Hit back",
        "Punches fireballs and wind charges back and breaks shulker bullets before they reach you.", true);
    private final NumberSetting hitRange = new NumberSetting("Hit range",
        "How close a fireball or bullet comes before it is hit.", 5, 3, HIT_REACH, 0.5, " blocks")
        .min(0.5).max(HIT_REACH).under(hitBack);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH).under(hitBack);
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own",
        "Never dodges what you fired yourself.", true);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Never dodges what a friend fired.", true);
    private final BoolSetting groundCheck = new BoolSetting("Ground check",
        "Only steps onto blocks that will hold you up.", true);
    private final BoolSetting draw = new BoolSetting("Draw paths",
        "Shows the predicted flight of every watched projectile.", false);

    private final List<Segment> paths = new ArrayList<>();
    private final List<Segment> threats = new ArrayList<>();
    private final Cooldowns<Integer> struck = new Cooldowns<>();
    private boolean dodging;
    private boolean raised;
    private int holdFor;
    // True once Face it asked for a turn this tick. The angle the server heard shows on the next
    // tick whether a module that asked later took the turn over.
    private boolean turned;

    public ArrowDodge() {
        super("ArrowDodge", "Sidesteps arrows and other projectiles aimed at you or blocks them.", Category.COMBAT);
        addSettings(range, steps, margin, move, speed, projectiles, shield, faceIt, hitBack, hitRange, swing,
            ignoreOwn, ignoreFriends, groundCheck, draw);
        searchTags("dodge", "arrow", "projectile", "anti projectile", "fireball", "shulker bullet", "shield",
            "auto shield", "block");
    }

    @Override
    public String getSuffix() {
        if (raised) {
            return "blocking";
        }
        return dodging ? "dodging" : null;
    }

    @Override
    protected void onDisable() {
        paths.clear();
        threats.clear();
        struck.clear();
        dodging = false;
        lower();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        paths.clear();
        threats.clear();
        dodging = false;
        boolean turnedBefore = turned;
        turned = false;
        if (Hands.tied()) {
            lower();
            return;
        }
        struck.tick();
        // Something else took the hand or wants it. The shield comes down at once.
        if (raised && (!mc.player.isUsingItem() || !mayRaise())) {
            lower();
        }

        AABB body = mc.player.getBoundingBox().inflate(margin.getValue());
        double watch = range.getValue();
        boolean punches = hitBack.isOn() && ItemUtil.landsPlainHit(mc.player.getMainHandItem());
        Projectile soonest = null;
        int soonestTick = Integer.MAX_VALUE;
        Vec3 soonestFrom = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || !watches(projectile)) {
                continue;
            }
            if (mc.player.distanceTo(projectile) > watch) {
                continue;
            }
            List<Segment> flight = predict(projectile);
            paths.addAll(flight);
            if (punches && hittable(projectile)) {
                if (incoming(projectile, flight, body) && !struck.contains(projectile.getId())
                    && EntityUtil.reachDistance(mc.player, projectile) <= hitRange.getValue()) {
                    strike(projectile);
                }
                continue;
            }
            threats.addAll(flight);
            int impact = impactTick(flight, body);
            if (impact >= 0 && impact < soonestTick) {
                soonest = projectile;
                soonestTick = impact;
                soonestFrom = flight.get(impact).from();
            }
        }
        if (soonest == null) {
            if (raised && --holdFor > 0) {
                holdUp();
            } else {
                lower();
            }
            return;
        }
        if (block(soonest, soonestTick, soonestFrom, turnedBefore)) {
            return;
        }

        double push = speed.getValue();
        for (int attempt = 0; attempt < GROWTH_TRIES; attempt++) {
            for (Vec3 direction : DIRECTIONS) {
                Vec3 offset = direction.scale(push);
                AABB moved = body.move(offset);
                if (meets(threats, moved) || !isClear(offset)) {
                    continue;
                }
                step(offset);
                dodging = true;
                return;
            }
            push += speed.getValue();
        }
    }

    private boolean watches(Projectile projectile) {
        boolean kind = projectiles.contains(projectile.getType()) || hitBack.isOn() && hittable(projectile);
        if (!kind) {
            return false;
        }
        // A stuck arrow is not going anywhere.
        if (projectile.getDeltaMovement().lengthSqr() < 0.01) {
            return false;
        }
        Entity owner = projectile.getOwner();
        if (owner == null) {
            return true;
        }
        if (ignoreOwn.isOn() && owner == mc.player) {
            return false;
        }
        return !ignoreFriends.isOn() || !(owner instanceof Player player)
            || !EntityUtil.isFriend(player);
    }

    // A punch turns ghast fireballs and wind charges round and breaks a shulker bullet. Blaze
    // and dragon fireballs shrug it off. An arrow would get you kicked.
    private static boolean hittable(Projectile projectile) {
        return projectile instanceof ShulkerBullet
            || EntityUtil.typeIs(projectile, EntityTypeTags.REDIRECTABLE_PROJECTILE);
    }

    // A shulker bullet turns towards its target on the way. Any bullet closing in counts.
    private static boolean incoming(Projectile projectile, List<Segment> flight, AABB body) {
        if (projectile instanceof ShulkerBullet) {
            return projectile.getDeltaMovement().dot(body.getCenter().subtract(projectile.position())) > 0;
        }
        return meets(flight, body);
    }

    // The server sends a punched fireball or wind charge the way it thinks you look. Facing the
    // projectile sends it back the way it came.
    private void strike(Projectile projectile) {
        Vec3 centre = projectile.getBoundingBox().getCenter();
        RotationManager.glance(RotationManager.yawTo(centre), RotationManager.pitchTo(centre));
        mc.gameMode.attack(mc.player, projectile);
        swing.getValue().hit();
        struck.put(projectile.getId(), HIT_AGAIN_TICKS);
    }

    // A fireball keeps its heading and speed. Everything else slows in the air and falls.
    private List<Segment> predict(Projectile projectile) {
        List<Segment> flight = new ArrayList<>();
        double gravity = projectile.getGravity();
        double drag = projectile instanceof AbstractHurtingProjectile ? 1 : ProjectileUtil.AIR_DRAG;
        Vec3 pos = projectile.position();
        Vec3 velocity = projectile.getDeltaMovement();
        int limit = steps.getInt();
        int minY = mc.level.getMinY();

        for (int i = 0; i < limit; i++) {
            Vec3 previous = pos;
            pos = pos.add(velocity);
            velocity = velocity.scale(drag).subtract(0, gravity, 0);
            if (pos.y < minY) {
                break;
            }
            // A projectile that buries itself in a wall stops there.
            HitResult wall = mc.level.clip(new ClipContext(previous, pos,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            if (wall.getType() != HitResult.Type.MISS) {
                flight.add(new Segment(previous, wall.getLocation()));
                break;
            }
            flight.add(new Segment(previous, pos));
        }
        return flight;
    }

    private static int impactTick(List<Segment> flight, AABB box) {
        for (int tick = 0; tick < flight.size(); tick++) {
            Segment segment = flight.get(tick);
            if (box.clip(segment.from(), segment.to()).isPresent()) {
                return tick;
            }
        }
        return -1;
    }

    // The server only counts a shield as up once it has been held for its delay and the trip there
    // and back comes on top.
    private boolean block(Projectile shot, int impactTick, Vec3 from, boolean turnedBefore) {
        InteractionHand hand = shield.isOn() ? shieldHand() : null;
        BlocksAttacks blocks = hand == null ? null : mc.player.getItemInHand(hand).get(DataComponents.BLOCKS_ATTACKS);
        if (blocks == null || !mayRaise() || !stoppable(shot) || !faces(blocks, from, turnedBefore)) {
            lower();
            return false;
        }
        if (!raised) {
            if (!mc.gameMode.useItem(mc.player, hand).consumesAction()) {
                return false;
            }
            raised = true;
        }
        holdUp();
        holdFor = SHIELD_HOLD_TICKS;
        return mc.player.getTicksUsingItem() + impactTick >= blocks.blockDelayTicks() + ServerInfo.pingTicks();
    }

    // The server holds a shield up only against what comes within its widest angle of the yaw it
    // holds for you.
    private boolean faces(BlocksAttacks blocks, Vec3 from, boolean turnedBefore) {
        float yaw = RotationManager.yawTo(from);
        if (faceIt.isOn()) {
            RotationManager.request(yaw, RotationManager.pitchTo(from), RotationPriority.ATTACK,
                RotationManager.NO_STEP);
            turned = true;
        }
        float widest = 0;
        for (BlocksAttacks.DamageReduction reduction : blocks.damageReductions()) {
            widest = Math.max(widest, reduction.horizontalBlockingAngle());
        }
        return Mth.degreesDifferenceAbs(RotationManager.facingYaw(), yaw) <= widest
            && (!turnedBefore || Mth.degreesDifferenceAbs(RotationManager.serverYaw(), yaw) <= widest);
    }

    // A piercing arrow goes through a shield and a thrown potion splashes round it.
    private static boolean stoppable(Projectile shot) {
        return !(shot instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0)
            && !(shot instanceof AbstractThrownPotion);
    }

    // A shield cooling down after an axe hit cannot be raised.
    private InteractionHand shieldHand() {
        for (InteractionHand hand : new InteractionHand[] {InteractionHand.OFF_HAND, InteractionHand.MAIN_HAND}) {
            ItemStack held = mc.player.getItemInHand(hand);
            if (held.has(DataComponents.BLOCKS_ATTACKS) && !mc.player.getCooldowns().isOnCooldown(held)) {
                return hand;
            }
        }
        return null;
    }

    // The use key stays down for the shield. Held down it would use the main hand item the moment
    // the shield goes down under it unless the right click delay still runs.
    private void holdUp() {
        InputUtil.holdUse();
        mc.rightClickDelay = InputUtil.USE_DELAY;
    }

    // A module that cuts the right click delay may tick after holdUp. Whilst the shield is up only
    // the player's own finger on the use key clicks.
    @Subscribe(priority = BEFORE_CLICKS)
    private void onRightClick(RightClickEvent event) {
        if (raised && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            event.cancel();
        }
    }

    private boolean mayRaise() {
        return mc.gui.screen() == null && !Hands.slotsTaken() && (raised || !mc.player.isUsingItem());
    }

    // The shield comes down at once. A meal begun this tick would otherwise find it still in use.
    private void lower() {
        if (!raised) {
            return;
        }
        raised = false;
        InputUtil.releaseUse();
        if (mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().has(DataComponents.BLOCKS_ATTACKS)
            && !InputUtil.physicallyHeld(mc.options.keyUse)) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }

    private static boolean meets(List<Segment> segments, AABB box) {
        for (Segment segment : segments) {
            if (box.clip(segment.from(), segment.to()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    // A packet step lands at once. The velocity keeps whatever rise or fall was under way.
    private void step(Vec3 offset) {
        Vec3 motion = mc.player.getDeltaMovement();
        if (move.is(Move.VELOCITY)) {
            mc.player.setDeltaMovement(offset.x, motion.y, offset.z);
            return;
        }
        // The tick's own movement packet carries the step.
        Vec3 landing = mc.player.position().add(offset);
        mc.player.setPos(landing);
        mc.player.setDeltaMovement(0, motion.y, 0);
    }

    private boolean isClear(Vec3 offset) {
        AABB destination = mc.player.getBoundingBox().move(offset);
        if (!mc.level.noCollision(mc.player, destination)) {
            return false;
        }
        if (!groundCheck.isOn() || !mc.player.onGround()) {
            return true;
        }
        AABB floor = destination.move(0, -0.6, 0);
        return !mc.level.noCollision(mc.player, floor);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!draw.isOn()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        for (Segment segment : paths) {
            batch.line(segment.from(), segment.to(), 0xFFFF6040, true);
        }
    }
}
