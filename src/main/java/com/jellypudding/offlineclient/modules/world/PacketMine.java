package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.BreakTrail;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.ListMode;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.BreakArea;
import com.jellypudding.offlineclient.util.BreakSlots;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.PacketBreaker;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

// Breaks the blocks you click with a start and stop pair instead of a held click. The server
// finishes each one on its own and the queue moves to the next. An area around each clicked
// block can come down with it.
public final class PacketMine extends Module {

    // Ticks of grace before a block or an area that will not break is given up on.
    private static final int PATIENCE_TICKS = 50;

    private static final int MAX_QUEUE = 16;

    public enum Plane { AUTO, FLAT, UPRIGHT }

    public enum Side { LEFT, RIGHT }

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait after a click before the packets go out.", 1, 0, 20, 1, " ticks").min(0);
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds the tool AutoTool would pick or else your fastest one.", true);
    private final BoolSetting notOnUse = new BoolSetting("Not on use",
        "Holds off the tool swap and the break packets whilst you are using an item.", true).under(autoTool);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final EnumSetting<BreakArea> area = new EnumSetting<>("Area",
        "The blocks that come down around each one you click.", BreakArea.SINGLE)
        .describe(BreakArea.SINGLE, "Only the block you click.")
        .describe(BreakArea.SIDE, "The block you click and one beside it on the side you pick.")
        .describe(BreakArea.BOTH_SIDES, "The block you click and one on each side.")
        .describe(BreakArea.PLUS, "A plus sign of five blocks across the plane.")
        .describe(BreakArea.SQUARE, "A three by three square across the plane.")
        .describe(BreakArea.SQUARE_AND_POLES, "The square and one more block through its middle each way.")
        .describe(BreakArea.ROUNDED_CUBE, "A three by three by three cube without its eight corners.")
        .describe(BreakArea.CUBE, "The whole three by three by three cube.");
    private final EnumSetting<Side> side = new EnumSetting<>("Side",
        "Which side the extra block comes from.", Side.RIGHT)
        .describe(Side.LEFT, "Your left as you face the block.")
        .describe(Side.RIGHT, "Your right as you face the block.")
        .under(area, BreakArea.SIDE);
    private final EnumSetting<Plane> plane = new EnumSetting<>("Plane",
        "Which way the area lies.", Plane.AUTO)
        .describe(Plane.AUTO, "Lies across the face you click and flat for a top or bottom face.")
        .describe(Plane.FLAT, "Always level with the ground.")
        .describe(Plane.UPRIGHT, "Always standing across your view.")
        .under(area, () -> !area.isAny(BreakArea.SINGLE, BreakArea.CUBE));
    private final BoolSetting rightTool = new BoolSetting("Right tool only",
        "Leaves the blocks around your click alone when your tool is the wrong kind for them.", true)
        .under(area, () -> !area.is(BreakArea.SINGLE));
    private final EnumSetting<ListMode> listMode = ListMode.setting("List mode", ListMode.BLACKLIST,
        "The area only takes the listed blocks.", "The area leaves the listed blocks alone.")
        .under(area, () -> !area.is(BreakArea.SINGLE));
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "The blocks the list applies to around your click. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of())
        .under(area, () -> !area.is(BreakArea.SINGLE));
    private final BoolSetting rebreak = new BoolSetting("Rebreak",
        "Stays on the spot and breaks whatever is put back until you click another block.", false);
    private final BoolSetting instantRebreak = new BoolSetting("Instant rebreak",
        "Fires at the spot to catch a replacement the moment it lands.", true)
        .under(rebreak);
    private final NumberSetting rebreakDelay = new NumberSetting("Rebreak delay",
        "Ticks between the shots at the spot.", 0, 0, 20, 1, " ticks").min(0)
        .under(instantRebreak);
    private final BoolSetting onlyPickaxe = new BoolSetting("Only pickaxe",
        "Only fires whilst a pickaxe is in your main hand.", true).under(instantRebreak);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws a box on each block in the queue and around it.", true);
    private final BoxStyle miningBox = new BoxStyle("Mining", BoxStyle.Shape.BOTH, 0)
        .under(render);
    private final BoxStyle readyBox = new BoxStyle("Ready", BoxStyle.Shape.BOTH, 120)
        .under(render);
    private final BoxStyle areaBox = new BoxStyle("Area", BoxStyle.Shape.LINES, 30)
        .under(render);
    private final BoxStyle rebreakBox = new BoxStyle("Rebreak", BoxStyle.Shape.BOTH, 0)
        .under(render);
    private final BreakTrail trail = new BreakTrail();

