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
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ProjectileUtil;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Flies every projectile in range forward and takes the first sidestep
// that clears them all.
public final class ArrowDodge extends Module {

    // Each axis of a sidestep at forty five degrees.
    private static final double DIAGONAL = Math.sqrt(0.5);

    private static final Vec3[] DIRECTIONS = {
        new Vec3(1, 0, 0), new Vec3(-1, 0, 0), new Vec3(0, 0, 1), new Vec3(0, 0, -1),
        new Vec3(DIAGONAL, 0, DIAGONAL), new Vec3(-DIAGONAL, 0, DIAGONAL),
        new Vec3(DIAGONAL, 0, -DIAGONAL), new Vec3(-DIAGONAL, 0, -DIAGONAL)
    };

    private static final int GROWTH_TRIES = 6;

    private record Segment(Vec3 from, Vec3 to) {
    }

    public enum Move { VELOCITY, PACKET }

    private final NumberSetting range = new NumberSetting("Range",
        "How far away a projectile is still watched.", 32, 8, 64, 4, " blocks").min(1);
    private final NumberSetting steps = new NumberSetting("Steps",
        "How many ticks of flight to predict.", 40, 5, 120, 5, " ticks").min(1).max(400);
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
    private final BoolSetting ignoreOwn = new BoolSetting("Ignore own",
        "Never dodge what you fired yourself.", true);
    private final BoolSetting ignoreFriends = new BoolSetting("Ignore friends",
        "Never dodge what a friend fired.", true);
    private final BoolSetting groundCheck = new BoolSetting("Ground check",
        "Only step onto blocks that will hold you up.", true);
    private final BoolSetting draw = new BoolSetting("Draw paths",
        "Show the predicted flight of every watched projectile.", false);

    private final List<Segment> paths = new ArrayList<>();
    private boolean dodging;

    public ArrowDodge() {
        super("ArrowDodge", "Sidesteps arrows and other projectiles aimed at you.", Category.COMBAT);
        addSettings(range, steps, margin, move, speed, everything, ignoreOwn, ignoreFriends,
            groundCheck, draw);
        searchTags("dodge", "arrow", "projectile");
    }

    @Override
    public String getSuffix() {
        return dodging ? "dodging" : null;
    }

    @Override
    protected void onDisable() {
        paths.clear();
        dodging = false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        paths.clear();
        dodging = false;
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }

        double watch = range.getValue();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || !watches(projectile)) {
                continue;
            }
            if (mc.player.distanceTo(projectile) > watch) {
                continue;
            }
            predict(projectile);
        }
        if (paths.isEmpty()) {
            return;
        }

        AABB body = mc.player.getBoundingBox().inflate(margin.getValue());
        if (!hits(body)) {
            return;
        }

        double push = speed.getValue();
        for (int attempt = 0; attempt < GROWTH_TRIES; attempt++) {
            for (Vec3 direction : DIRECTIONS) {
                Vec3 offset = direction.scale(push);
                AABB moved = body.move(offset);
                if (hits(moved) || !isClear(offset)) {
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
        if (!everything.isOn() && !(projectile instanceof AbstractArrow)) {
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

    private void predict(Projectile projectile) {
        double gravity = projectile instanceof AbstractArrow
            ? ProjectileUtil.ARROW_GRAVITY : ProjectileUtil.THROWN_GRAVITY;
        Vec3 pos = projectile.position();
        Vec3 velocity = projectile.getDeltaMovement();
        int limit = steps.getInt();
        int minY = mc.level.getMinY();

        for (int i = 0; i < limit; i++) {
            Vec3 previous = pos;
            pos = pos.add(velocity);
            velocity = velocity.scale(ProjectileUtil.ARROW_DRAG).subtract(0, gravity, 0);
            if (pos.y < minY) {
                break;
            }
            // A projectile that buries itself in a wall is no longer a threat.
            HitResult wall = mc.level.clip(new ClipContext(previous, pos,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile));
            if (wall.getType() != HitResult.Type.MISS) {
                paths.add(new Segment(previous, wall.getLocation()));
                break;
            }
            paths.add(new Segment(previous, pos));
        }
    }

    private boolean hits(AABB box) {
        for (Segment segment : paths) {
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
        Vec3 landing = mc.player.position().add(offset);
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(landing,
            mc.player.onGround(), mc.player.horizontalCollision));
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
