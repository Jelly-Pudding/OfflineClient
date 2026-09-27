package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Pours and scoops fluids with a bucket. The server casts its own ray from the eyes
// along the angle the use packet carries. The view turns for the one call and the
// ray is tested here first with the same numbers.
public final class Buckets {

    private static final Minecraft MC = OfflineClient.MC;

    // The floor is tried first and the ceiling last.
    private static final Direction[] POUR_FACES = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    // How far from the middle of a face the other aims sit. An edge of the view can
    // hide the middle and still leave part of the face in sight.
    private static final double FACE_SPREAD = 0.3;

    private Buckets() {
    }

    // A point to look at for a full bucket to empty into the spot. A full bucket's ray
    // passes through fluids and stops on the first block outline. The fluid then goes
    // into the space in front of the face it met. Null when no face in reach leads there.
    public static Vec3 pourAim(BlockPos spot) {
        for (Direction side : POUR_FACES) {
            BlockPos against = spot.relative(side);
            if (BlockUtil.state(against).getShape(MC.level, against).isEmpty()) {
                continue;
            }
            Direction face = side.getOpposite();
            for (Vec3 aim : facePoints(against, face)) {
                BlockHitResult hit = ray(aim, ClipContext.Fluid.NONE);
                if (hit != null && hit.getBlockPos().equals(against) && hit.getDirection() == face) {
                    return aim;
                }
            }
        }
        return null;
    }

    // A point to look at for an empty bucket to scoop the source at the spot. An empty
    // bucket's ray stops on the first source it meets. Null when something is in the way.
    public static Vec3 scoopAim(BlockPos source) {
        Vec3 aim = Vec3.atCenterOf(source);
        BlockHitResult hit = ray(aim, ClipContext.Fluid.SOURCE_ONLY);
        return hit != null && hit.getBlockPos().equals(source) ? aim : null;
    }

    // Puts a bucket of the kind in hand through the loan and uses it once looking at the aim.
    // The loan stays open and the bucket the use leaves behind waits in the lent slot.
    public static boolean useHeld(InventoryUtil.HotbarLoan loan, Item bucket, Vec3 aim) {
        return loan.hold(bucket, InventoryUtil.WHOLE_INVENTORY)
            && RotationManager.whileFacing(RotationManager.yawTo(aim), RotationManager.pitchTo(aim), InputUtil::useMainHand);
    }

    // Borrows a bucket of the kind and uses it once looking at the aim. True when the game took the use.
    public static boolean use(InventoryUtil.HotbarLoan loan, Item bucket, Vec3 aim) {
        boolean used = useHeld(loan, bucket, aim);
        loan.giveBack();
        return used;
    }

    // Fills an empty bucket from the water or powder snow at the spot. The bucket in hand
    // goes first and the loan stays open. True once the use went through.
    public static boolean scoop(BlockPos source, InventoryUtil.HotbarLoan loan) {
        Vec3 aim = scoopAim(source);
        return aim != null && useHeld(loan, Items.BUCKET, aim);
    }

    // Where a bucket poured onto the top of this block puts its water. A block that can hold
    // water takes it unless the player sneaks. The space above takes it otherwise. Null when
    // neither can.
    public static BlockPos pourSpot(BlockPos ground, boolean sneaking) {
        if (!sneaking && holdsWater(ground)) {
            return ground;
        }
        BlockPos above = ground.above();
        BlockState state = BlockUtil.state(above);
        return state.isAir() || state.canBeReplaced(Fluids.WATER) || holdsWater(above) ? above : null;
    }

    private static boolean holdsWater(BlockPos pos) {
        BlockState state = BlockUtil.state(pos);
        return state.getBlock() instanceof LiquidBlockContainer container
            && container.canPlaceLiquid(MC.player, MC.level, pos, state, Fluids.WATER);
    }

    // The middle of the face first and then four points around it on the same face.
    private static List<Vec3> facePoints(BlockPos pos, Direction face) {
        Vec3 middle = BlockUtil.hitPoint(pos, face);
        List<Vec3> points = new ArrayList<>();
        points.add(middle);
        for (Direction along : Direction.values()) {
            if (along.getAxis() != face.getAxis()) {
                points.add(middle.relative(along, FACE_SPREAD));
            }
        }
        return points;
    }

    // The ray a bucket casts when the view points at the aim. Null when it meets nothing.
    private static BlockHitResult ray(Vec3 aim, ClipContext.Fluid fluids) {
        return ray(RotationManager.yawTo(aim), RotationManager.pitchTo(aim), fluids);
    }

    // The ray a bucket casts with the view at these angles. Null when it meets nothing.
    public static BlockHitResult ray(float yaw, float pitch, ClipContext.Fluid fluids) {
        LocalPlayer player = MC.player;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(player.blockInteractionRange()));
        BlockHitResult hit = MC.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluids, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
}