    // One clicked block waiting its turn or being broken or kept clear.
    private static final class Target {

        private final BlockPos pos;
        // How the block was clicked. The area is worked out from these each tick.
        private final Direction face;
        private final Direction facing;
        private Block block;
        private int wait;
        private boolean mining;
        // Broken at least once. Under Rebreak the spot is then kept clear.
        private boolean cleared;
        private int startTick;
        private int sincePoke;
        private int areaLeft;
        private int areaStall;

        private Target(BlockPos pos, Direction face, Direction facing, int wait) {
            this.pos = pos;
            this.face = face;
            this.facing = facing;
            this.wait = wait;
        }
    }

    private final List<Target> queue = new ArrayList<>();

    private final SlotSwap slots = new SlotSwap();
    private final PacketBreaker areaBreaker = new PacketBreaker();

    // Only a fresh press picks a target.
    private boolean attackHeld;

    public PacketMine() {
        super("PacketMine", "Breaks each block you click and any area around it whilst you do other things.",
            Category.WORLD);
        addSettings(delay, autoTool, notOnUse, rotate, swing, area, side, plane, rightTool, listMode, blocks,
            rebreak, instantRebreak, rebreakDelay, onlyPickaxe, render);
        addSettings(miningBox.settings());
        addSettings(readyBox.settings());
        addSettings(areaBox.settings());
        addSettings(rebreakBox.settings());
        addSettings(trail.settings());
        searchTags("obsidian", "packet mine", "instant mine", "queue", "fast mine", "area", "3x3",
            "hammer", "rebreak");
    }

    @Override
    public String getSuffix() {
        if (queue.isEmpty()) {
            return "no target";
        }
        Target head = queue.getFirst();
        if (head.cleared && !head.mining) {
            return rebreak.isOn() ? "rebreak" : "area";
        }
        if (!head.mining) {
            return "waiting";
        }
        String amount = queue.size() > 1 ? " x" + queue.size() : "";
        return Math.min(100, (int) (progress(head) * 100)) + "%" + amount;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        queue.clear();
        areaBreaker.reset();
        trail.clear();
        attackHeld = InputUtil.physicallyHeld(mc.options.keyAttack);
    }

    @Override
    protected void onDisable() {
        slots.restoreIfMine();
        queue.clear();
        areaBreaker.reset();
        trail.clear();
    }

