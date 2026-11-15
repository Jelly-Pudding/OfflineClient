package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.ScaffoldingBlockItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallHangingSignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// Places a block in the state a build wants. Which half a slab lands in and which way a stair
// faces and what a torch hangs on all come from the click and from the look the server holds
// when the click lands. For every click it could make the placer asks the game's own placing
// rules what state that click would give and takes one that gives the wanted state. When none
// does under the look the server holds it finds a look that works and holds it through the face
// mode until the server has it.
//
// The server reads a look two ways. The way the body faces is the yaw of the last look packet.
// The nearest looking direction goes by the head and the head only follows the body once a
// server tick. A click right after a turn can meet the head still facing the way it did. A click
// is made only when every yaw the head may still hold gives the wanted state.
public final class StatePlacer {

    private static final Minecraft MC = OfflineClient.MC;

    // How a caller wants its blocks clicked.
    public record Rules(double range, boolean needSight, boolean airPlace, FaceMode face, SwingMode swing) {
    }

    // A click that puts the wanted state at the spot. One that is not ready waits for the
    // server to take the look the placer is holding for it.
    public record Plan(BlockPos pos, BlockHitResult hit, boolean sneak, float yaw, float pitch, boolean ready) {
    }

    // Why the last spot asked about got no plan.
    public enum Hitch {
        NONE,
        // Nothing in reach to click that would put a block there.
        NO_CLICK,
        // No click from here gives the wanted state.
        NO_STATE
    }

    private record Click(BlockHitResult hit, boolean sneak) {
    }

    private record Look(float yaw, float pitch) {
    }

    // A look held for one spot until its click can go.
    private record Aim(long cell, Look look, FaceMode face, int since) {
    }

    // A spot that got no plan with the tick it was asked on and why. One that no look at all
    // helps stays put whatever the server look does. One that only the look of that tick failed
    // is worth asking again as soon as the server look moves.
    private record NoPlan(int at, boolean anyLook, Hitch hitch) {
    }

    // How far off the middle of a face the four extra hit spots lie. Which half of the face is
    // hit decides the half of a slab or a stair and the hinge of a door.
    private static final double SPOT_OFFSET = 0.25;
    private static final double[][] SPOT_OFFSETS = {
        {SPOT_OFFSET, SPOT_OFFSET}, {SPOT_OFFSET, -SPOT_OFFSET}, {-SPOT_OFFSET, SPOT_OFFSET},
        {-SPOT_OFFSET, -SPOT_OFFSET}};

    // Steep enough that up or down is the nearest looking direction and the yaw still says
    // which side comes second.
    private static final float STEEP_PITCH = 60;
    private static final float[] PITCHES = {0, STEEP_PITCH, -STEEP_PITCH};

    // A sign or a banner faces back at whoever places it.
    private static final float HALF_TURN = 180;

    // Ticks a look is held for one spot before the spot is given up on for a while.
    private static final int AIM_TICKS = 10;
    // Ticks before a spot with no plan is thought about again.
    private static final int RETRY_TICKS = 10;
    // The server look counts as moved past this many degrees.
    private static final float LOOK_MOVED = 1;
    private static final int MOST_REMEMBERED = 4096;

    // The wall form of every item that stands on the floor or hangs on a wall.
    private static final Map<Item, Block> WALL_FORMS = new IdentityHashMap<>();

    // The yaw the server held when this tick began and when the one before began.
    private float lastYaw;
    private float earlierYaw;
    private float lastPitch;
    private int ticks;
    private int lookMovedAt;
    private Aim aim;
    private int aimAskedAt;
    private final Long2ObjectOpenHashMap<NoPlan> noPlan = new Long2ObjectOpenHashMap<>();
    private Hitch hitch = Hitch.NONE;

    // Once a tick before any spot is asked about. A held look is asked for again first of all
    // and nothing else of the same priority can take the turn from it.
    public void tick() {
        ticks++;
        float yaw = RotationManager.serverYaw();
        float pitch = RotationManager.serverPitch();
        if (Mth.degreesDifferenceAbs(yaw, lastYaw) > LOOK_MOVED || Math.abs(pitch - lastPitch) > LOOK_MOVED) {
            lookMovedAt = ticks;
        }
        earlierYaw = lastYaw;
        lastYaw = yaw;
        lastPitch = pitch;
        if (aim == null) {
            return;
        }
        if (ticks - aimAskedAt > 1) {
            aim = null;
        } else if (ticks - aim.since() > AIM_TICKS) {
            remember(aim.cell(), true, Hitch.NO_STATE);
            aim = null;
        } else {
            aim.face().faceExact(aim.look().yaw(), aim.look().pitch(), RotationPriority.PLACE);
        }
    }

