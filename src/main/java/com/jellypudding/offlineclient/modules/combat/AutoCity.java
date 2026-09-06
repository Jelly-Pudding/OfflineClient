package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.ExclusivityGroup;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockMiner;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.ItemUtil;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.TargetPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

// Strips the blast proof cover from an enemy standing in a hole.
public final class AutoCity extends Module {

    public enum Mode { HOLD, PACKET, SILENT }

    private final NumberSetting targetRange = new NumberSetting("Target range",
        "How far away enemies are considered.", 6, 1, 10, 0.5, " blocks");
    private final EnumSetting<TargetPriority> priority = TargetPriority.setting("Cities",
        TargetPriority.NEAREST);
    private final NumberSetting breakRange = new NumberSetting("Break range",
        "How far you can reach to mine.", 4.5, 1, 6, 0.1, " blocks");
    private final NumberSetting placeRange = new NumberSetting("Place range",
        "How far you can reach to place the support block.", 4.5, 1, 6, 0.1, " blocks");
    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the block is mined.", Mode.HOLD)
        .describe(Mode.HOLD, "Mine it like a held left click with the tool in hand throughout.")
        .describe(Mode.PACKET, "Send the start and stop at once and keep the tool out whilst the server counts.")
        .describe(Mode.SILENT, "Send the start and stop at once and put the tool away until the last packet.");
    private final BoolSetting switchTool = new BoolSetting("Switch tool",
        "Swap to your fastest hotbar tool first.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting support = new BoolSetting("Support",
        "Fill the empty block under the city block to give a crystal a base.", true);
    private final BoolSetting chatInfo = new BoolSetting("Chat info",
        "Say why the module stopped.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turn towards the block on the server side.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turn off once the block is gone.", true);
    private final BoolSetting render = new BoolSetting("Show target",
        "Outline the block being mined.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 0f).under(render);

    private final SlotSwap slots = new SlotSwap();
    private BlockPos current;
    private String targetName;
    // How far along the server should be with the packet pair it was sent.
    private float progress;
    private boolean sent;

    public AutoCity() {
        super("AutoCity", "Mines the block guarding an enemy in a hole.", Category.COMBAT);
        addSettings(targetRange, priority, breakRange, placeRange, mode, switchTool, swing, support,
            chatInfo, rotate, toggleOff, render);
        addSettings(style.settings());
        searchTags("city", "surround", "obsidian");
    }

    @Override
    public String getSuffix() {
        return targetName;
    }

    @Override
    public ExclusivityGroup getExclusivityGroup() {
        return ExclusivityGroup.MINING;
    }

    @Override
    protected void onEnable() {
        current = null;
        slots.forget();
        targetName = null;
        progress = 0;
        sent = false;
    }

    @Override
    protected void onDisable() {
        stopMining();
        targetName = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }

        Player target = EntityUtil.bestEnemy(targetRange.getValue(), priority.getValue());
        targetName = EntityUtil.nameOf(target);
        if (target == null) {
            stopMining();
            return;
        }

        if (current != null && BlockUtil.state(current).isAir()) {
            forgetBlock();
            if (toggleOff.isOn()) {
                if (chatInfo.isOn()) {
                    ChatUtil.message("§bAutoCity §7took the block down.");
                }
                setEnabled(false);
                return;
            }
        }
        if (current != null && BlockUtil.distanceTo(current) > breakRange.getValue()) {
            stopMining();
        }
        if (current == null) {
            current = cityBlock(target);
        }
        if (current == null) {
            return;
        }
        // A crystal needs a base under the gap the city leaves behind.
        if (support.isOn() && placeSupport()) {
            return;
        }
        if (mode.is(Mode.HOLD)) {
            holdMine();
        } else {
            packetMine();
        }
    }

    private void holdMine() {
        if (switchTool.isOn()) {
            ItemUtil.selectBestTool(BlockUtil.state(current), slots);
        }
        if (!BlockMiner.mine(current, rotate.isOn())) {
            stopMining();
        }
    }

    // The pair goes out once with the tool in hand. The server counts from there so
    // the final stop only has to arrive once the client reckons the count is done.
    private void packetMine() {
        BlockState state = BlockUtil.state(current);
        int tool = switchTool.isOn() ? ItemUtil.bestToolSlot(state) : -1;
        ItemStack toolStack = tool == -1
            ? mc.player.getMainHandItem() : mc.player.getInventory().getItem(tool);
        if (!sent) {
            withTool(tool, () -> {
                BlockMiner.breakInstantly(current);
                swing.getValue().swing();
            });
            sent = true;
            progress = 0;
            return;
        }
        progress += BlockUtil.breakDelta(toolStack, current);
        if (progress < 1) {
            return;
        }
        withTool(tool, () -> {
            Direction side = BlockUtil.facingSide(current);
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, current, side));
            swing.getValue().swing();
        });
        // A miss keeps the count going and tries again next tick.
        progress = 0.5f;
    }

    // Runs the action with the tool in hand. Silent mode hands the slot back after.
    private void withTool(int tool, Runnable action) {
        if (rotate.isOn()) {
            BlockUtil.faceVector(BlockUtil.hitPoint(current, BlockUtil.facingSide(current)));
        }
        if (tool != -1) {
            slots.select(tool);
        }
        action.run();
        if (mode.is(Mode.SILENT)) {
            slots.restore();
        }
    }

    // Fills the hole under the block about to fall.
    // True when a block went down this tick and mining should wait.
    private boolean placeSupport() {
        BlockPos below = current.below();
        if (!BlockUtil.isReplaceable(below) || BlockUtil.intersectsPlayer(below)) {
            return false;
        }
        if (BlockUtil.distanceTo(below) > placeRange.getValue()) {
            return false;
        }
        int slot = BlockUtil.findBlockSlot();
        if (slot == -1) {
            return false;
        }
        slots.select(slot);
        boolean placed = BlockUtil.placeAny(below, rotate.isOn(), true);
        slots.restore();
        return placed;
    }

    private BlockPos cityBlock(Player target) {
        return cityBlock(target, breakRange.getValue());
    }

    // The blast proof block beside the target's feet that is closest to the player.
    public static BlockPos cityBlock(Player target, double reach) {
        BlockPos feet = target.blockPosition();
        BlockPos best = null;
        double bestDistance = reach;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            BlockState state = BlockUtil.state(pos);
            if (state.isAir() || !BlockUtil.isBreakable(pos)
                || state.getBlock().getExplosionResistance() < BlockUtil.BLAST_PROOF) {
                continue;
            }
            double distance = BlockUtil.distanceTo(pos);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private void forgetBlock() {
        slots.restore();
        current = null;
        progress = 0;
        sent = false;
    }

    private void stopMining() {
        if (current != null && mode.is(Mode.HOLD)) {
            BlockMiner.release();
        }
        if (current != null && sent) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, current, Direction.DOWN));
        }
        forgetBlock();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || current == null || BlockUtil.state(current).isAir()) {
            return;
        }
        style.draw(event.getBatch(), current, false);
    }
}
