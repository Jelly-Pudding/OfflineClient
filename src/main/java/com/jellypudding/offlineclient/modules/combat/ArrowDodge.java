package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Cooldowns;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Flies every projectile in range forward and takes the first sidestep
// that clears them all. Fireballs and wind charges and shulker bullets can be hit instead.
public final class ArrowDodge extends Module {

    // Each axis of a sidestep at forty five degrees.
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

    private record Segment(Vec3 from, Vec3 to) {
    }

    public enum Move { VELOCITY, PACKET }

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a projectile is still watched.", 32, 8, 64, 4, " blocks").min(1);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 40, 5, 120, 5, " ticks").min(1);
    private final NumberSetting margin = new NumberSetting("Margin",
        "Extra space kept around you.", 0.4, 0, 2, 0.1);
    private final EnumSetting<Move> move = new EnumSetting<>("Move",
        "How the sidestep is made.", Move.VELOCITY)
        .describe(Move.VELOCITY, "Pushes you sideways and lets the game carry you.")
        .describe(Move.PACKET, "Steps sideways at once with a position packet.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How far each sidestep goes.", 0.35, 0.05, 1.5, 0.05).min(0.01);
    private final BoolSetting everything = new BoolSetting("All projectiles",
        "Dodge thrown items and fireballs as well as arrows.", false);
    private final BoolSetting hitBack = new BoolSetting("Hit back",
        "Punches fireballs and wind charges back and breaks shulker bullets before they reach you.", true);
    private final NumberSetting hitRange = new NumberSetting("Hit range",
        "How close a fireball or bullet comes before it is hit.", 5, 3, HIT_REACH, 0.5, " blocks")
        .min(0.5).max(HIT_REACH).under(hitBack);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH).under(hitBack);
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own",
        "Never dodge what you fired yourself.", true);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Never dodge what a friend fired.", true);
    private final BoolSetting groundCheck = new BoolSetting("Ground check",
        "Only step onto blocks that will hold you up.", true);
    private final BoolSetting draw = new BoolSetting("Draw paths",
        "Show the predicted flight of every watched projectile.", false);

    // Every watched flight for drawing and the ones a sidestep has to clear.
    private final List<Segment> paths = new ArrayList<>();
    private final List<Segment> threats = new ArrayList<>();
    private final Cooldowns<Integer> struck = new Cooldowns<>();
    private boolean dodging;

    public ArrowDodge() {
        super("ArrowDodge", "Sidesteps arrows and other projectiles aimed at you.", Category.COMBAT);
        addSettings(range, steps, margin, move, speed, everything, hitBack, hitRange, swing, ignoreOwn,
            ignoreFriends, groundCheck, draw);
        searchTags("dodge", "arrow", "projectile", "anti projectile", "fireball", "shulker bullet");
    }

    @Override
    public String getSuffix() {
        return dodging ? "dodging" : null;
    }

    @Override
    protected void onDisable() {
        paths.clear();
        threats.clear();
        struck.clear();
        dodging = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        paths.clear();
        threats.clear();
        dodging = false;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        struck.tick();

        AABB body = mc.player.getBoundingBox().inflate(margin.getValue());
        double watch = range.getValue();
        // The server throws away a plain hit whilst a spear is in hand.
        boolean punches = hitBack.isOn() && !mc.player.getMainHandItem().has(DataComponents.PIERCING_WEAPON);
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
                // One that can be hit is never dodged.
                if (incoming(projectile, flight, body) && !struck.contains(projectile.getId())
                    && EntityUtil.reachDistance(mc.player, projectile) <= hitRange.getValue()) {
                    strike(projectile);
                }
                continue;
            }
            threats.addAll(flight);
        }
        if (!meets(threats, body)) {
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
        boolean kind = projectile instanceof AbstractArrow || everything.isOn()
            || hitBack.isOn() && hittable(projectile);
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
        swing.getValue().swing(InteractionHand.MAIN_HAND);
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
