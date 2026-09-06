package com.jellypudding.offlineclient.modules.world;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.BlockBreakEvent;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

// Breaks the blocks you click with a start and stop pair instead of a held click.
// The server finishes each one on its own and the queue moves to the next.
public final class PacketMine extends Module {

    // Ticks of grace before a block that will not break is given up on.
    private static final int PATIENCE_TICKS = 50;

    private static final int MAX_QUEUE = 16;

    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait after a click before the packets go out.", 1, 0, 20, 1, " ticks").min(0);
    private final BoolSetting autoTool = new BoolSetting("Auto tool",
        "Holds your fastest tool whilst the block breaks.", true);
    private final BoolSetting notOnUse = new BoolSetting("Not on use",
        "Holds off the tool swap whilst you are using an item.", true).under(autoTool);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);
    private final BoolSetting obscure = new BoolSetting("Obscure progress",
        "Sends an abort every tick so others do not see the cracks.", false);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws a box on each block in the queue.", true);
    private final BoxStyle miningBox = new BoxStyle("Mining", BoxStyle.Shape.BOTH, 0)
        .under(render);
    private final BoxStyle readyBox = new BoxStyle("Ready", BoxStyle.Shape.BOTH, 120)
        .under(render);
    private final BoolSetting rebreak = new BoolSetting("Rebreak",
        "Stays on the spot and starts again on whatever is put back.", false);
    private final BoolSetting instantRebreak = new BoolSetting("Instant rebreak",
        "Fires at the spot to catch a replacement the moment it lands.", true)
        .under(rebreak);
    private final NumberSetting rebreakDelay = new NumberSetting("Rebreak delay",
        "Ticks between the shots at the spot.", 0, 0, 20, 1, " ticks").min(0)
        .under(instantRebreak);
    private final BoolSetting onlyPickaxe = new BoolSetting("Only pickaxe",
        "Only fires whilst a pickaxe is in your main hand.", true).under(instantRebreak);
    private final BoxStyle rebreakBox = new BoxStyle("Rebreak", BoxStyle.Shape.BOTH, 0)
        .under(instantRebreak);

    // One block waiting its turn or being broken.
    private static final class Target {

        private final BlockPos pos;
        private final Direction side;
        private Block block;
        private int wait;
        private boolean mining;
        private int startTick;
        private int sincePoke;

        private Target(BlockPos pos, Direction side, int wait) {
            this.pos = pos;
            this.side = side;
            this.wait = wait;
        }
    }

    private final List<Target> queue = new ArrayList<>();

    private final SlotSwap slots = new SlotSwap();

    // Only a fresh press picks a target.
    private boolean attackHeld;

    public PacketMine() {
        super("PacketMine", "Keeps breaking the blocks you clicked whilst you do other things.", Category.WORLD);
        addSettings(delay, autoTool, notOnUse, rotate, obscure, render);
        addSettings(miningBox.settings());
        addSettings(readyBox.settings());
        addSettings(rebreak, instantRebreak, rebreakDelay, onlyPickaxe);
        addSettings(rebreakBox.settings());
        searchTags("obsidian", "packet mine", "instant mine", "queue");
    }

    @Override
    public String getSuffix() {
        if (queue.isEmpty()) {
            return "no target";
        }
        Target head = queue.getFirst();
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
        attackHeld = InputUtil.physicallyHeld(mc.options.keyAttack);
    }

    @Override
    protected void onDisable() {
        if (inGame()) {
            for (Target target : queue) {
                if (target.mining) {
                    mc.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target.pos, target.side));
                }
            }
        }
        BlockMiner.release();
        slots.restoreIfMine();
        queue.clear();
    }

    // Samples the attack key after the game has handled this tick's clicks.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        attackHeld = InputUtil.physicallyHeld(mc.options.keyAttack);
    }

    // A fresh left click on a block puts it at the back of the queue.
    @Subscribe
    private void onBlockBreak(BlockBreakEvent event) {
        if (attackHeld || BlockMiner.isSelfCall() || !inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos pos = event.getPos().immutable();
        if (queued(pos) || !BlockUtil.isBreakable(pos) || queue.size() >= MAX_QUEUE) {
            return;
        }
        queue.add(new Target(pos, BlockUtil.facingSide(pos), delay.getInt()));
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
        if (!inGame() || mc.player.isSpectator()) {
            queue.clear();
            return;
        }
        queue.removeIf(this::finished);
        if (queue.isEmpty()) {
            slots.restoreIfMine();
            return;
        }
        Target head = queue.getFirst();
        BlockState state = BlockUtil.state(head.pos);
        if (head.mining) {
            holdTool(state);
            if (obscure.isOn()) {
                mc.player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, head.pos, head.side));
            }
            return;
        }
        // A broken block under Rebreak waits here for its replacement.
        if (state.isAir() || !BlockUtil.isBreakable(head.pos)) {
            if (canPoke(head)) {
                poke(head);
            }
            return;
        }
        if (head.wait > 0) {
            head.wait--;
            return;
        }
        start(head, state);
    }

    // True once the entry is done with or can never finish.
    private boolean finished(Target target) {
        if (BlockUtil.distanceTo(target.pos) > mc.player.blockInteractionRange()) {
            return true;
        }
        BlockState state = BlockUtil.state(target.pos);
        if (target.mining && (state.isAir() || state.getBlock() != target.block)) {
            if (!rebreak.isOn()) {
                return true;
            }
            target.mining = false;
            target.block = null;
            return false;
        }
        return target.mining && progress(target) > 2
            && mc.player.tickCount - target.startTick > PATIENCE_TICKS;
    }

    private void start(Target target, BlockState state) {
        // The pair goes out once the server has an angle on the block.
        if (rotate.isOn() && !RotationManager.look(BlockUtil.hitPoint(target.pos, target.side),
            RotationPriority.MINE, RotationManager.BLOCK_TOLERANCE)) {
            return;
        }
        holdTool(state);
        BlockMiner.breakInstantly(target.pos);
        mc.player.swing(InteractionHand.MAIN_HAND);
        target.block = state.getBlock();
        target.startTick = mc.player.tickCount;
        target.mining = true;
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

    // Breaks the replacement on the tick it lands. Ignored whilst the spot is empty.
    private void poke(Target target) {
        mc.player.connection.send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target.pos, Direction.UP));
        mc.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
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
        if (!autoTool.isOn() || (notOnUse.isOn() && mc.player.isUsingItem())) {
            return;
        }
        // A slot the player picked themselves is left alone.
        if (slots.isHolding() && !slots.stillMine()) {
            slots.forget();
            return;
        }
        ItemUtil.selectBestTool(state, slots);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        for (Target target : queue) {
            if (!target.mining && rebreak.isOn() && instantRebreak.isOn()) {
                rebreakBox.draw(event.getBatch(), target.pos, true);
                continue;
            }
            double done = Math.clamp(progress(target), 0, 1);
            BoxStyle style = done >= 1 ? readyBox : miningBox;
            AABB box = DrawBatch.blockBox(target.pos);
            if (style.drawsLines()) {
                event.getBatch().outlineBox(box, style.lineColor(), true);
            }
            if (style.drawsSides() && done > 0) {
                AABB filled = new AABB(box.minX, box.minY, box.minZ,
                    box.maxX, box.minY + (box.maxY - box.minY) * done, box.maxZ);
                event.getBatch().solidBox(filled, style.fillColor(), true);
            }
        }
    }
}
