package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;

// The server takes the clicks in the order they arrive and a piston only moves once the
// tick is over. The piston goes down first and then the crystal and the power last.
public final class PistonAura extends Module {

    public enum Mode { CRYSTAL, PUSH }

    // A piston head carries whatever it strikes this far at most over its two ticks.
    private static final double PUSH_REACH = 1.02;

    // The head leaves an entity 0.01 past its face on each of those ticks. A push needs room for both.
    private static final double PUSH_SLACK = 0.02;

    // Ticks a placed crystal is waited on before it is hit wherever it stands.
    private static final int PUSH_WAIT = 8;

    // How close to where it should end up a pushed crystal has to come before the hit.
    private static final double ARRIVED = 0.25;

    // One way to hit the target. The crystal is null for a push.
    private record Setup(BlockPos piston, Direction facing, boolean reuse, BlockPos crystal,
                         BlockPos power, boolean torch, Vec3 blast) {
    }

    private record Pending(BlockPos crystal, Vec3 blast, int firedAt) {
    }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode", "What the piston shoves.", Mode.CRYSTAL)
        .describe(Mode.CRYSTAL, "Pushes a crystal off the top of their wall into their head and sets it off.")
        .describe(Mode.PUSH, "Shoves the enemy out through an open side of their hole.");
    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 10, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Targets",
        TargetPriority.NEAREST);
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place the piston and the crystal and the power.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to hit the crystal.", 4.5, 1, 6, 0.1, " blocks")
        .under(mode, Mode.CRYSTAL);
    private final NumberSetting minDamage = new NumberSetting("Min damage",
        "Only fire when the enemy would take at least this much.", 8, 0, 36, 0.5)
        .under(mode, Mode.CRYSTAL);
    private final NumberSetting maxSelfDamage = new NumberSetting("Max self damage",
        "Never take more than this from the blast.", 6, 0, 36, 0.5)
        .under(mode, Mode.CRYSTAL);
    private final BoolSetting antiSuicide = new BoolSetting("Anti suicide",
        "Never set off a blast that could kill you.", true)
        .under(mode, Mode.CRYSTAL);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait after one piston fires before the next goes down.", 4, 0, 20, 1, " ticks");
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws where the piston and the crystal and the power go.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 20).under(render);

    private final SlotSwap slots = new SlotSwap();
    private Setup planned;
    private Pending pending;
    private boolean turning;
    private int timer;
    private String targetName;
    private String status;

    public PistonAura() {
        super("PistonAura", "Fires pistons at an enemy in a hole to push a crystal into them or shove them out.",
            Category.COMBAT);
        addSettings(mode, targetRange, priority, placeRange, breakRange, minDamage, maxSelfDamage,
            antiSuicide, delay, swing, render);
        addSettings(style.settings());
        searchTags("piston crystal", "piston push", "cpvp", "hole");
    }

    @Override
    public String getSuffix() {
        return suffix(targetName, status);
    }

    // True whilst a piston is being turned for or its crystal is still out.
    // CrystalAura stands aside for that long.
    public boolean isFiring() {
        return turning || pending != null;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        slots.forget();
        planned = null;
        pending = null;
        targetName = null;
        status = null;
    }

    @Override
    protected void onDisable() {
        slots.restore();
        planned = null;
        pending = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        status = null;
        turning = false;
        if (!inGame() || mc.player.isSpectator()) {
            targetName = null;
            planned = null;
            pending = null;
            return;
        }
        timer = Math.max(0, timer - 1);
        if (pending != null) {
            watchCrystal();
            return;
        }
        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
        targetName = EntityUtil.nameOf(target);
        planned = null;
        if (target == null || timer > 0) {
            return;
        }
        fireAt(target);
        slots.restore();
    }

    private void fireAt(Player target) {
        int piston = InventoryUtil.hotbarSlot(stack -> stack.is(Items.PISTON) || stack.is(Items.STICKY_PISTON));
        int block = InventoryUtil.hotbarSlot(stack -> stack.is(Items.REDSTONE_BLOCK));
        int torch = InventoryUtil.hotbarSlot(stack -> stack.is(Items.REDSTONE_TORCH));
        int crystal = InventoryUtil.hotbarSlot(stack -> stack.is(Items.END_CRYSTAL));
        InteractionHand crystalHand = crystalHand(crystal);
        if (piston == -1) {
            status = "(no pistons)";
            return;
        }
        if (block == -1 && torch == -1) {
            status = "(no redstone)";
            return;
        }
        if (mode.is(Mode.CRYSTAL) && crystalHand == null) {
            status = "(no crystals)";
            return;
        }
        planned = mode.is(Mode.CRYSTAL) ? crystalSetup(target, block != -1, torch != -1)
            : pushSetup(target, block != -1, torch != -1);
        if (planned == null) {
            status = "(no spot)";
            return;
        }
        // The piston faces away from the way the server sees you look.
        float yaw = planned.facing().getOpposite().toYRot();
        if (!planned.reuse()) {
            RotationManager.requestExact(yaw, 0, RotationPriority.AURA);
            if (!RotationManager.sentIsFacing(yaw, 0, RotationManager.BLOCK_TOLERANCE)) {
                turning = true;
                status = "(turning)";
                return;
            }
            slots.select(piston);
            if (!RotationManager.whileFacing(yaw, 0, () -> BlockUtil.placeAny(planned.piston(), false, false))) {
                status = "(piston refused)";
                return;
            }
            swing.getValue().swing(InteractionHand.MAIN_HAND);
        }
        if (planned.crystal() != null && !placeCrystal(crystalHand, crystal, planned.crystal().below())) {
            status = "(crystal refused)";
            return;
        }
        slots.select(planned.torch() ? torch : block);
        boolean powered = planned.torch()
            ? BlockUtil.place(planned.power(), Direction.DOWN, false, false)
            : BlockUtil.placeAny(planned.power(), false, false);
        if (!powered) {
            status = "(power refused)";
            return;
        }
        swing.getValue().swing(InteractionHand.MAIN_HAND);
        timer = delay.getInt();
        if (planned.crystal() != null) {
            pending = new Pending(planned.crystal(), planned.blast(), mc.player.tickCount);
        }
    }

    // The offhand when it holds crystals. Otherwise the main hand on the hotbar slot of them.
    private InteractionHand crystalHand(int slot) {
        if (mc.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            return InteractionHand.OFF_HAND;
        }
        return slot == -1 ? null : InteractionHand.MAIN_HAND;
    }

    private boolean placeCrystal(InteractionHand hand, int slot, BlockPos base) {
        if (hand == InteractionHand.MAIN_HAND) {
            slots.select(slot);
        }
        if (!mc.gameMode.useItemOn(mc.player, hand, BlockUtil.crystalClick(base)).consumesAction()) {
            return false;
        }
        swing.getValue().swing(hand);
        return true;
    }

    // A crystal on top of the wall beside the target with a piston behind it at head height.
    // The head shoves the crystal into the target's head where no wall stands between.
    private Setup crystalSetup(Player target, boolean blocks, boolean torches) {
        BlockPos feet = target.blockPosition();
        Setup best = null;
        float bestDamage = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos base = feet.relative(side);
            if (!BlockUtil.crystalBase(BlockUtil.state(base))) {
                continue;
            }
            BlockPos crystal = base.above();
            Vec3 crystalHit = BlockUtil.crystalClick(base).getLocation();
            if (!BlockUtil.state(crystal).isAir() || !crystalFits(crystal)
                || mc.player.getEyePosition().distanceTo(crystalHit) > placeRange.getValue()) {
                continue;
            }
            Direction facing = side.getOpposite();
            BlockPos piston = crystal.relative(side);
            boolean reuse = pistonWaiting(piston, facing);
            if (!reuse && !pistonFits(piston)) {
                continue;
            }
            Vec3 blast = pushedCrystal(crystal, facing);
            float damage = ExplosionUtil.crystalDamage(target, blast);
            if (damage < minDamage.getFloat() || damage <= bestDamage || !selfSafe(blast)) {
                continue;
            }
            AABB crystalBox = crystalBox(crystal);
            Setup setup = withPower(piston, facing, reuse, crystal, blast, blocks, torches, crystalBox);
            if (setup != null) {
                best = setup;
                bestDamage = damage;
            }
        }
        return best;
    }

    // A piston behind the target at head height with the far side open. The head strikes
    // the target's head and carries the whole body out of the hole.
    private Setup pushSetup(Player target, boolean blocks, boolean torches) {
        BlockPos feet = target.blockPosition();
        BlockPos head = feet.above();
        if (!BlockUtil.state(head).isAir()) {
            return null;
        }
        Setup best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            if (!shovable(target, feet, facing)) {
                continue;
            }
            BlockPos piston = head.relative(facing.getOpposite());
            boolean reuse = pistonWaiting(piston, facing);
            double distance = BlockUtil.distanceTo(piston);
            if (!reuse && !pistonFits(piston) || distance >= bestDistance) {
                continue;
            }
            Setup setup = withPower(piston, facing, reuse, null, null, blocks, torches, null);
            if (setup != null) {
                best = setup;
                bestDistance = distance;
            }
        }
        return best;
    }

    // True when a full push along the facing carries the target's body clear of its block.
    private static boolean shovable(Player target, BlockPos feet, Direction facing) {
        AABB body = target.getBoundingBox();
        double needed = switch (facing) {
            case EAST -> feet.getX() + 1 - body.minX;
            case WEST -> body.maxX - feet.getX();
            case SOUTH -> feet.getZ() + 1 - body.minZ;
            default -> body.maxZ - feet.getZ();
        } + PUSH_SLACK;
        Vec3 shove = facing.getUnitVec3().scale(PUSH_REACH);
        Vec3 moved = Entity.collideBoundingBox(target, shove, body, mc.level, List.of());
        return needed <= PUSH_REACH && moved.dot(facing.getUnitVec3()) >= needed;
    }

    // A piston left from a round that never got its power. It already faces the right way.
    private boolean pistonWaiting(BlockPos pos, Direction facing) {
        BlockState state = BlockUtil.state(pos);
        return state.getBlock() instanceof PistonBaseBlock && inPlaceReach(pos) && !powered(pos)
            && state.getValue(PistonBaseBlock.FACING) == facing && !state.getValue(PistonBaseBlock.EXTENDED);
    }

    private boolean pistonFits(BlockPos pos) {
        return inPlaceReach(pos) && !powered(pos) && BlockUtil.blockFits(pos);
    }

    private boolean inPlaceReach(BlockPos pos) {
        return BlockUtil.distanceTo(pos) <= placeRange.getValue();
    }

    // A piston placed here would fire at once and leave no time for the crystal.
    private static boolean powered(BlockPos pos) {
        return mc.level.hasNeighborSignal(pos) || mc.level.hasNeighborSignal(pos.above());
    }

    // A spot beside the piston for the power. A torch is tried first since any blast takes
    // it away and the spot is free for the next round. A redstone block often survives and
    // would fire the next piston the moment it went down. The front is where the head goes.
    private Setup withPower(BlockPos piston, Direction facing, boolean reuse, BlockPos crystal, Vec3 blast,
                            boolean blocks, boolean torches, AABB crystalBox) {
        for (boolean torch : new boolean[] {true, false}) {
            if (torch ? !torches : !blocks) {
                continue;
            }
            for (Direction side : Direction.values()) {
                BlockPos power = piston.relative(side);
                if (side != facing && inPlaceReach(power)
                    && (torch ? torchFits(power, side) : redstoneBlockFits(power, crystalBox))) {
                    return new Setup(piston, facing, reuse, crystal, power, torch, blast);
                }
            }
        }
        return null;
    }

    // A standing torch on top of the piston would power nothing. A crystal overlapping the spot does not stop it.
    private static boolean torchFits(BlockPos pos, Direction side) {
        return side != Direction.UP && BlockUtil.torchStands(pos);
    }

    // The crystal goes down first and the server refuses a block inside it.
    private static boolean redstoneBlockFits(BlockPos pos, AABB crystalBox) {
        return BlockUtil.blockFits(pos) && (crystalBox == null || !crystalBox.intersects(new AABB(pos)));
    }

    private static boolean crystalFits(BlockPos crystal) {
        return mc.level.getEntities((Entity) null, BlockUtil.crystalSpace(crystal)).isEmpty();
    }

    private static AABB crystalBox(BlockPos crystal) {
        return EntityTypes.END_CRYSTAL.getDimensions().makeBoundingBox(Vec3.atBottomCenterOf(crystal));
    }

    // Where the crystal stops once the head has carried it as far as the blocks allow.
    private static Vec3 pushedCrystal(BlockPos crystal, Direction facing) {
        Vec3 start = Vec3.atBottomCenterOf(crystal);
        Vec3 shove = facing.getUnitVec3().scale(PUSH_REACH);
        return start.add(Entity.collideBoundingBox(CollisionContext.empty(), shove, crystalBox(crystal),
            mc.level, List.of()));
    }

    private boolean selfSafe(Vec3 blast) {
        return ExplosionUtil.selfSafe(blast, ExplosionUtil.CRYSTAL_POWER, maxSelfDamage.getFloat(),
            antiSuicide.isOn());
    }

    // Hits the crystal once the head has carried it into place or the wait runs out.
    private void watchCrystal() {
        int waited = mc.player.tickCount - pending.firedAt();
        EndCrystal crystal = pendingCrystal();
        if (crystal == null) {
            status = "(waiting)";
            if (waited > PUSH_WAIT) {
                pending = null;
            }
            return;
        }
        if (crystal.position().distanceTo(pending.blast()) > ARRIVED && waited <= PUSH_WAIT) {
            status = "(pushing)";
            return;
        }
        if (EntityUtil.reachDistance(mc.player, crystal) <= breakRange.getValue() && selfSafe(crystal.position())) {
            mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
            swing.getValue().swing();
        }
        pending = null;
    }

    // The crystal nearest where the pushed one should end up. It may still sit where it went down.
    private EndCrystal pendingCrystal() {
        AABB around = new AABB(pending.crystal()).inflate(PUSH_REACH);
        EndCrystal nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.level.getEntities((Entity) null, around, entity -> entity instanceof EndCrystal)) {
            double distance = entity.position().distanceTo(pending.blast());
            if (!entity.isRemoved() && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = (EndCrystal) entity;
            }
        }
        return nearest;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        Setup shown = planned;
        if (!render.isOn() || shown == null) {
            return;
        }
        style.draw(event.getBatch(), shown.piston(), false);
        style.draw(event.getBatch(), shown.power(), false);
        if (shown.crystal() != null) {
            style.draw(event.getBatch(), shown.crystal(), false);
        }
    }
}