    // Lets go of a held look and forgets every spot that got no plan.
    public void reset() {
        aim = null;
        noPlan.clear();
        hitch = Hitch.NONE;
    }

    public Hitch hitch() {
        return hitch;
    }

    // The click that puts the wanted state at the spot with the stack given. A null state takes
    // whatever the click gives. Null when no click from here does it. Hitch then says why.
    public Plan plan(BlockPos pos, BlockState wanted, ItemStack stack, InteractionHand hand, Rules rules) {
        long cell = pos.asLong();
        hitch = remembered(cell);
        if (hitch != Hitch.NONE || !(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        List<Click> clicks = clicks(pos, rules);
        if (clicks.isEmpty()) {
            hitch = Hitch.NO_CLICK;
            return null;
        }
        Look held = aim != null && aim.cell() == cell ? aim.look() : null;
        if (held != null) {
            aimAskedAt = ticks;
        }
        Look server = new Look(RotationManager.serverYaw(), RotationManager.serverPitch());
        boolean looksFirst = rules.face() == FaceMode.SPAM;
        Plan near = null;
        for (Click click : clicks) {
            // The spam mode sends its look right before the click and the server then holds that one.
            Look at = !looksFirst ? server : held != null ? held : lookAt(click.hit().getLocation());
            StateMatch.Fit fit = fit(pos, wanted, item, stack, hand, click, at, false);
            if (fit == StateMatch.Fit.RIGHT) {
                return new Plan(pos, click.hit(), click.sneak(), at.yaw(), at.pitch(), true);
            }
            if (fit == StateMatch.Fit.NEAR && near == null) {
                near = new Plan(pos, click.hit(), click.sneak(), at.yaw(), at.pitch(), true);
            }
        }
        if (near != null) {
            return near;
        }
        hitch = Hitch.NO_STATE;
        if (held != null) {
            return turning(pos, clicks.getFirst(), held);
        }
        // Without a turn only another server look can help. One look is held at a time and a spot
        // behind it waits its turn the same way.
        if (wanted == null || rules.face() == FaceMode.OFF || aim != null) {
            remember(cell, false, Hitch.NO_STATE);
            return null;
        }
        return turn(pos, wanted, item, stack, hand, clicks, rules.face());
    }

    // Finds a look that gives the wanted state and starts holding it.
    private Plan turn(BlockPos pos, BlockState wanted, BlockItem item, ItemStack stack, InteractionHand hand,
                      List<Click> clicks, FaceMode face) {
        Click nearClick = null;
        Look nearLook = null;
        for (Look look : looks(wanted)) {
            for (Click click : clicks) {
                StateMatch.Fit fit = fit(pos, wanted, item, stack, hand, click, look, true);
                if (fit == StateMatch.Fit.RIGHT) {
                    return hold(pos, click, look, face);
                }
                if (fit == StateMatch.Fit.NEAR && nearClick == null) {
                    nearClick = click;
                    nearLook = look;
                }
            }
        }
        if (nearClick != null) {
            return hold(pos, nearClick, nearLook, face);
        }
        remember(pos.asLong(), true, Hitch.NO_STATE);
        return null;
    }

    private Plan hold(BlockPos pos, Click click, Look look, FaceMode face) {
        aim = new Aim(pos.asLong(), look, face, ticks);
        aimAskedAt = ticks;
        face.faceExact(look.yaw(), look.pitch(), RotationPriority.PLACE);
        hitch = Hitch.NONE;
        return turning(pos, click, look);
    }

    private static Plan turning(BlockPos pos, Click click, Look look) {
        return new Plan(pos, click.hit(), click.sneak(), look.yaw(), look.pitch(), false);
    }

    // Makes the click of a ready plan with the item in the hand given. True when the game took it.
    // The view turns to the look the server holds for the length of the click. The block the
    // client puts down ahead of the answer is then the one the server will put down.
    public boolean click(Plan plan, InteractionHand hand, Rules rules) {
        if (rules.face() == FaceMode.SPAM) {
            rules.face().faceExact(plan.yaw(), plan.pitch(), RotationPriority.PLACE);
        } else if (aim == null) {
            rules.face().face(plan.hit().getLocation(), RotationPriority.PLACE);
        }
        if (aim != null && aim.cell() == plan.pos().asLong()) {
            aim = null;
        }
        BooleanSupplier use = () -> MC.gameMode.useItemOn(MC.player, hand, plan.hit()).consumesAction();
        boolean taken = RotationManager.whileFacing(RotationManager.serverYaw(), RotationManager.serverPitch(),
            () -> plan.sneak() ? InputUtil.whileSneaking(use) : InputUtil.whileStanding(use));
        if (taken) {
            rules.swing().use(hand);
            // The block that went down may be what a spot beside it waited for.
            for (Direction side : Direction.values()) {
                noPlan.remove(plan.pos().relative(side).asLong());
            }
        }
        MC.rightClickDelay = InputUtil.USE_DELAY;
        return taken;
    }

    // Why a spot still has no plan without thinking it over again. None once it is worth
    // another look.
    private Hitch remembered(long cell) {
        NoPlan last = noPlan.get(cell);
        if (last == null) {
            return Hitch.NONE;
        }
        if (ticks - last.at() < RETRY_TICKS && (last.anyLook() || lookMovedAt <= last.at())) {
            return last.hitch();
        }
        noPlan.remove(cell);
        return Hitch.NONE;
    }

    private void remember(long cell, boolean anyLook, Hitch why) {
        if (noPlan.size() >= MOST_REMEMBERED) {
            noPlan.clear();
        }
        noPlan.put(cell, new NoPlan(ticks, anyLook, why));
    }

    private static Look lookAt(Vec3 point) {
        return new Look(Mth.wrapDegrees(RotationManager.yawTo(point)), RotationManager.pitchTo(point));
    }

    // The looks worth trying for a state. A sign or a banner or a head on the floor turns in
    // sixteen steps with the yaw. Everything else goes by the four ways and by up and down.
    private static List<Look> looks(BlockState wanted) {
        List<Look> looks = new ArrayList<>();
        if (wanted.hasProperty(BlockStateProperties.ROTATION_16)) {
            float yaw = RotationSegment.convertToDegrees(wanted.getValue(BlockStateProperties.ROTATION_16));
            // A head faces the way you look. A sign or a banner faces back at you.
            for (float pitch : new float[] {0, STEEP_PITCH}) {
                looks.add(new Look(Mth.wrapDegrees(yaw - HALF_TURN), pitch));
                looks.add(new Look(Mth.wrapDegrees(yaw), pitch));
            }
            return looks;
        }
        for (float pitch : PITCHES) {
            for (Direction way : Direction.Plane.HORIZONTAL) {
                looks.add(new Look(way.toYRot(), pitch));
            }
        }
        return looks;
    }

    // Every click in reach that might put a block at the spot. The faces of the blocks beside
    // it come first with the nearest in front. Then the spot itself when it holds something to
    // click or the caller allows a click into thin air.
    private static List<Click> clicks(BlockPos pos, Rules rules) {
        ClientLevel level = MC.level;
        Vec3 eye = MC.player.getEyePosition();
        double rangeSq = rules.range() * rules.range();
        // The plain clicks are made the way the server sees the player already. No sneak
        // packet goes out for them.
        boolean sneaking = MC.player.getLastSentInput().shift();
        List<Click> clicks = new ArrayList<>();
        List<Direction> sides = new ArrayList<>(List.of(Direction.values()));
        sides.sort(Comparator.comparingDouble(side -> eye.distanceToSqr(faceMiddle(pos.relative(side),
            side.getOpposite()))));
        for (Direction side : sides) {
            BlockPos beside = pos.relative(side);
            BlockState there = level.getBlockState(beside);
            if (there.canBeReplaced() || there.getShape(level, beside).isEmpty() || !BlockUtil.serverReaches(beside)) {
                continue;
            }
            // A block that opens only takes a block against it from a sneaking player.
            boolean sneak = sneaking || BlockUtil.opensOnClick(there);
            for (Vec3 spot : spots(beside, side.getOpposite(), true)) {
                if (eye.distanceToSqr(spot) <= rangeSq && (!rules.needSight() || BlockUtil.canSee(spot))) {
                    clicks.add(new Click(new BlockHitResult(spot, side.getOpposite(), beside, false), sneak));
                }
            }
        }
        boolean solid = !level.getBlockState(pos).getShape(level, pos).isEmpty();
        if ((solid || rules.airPlace()) && BlockUtil.reaches(pos, rules.range())) {
            for (Direction face : Direction.values()) {
                for (Vec3 spot : spots(pos, face, solid)) {
                    if (!rules.needSight() || BlockUtil.canSee(spot)) {
                        clicks.add(new Click(new BlockHitResult(spot, face, pos, false), sneaking));
                    }
                }
            }
        }
        // A few states only come from a click made the other way. A hanging sign under a full
        // block turns freely for a sneaking player alone. Those clicks are tried after the plain ones.
        int plain = clicks.size();
        for (int i = 0; i < plain; i++) {
            BlockHitResult hit = clicks.get(i).hit();
            if (hit.getBlockPos().equals(pos) || !BlockUtil.opensOnClick(level.getBlockState(hit.getBlockPos()))) {
                clicks.add(new Click(hit, !clicks.get(i).sneak()));
            }
        }
        return clicks;
    }

    private static Vec3 faceMiddle(BlockPos block, Direction face) {
        return Vec3.atCenterOf(block).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    // The middle of a face and four spots a quarter of a block off it. The middle of a block
    // with a shape of its own is taken from that shape. Without one nothing of the world is read
    // and a plan of where to stand asks the spots a click would try.
    public static List<Vec3> spots(BlockPos block, Direction face, boolean shaped) {
        List<Vec3> spots = new ArrayList<>();
        Vec3 middle = faceMiddle(block, face);
        spots.add(shaped ? BlockUtil.hitPoint(block, face) : middle);
        for (double[] offset : SPOT_OFFSETS) {
            spots.add(switch (face.getAxis()) {
                case X -> middle.add(0, offset[0], offset[1]);
                case Y -> middle.add(offset[0], 0, offset[1]);
                case Z -> middle.add(offset[1], offset[0], 0);
            });
        }
        return spots;
    }

    // How the state a click would give compares with the wanted one. A settled look is one
    // the server has held long enough for head and body to agree.
    private StateMatch.Fit fit(BlockPos pos, BlockState wanted, BlockItem item, ItemStack stack,
                               InteractionHand hand, Click click, Look look, boolean settled) {
        float[] heads = settled ? new float[] {look.yaw()} : new float[] {look.yaw(), lastYaw, earlierYaw};
        StateMatch.Fit worst = StateMatch.Fit.RIGHT;
        for (int i = 0; i < heads.length; i++) {
            if (i > 0 && (heads[i] == heads[0] || i > 1 && heads[i] == heads[1])) {
                continue;
            }
            BlockState given = result(pos, item, stack, hand, click, look, heads[i]);
            if (given == null) {
                return StateMatch.Fit.WRONG;
            }
            StateMatch.Fit fit = wanted == null ? StateMatch.Fit.RIGHT : StateMatch.fit(given, wanted, true);
            if (fit == StateMatch.Fit.WRONG) {
                return fit;
            }
            if (fit == StateMatch.Fit.NEAR) {
                worst = fit;
            }
        }
        return worst;
    }

    // The state the game would put at the spot for this click. Null when the click puts nothing
    // there. The sneak key is set for the length of the question because the placing rules read
    // it from the player.
    private static BlockState result(BlockPos pos, BlockItem item, ItemStack stack, InteractionHand hand,
                                     Click click, Look look, float headYaw) {
        LocalPlayer player = MC.player;
        Input held = player.input.keyPresses;
        player.input.keyPresses = InputUtil.withShift(held, click.sneak());
        try {
            return stateFor(MC.level, player, item, stack, hand, click.hit(), pos, look, headYaw);
        } finally {
            player.input.keyPresses = held;
        }
    }

    // The same for any player in any level. The steps are those of the item itself. The click
    // has to be one that may place at all and the item may move it and it has to end at the spot.
    private static BlockState stateFor(Level level, Player player, BlockItem item, ItemStack stack,
                                       InteractionHand hand, BlockHitResult hit, BlockPos pos, Look look,
                                       float headYaw) {
        Asked asked = new Asked(level, player, hand, stack, hit, look, headYaw);
        if (!asked.canPlace()) {
            return null;
        }
        BlockPlaceContext moved = item.updatePlacementContext(asked);
        if (moved == null || !moved.getClickedPos().equals(pos)) {
            return null;
        }
        return placementState(item, moved, player);
    }

    // What the item makes of the click. The game keeps this step to itself and it is followed
    // here rule for rule. An item that stands or hangs tries its two forms in the order of the
    // nearest looking directions. Any block has to survive where it lands with nobody in the way.
    private static BlockState placementState(BlockItem item, BlockPlaceContext context, Player player) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (item instanceof PlaceOnWaterBlockItem
            || item instanceof GameMasterBlockItem && !player.canUseGameMasterBlocks()) {
            return null;
        }
        if (!(item instanceof StandingAndWallBlockItem)) {
            BlockState state = item.getBlock().getStateForPlacement(context);
            boolean survives = state != null
                && (item instanceof ScaffoldingBlockItem || state.canSurvive(level, pos));
            return survives && level.isUnobstructed(state, pos, CollisionContext.placementContext(player))
                ? state : null;
        }
        Direction attachment = item instanceof HangingSignItem ? Direction.UP : Direction.DOWN;
        BlockState onWall = wallForm(item).getStateForPlacement(context);
        for (Direction way : context.getNearestLookingDirections()) {
            if (way == attachment.getOpposite()) {
                continue;
            }
            BlockState state = way == attachment ? item.getBlock().getStateForPlacement(context) : onWall;
            if (state != null && state.canSurvive(level, pos) && hangs(state, level, pos)) {
                return level.isUnobstructed(state, pos, CollisionContext.empty()) ? state : null;
            }
        }
        return null;
    }

    // A hanging sign on a wall has a rule of its own on top of the usual one.
    private static boolean hangs(BlockState state, Level level, BlockPos pos) {
        return !(state.getBlock() instanceof WallHangingSignBlock sign) || sign.canPlace(state, level, pos);
    }

    // The block an item puts on a wall. The game registers both forms of the item under it.
    private static Block wallForm(BlockItem item) {
        return WALL_FORMS.computeIfAbsent(item, standing -> {
            for (Map.Entry<Block, Item> form : Item.BY_BLOCK.entrySet()) {
                if (form.getValue() == standing && form.getKey() != item.getBlock()) {
                    return form.getKey();
                }
            }
            return item.getBlock();
        });
    }

    // A click as the placing rules see it with the look of the server in place of the view. The
    // body yaw gives the way the player faces and the head yaw the nearest looking directions.
    private static final class Asked extends BlockPlaceContext {

        private final Look look;
        private final float headYaw;

        private Asked(Level level, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit,
                      Look look, float headYaw) {
            super(level, player, hand, stack, hit);
            this.look = look;
            this.headYaw = headYaw;
        }

        @Override
        public Direction getHorizontalDirection() {
            return Direction.fromYRot(look.yaw());
        }

        @Override
        public float getRotation() {
            return look.yaw();
        }

        @Override
        public Direction getNearestLookingDirection() {
            return facingHead(super::getNearestLookingDirection);
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            return facingHead(super::getNearestLookingDirections);
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return look.pitch() < 0 ? Direction.UP : Direction.DOWN;
        }

        // The game works the directions out from the view of the player it is handed. The yaw
        // of that view is the head's for most living things and the body's for the local player.
        private <T> T facingHead(Supplier<T> read) {
            Player player = getPlayer();
            float yaw = player.getYRot();
            float head = player.getYHeadRot();
            float pitch = player.getXRot();
            player.setYRot(headYaw);
            player.setYHeadRot(headYaw);
            player.setXRot(look.pitch());
            try {
                return read.get();
            } finally {
                player.setYRot(yaw);
                player.setYHeadRot(head);
                player.setXRot(pitch);
            }
        }
    }
}