    // Samples the attack key after the game has handled this tick's clicks.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        attackHeld = InputUtil.physicallyHeld(mc.options.keyAttack);
    }

    // A fresh left click on a block puts it at the back of the queue. Under Rebreak it also
    // moves the kept spot on to the new block.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (attackHeld || BlockMiner.isSelfCall() || !inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        if (queued(pos) || !BlockUtil.isBreakable(pos)) {
            return;
        }
        if (rebreak.isOn()) {
            queue.removeIf(target -> target.cleared);
        }
        if (queue.size() >= MAX_QUEUE) {
            return;
        }
        queue.add(new Target(pos, clickedFace(pos), mc.player.getDirection(), delay.getInt()));
    }

    // The face under the crosshair. The nearest face stands in once the crosshair has moved off.
    private static Direction clickedFace(BlockPos pos) {
        BlockHitResult hit = BlockUtil.aimedBlock();
        return hit != null && hit.getBlockPos().equals(pos) ? hit.getDirection() : BlockUtil.facingSide(pos);
    }

    private boolean queued(BlockPos pos) {
        for (Target target : queue) {
            if (target.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        trail.tick();
        if (!inGame() || mc.player.isSpectator()) {
            queue.clear();
            return;
        }
        queue.removeIf(this::finished);
        if (queue.isEmpty()) {
            slots.restoreIfMine();
            return;
        }
        // The server breaks a block with whatever is in hand and the tool stays put whilst you eat.
        if (holdsOff()) {
            return;
        }
        Target head = queue.getFirst();
        // The clicked block goes first. A stop at a kept spot has to reach the server
        // before anything around it moves the server's target.
        boolean sent = driveCentre(head);
        sent |= breakArea(head);
        if (sent) {
            swing.getValue().swing();
        }
    }

    // True when break packets went out for the clicked block this tick.
    private boolean driveCentre(Target head) {
        BlockState state = BlockUtil.state(head.pos);
        if (head.mining) {
            // The server breaks the block with whatever is in hand on the tick it finishes.
            holdTool(state);
            return false;
        }
        if (state.isAir()) {
            if (canPoke(head)) {
                poke(head);
            }
            return false;
        }
        if (head.wait > 0) {
            head.wait--;
            return false;
        }
        return start(head, state);
    }

    // True once the entry is done with or can never finish.
    private boolean finished(Target target) {
        if (!BlockUtil.serverReaches(target.pos)) {
            return true;
        }
        BlockState state = BlockUtil.state(target.pos);
        // A block can go before its own pair does. The click itself breaks a one hit block.
        if (state.isAir() || (target.mining && state.getBlock() != target.block)) {
            target.mining = false;
            target.block = null;
            target.cleared = true;
        }
        // A later click whose block the head's area took waits its turn for an area of its own.
        if (target.cleared && !target.mining) {
            return target == queue.getFirst() && !rebreak.isOn() && areaDone(target);
        }
        if (!target.mining && !BlockUtil.isBreakable(target.pos)) {
            return true;
        }
        return target.mining && progress(target) > 2
            && mc.player.tickCount - target.startTick > PATIENCE_TICKS;
    }

    // Nothing is left around the block or nothing has come down for too long.
    private boolean areaDone(Target target) {
        BlockPos slow = areaBreaker.slowBlock();
        int patience = PATIENCE_TICKS + (slow == null ? 0 : BlockUtil.breakTicks(slow));
        return target.areaLeft == 0 || target.areaStall > patience;
    }

    // The pair goes out once the server has an angle on the block and room to take it.
    private boolean start(Target target, BlockState state) {
        Vec3 aim = BlockUtil.hitPoint(target.pos, BlockUtil.facingSide(target.pos));
        if (rotate.isOn() && !RotationManager.look(aim, RotationPriority.MINE, RotationManager.BLOCK_TOLERANCE)) {
            return false;
        }
        holdTool(state);
        // A slow start elsewhere would leave a break by hand unanswered and the block would come back.
        if (!BlockUtil.canInstantBreak(target.pos) && BreakSlots.busyElsewhere(target.pos)) {
            return false;
        }
        if (!BlockUtil.breaksInOneTick(target.pos) && !BreakSlots.parkingFree()) {
            return false;
        }
        BlockMiner.breakInstantly(target.pos);
        trail.add(target.pos);
        target.block = state.getBlock();
        target.startTick = mc.player.tickCount;
        target.mining = true;
        return true;
    }

    // True on the ticks a shot at the empty spot is due.
    private boolean canPoke(Target target) {
        if (!rebreak.isOn() || !instantRebreak.isOn()
            || (onlyPickaxe.isOn() && !mc.player.getMainHandItem().is(ItemTags.PICKAXES))) {
            return false;
        }
        if (target.sincePoke < rebreakDelay.getInt()) {
            target.sincePoke++;
            return false;
        }
        target.sincePoke = 0;
        return true;
    }

    // A stop at the empty spot breaks a replacement the moment it lands. The server counts it
    // from the last start and only whilst the spot is its target. Once the blocks around it
    // are done a start on the empty spot makes it the target again.
    private void poke(Target target) {
        if (BreakSlots.isTarget(target.pos)) {
            BlockMiner.sendStop(target.pos);
        } else if (target.areaLeft == 0 && !BreakSlots.busyElsewhere(target.pos)) {
            BlockMiner.sendStart(target.pos);
        }
    }

    // The blocks around the clicked one come down once its own pair has gone out. True when
    // any went out this tick.
    private boolean breakArea(Target head) {
        if (!head.mining && !head.cleared) {
            return false;
        }
        List<BlockPos> around = areaOf(head);
        areaBreaker.tick(around::contains);
        int left = 0;
        boolean sent = false;
        for (BlockPos pos : around) {
            if (!areaWanted(pos)) {
                continue;
            }
            left++;
            if (areaBreaker.canSend(pos)) {
                areaBreaker.send(pos);
                trail.add(pos);
                sent = true;
            }
        }
        // The clock only runs once the clicked block itself is gone.
        head.areaStall = head.mining || left < head.areaLeft ? 0 : head.areaStall + 1;
        head.areaLeft = left;
        return sent;
    }

    // Worked out from the current settings every time. A change reaches a kept spot at once.
    private List<BlockPos> areaOf(Target target) {
        BreakArea pattern = area.getValue();
        if (pattern == BreakArea.SINGLE) {
            return List.of();
        }
        boolean flat = switch (plane.getValue()) {
            case AUTO -> target.face.getAxis().isVertical();
            case FLAT -> true;
            case UPRIGHT -> false;
        };
        // Looking into a side face decides left and right for an area stood across it.
        Direction ahead = plane.is(Plane.AUTO) && !flat ? target.face.getOpposite() : target.facing;
        return pattern.around(target.pos, ahead, flat, side.is(Side.RIGHT));
    }

    private boolean areaWanted(BlockPos pos) {
        if (!BlockUtil.diggable(pos) || BlockUtil.isStandingOn(pos) || !BlockUtil.serverReaches(pos)) {
            return false;
        }
        BlockState state = BlockUtil.state(pos);
        if (!listMode.getValue().admits(blocks.contains(state.getBlock()))) {
            return false;
        }
        return !rightTool.isOn() || ItemUtil.suits(mc.player.getMainHandItem(), state);
    }

    // A client side estimate of how far along the server is.
    private double progress(Target target) {
        if (!target.mining || !inGame()) {
            return 0;
        }
        BlockState state = BlockUtil.state(target.pos);
        if (state.isAir()) {
            return 1;
        }
        return state.getDestroyProgress(mc.player, mc.level, target.pos)
            * (mc.player.tickCount - target.startTick + 1);
    }

    private void holdTool(BlockState state) {
        if (autoTool.isOn()) {
            ItemUtil.holdBestTool(state, slots);
        }
    }

    private boolean holdsOff() {
        return autoTool.isOn() && notOnUse.isOn() && mc.player.isUsingItem();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        trail.draw(event.getBatch(), event.getPartialTicks());
        if (!render.isOn() || !inGame() || queue.isEmpty()) {
            return;
        }
        for (Target target : queue) {
            if (target.mining || !target.cleared) {
                drawProgress(event.getBatch(), target);
            } else if (rebreak.isOn() && instantRebreak.isOn()) {
                rebreakBox.draw(event.getBatch(), target.pos, true);
            }
        }
        Target head = queue.getFirst();
        for (BlockPos pos : areaOf(head)) {
            if (areaWanted(pos)) {
                areaBox.draw(event.getBatch(), pos, true);
            }
        }
    }

    // The box fills from the bottom as the estimate climbs.
    private void drawProgress(DrawBatch batch, Target target) {
        double done = Math.clamp(progress(target), 0, 1);
        BoxStyle style = done >= 1 ? readyBox : miningBox;
        AABB box = DrawBatch.blockBox(target.pos);
        if (style.drawsLines()) {
            batch.outlineBox(box, style.lineColor(), true);
        }
        if (style.drawsSides() && done > 0) {
            AABB filled = new AABB(box.minX, box.minY, box.minZ,
                box.maxX, box.minY + (box.maxY - box.minY) * done, box.maxZ);
            batch.solidBox(filled, style.fillColor(), true);
        }
    }
}
