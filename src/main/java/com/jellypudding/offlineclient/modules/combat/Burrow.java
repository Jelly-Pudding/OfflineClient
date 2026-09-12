package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KeyPressEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.misc.Timer;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public final class Burrow extends Module {

    // Rungs of the packet lift. The last one clears the block completely.
    private static final double[] LIFT = {0.42, 0.75, 1.01, 1.16};

    private static final int JUMP_TIMEOUT = 20;

    private static final String TIMER_KEY = "burrow";

    public enum Lift { JUMP, PACKET }

    public enum Source { LIST, HELD }

    private final EnumSetting<Source> source = new EnumSetting<>("Block",
        "Where the block comes from.", Source.LIST)
        .describe(Source.LIST, "The first block from the list below that is in your hotbar.")
        .describe(Source.HELD, "Whatever block is in your main hand.");
    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN, Blocks.ENDER_CHEST));
    private final EnumSetting<Lift> lift = new EnumSetting<>("Lift",
        "How to get above the block as it goes in.", Lift.PACKET)
        .describe(Lift.JUMP, "Jumps for real.")
        .describe(Lift.PACKET, "Sends position packets to hop up without moving on screen.");
    private final BoolSetting automatic = new BoolSetting("Automatic",
        "Burrow the moment the module turns on. Off waits for you to press jump.", true);
    private final NumberSetting triggerHeight = new NumberSetting("Trigger height",
        "How high a jump has to carry you before the block goes in.", 1.02, 0.01, 1.4, 0.01, " blocks")
        .under(lift, Lift.JUMP);
    private final NumberSetting rubberband = new NumberSetting("Rubberband height",
        "Size of the refused move that puts you back inside the block.", 12, -30, 30, 1, " blocks");
    private final NumberSetting timer = new NumberSetting("Timer",
        "Game speed whilst the module runs. One leaves it alone.", 1, 1, 10, 0.5, "x");
    private final BoolSetting onlyInHoles = new BoolSetting("Only in holes",
        "Refuse to burrow unless you stand in a blast proof hole.", false);
    private final BoolSetting center = new BoolSetting("Centre",
        "Snap to the middle of your block first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards the block as it goes down.", true);

    private final SlotSwap slots = new SlotSwap();
    private BlockPos anchor;
    private int waited;

    // True once the burrow has been set off. Manual mode waits for the jump key.
    private boolean armed;

    public Burrow() {
        super("Burrow", "Places a blast proof block inside your own hitbox.", Category.COMBAT);
        blocks.under(source, Source.LIST);
        addSettings(source, blocks, lift, automatic, triggerHeight, rubberband, timer,
            onlyInHoles, center, rotate);
        searchTags("clip", "obsidian", "crystal", "hole");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    protected void onEnable() {
        waited = 0;
        anchor = null;
        armed = false;
        slots.forget();
        if (!inGame() || mc.player.isSpectator()) {
            setEnabled(false);
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        if (!BlockUtil.isReplaceable(feet)) {
            ChatUtil.error("Already inside a block.");
            setEnabled(false);
            return;
        }
        if (!headroom(feet)) {
            ChatUtil.error("Not enough room above to burrow.");
            setEnabled(false);
            return;
        }
        if (onlyInHoles.isOn() && !BlockUtil.playerInHole()) {
            ChatUtil.error("Not in a hole.");
            setEnabled(false);
            return;
        }
        if (findSlot() == -1) {
            ChatUtil.error(source.is(Source.HELD)
                ? "No block in your main hand." : "No burrow block in your hotbar.");
            setEnabled(false);
            return;
        }
        anchor = feet;
        Timer.override(TIMER_KEY, timer.getFloat());
        if (!automatic.isOn()) {
            ChatUtil.message("Waiting for a jump.");
            return;
        }
        arm();
    }

    @Override
    protected void onDisable() {
        Timer.override(TIMER_KEY, 1f);
    }

    // Sets the burrow off. A real jump starts here and the packet lift needs nothing yet.
    private void arm() {
        armed = true;
        waited = 0;
        if (lift.is(Lift.JUMP) && mc.player.onGround()) {
            mc.player.jumpFromGround();
        }
    }

    // Manual mode goes off the jump key. Vanilla jumps on the same press.
    @Subscribe
    private void onKeyPress(KeyPressEvent event) {
        if (!inGame() || armed || event.getAction() != GLFW.GLFW_PRESS || mc.gui.screen() != null) {
            return;
        }
        if (InputUtil.isKey(mc.options.keyJump, event.getKey())) {
            anchor = mc.player.blockPosition();
            arm();
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator() || anchor == null) {
            setEnabled(false);
            return;
        }
        if (!armed) {
            // The player may walk to another block before the jump.
            if (mc.player.onGround()) {
                anchor = mc.player.blockPosition();
            }
            return;
        }
        if (lift.is(Lift.JUMP) && mc.player.getY() < anchor.getY() + triggerHeight.getValue()) {
            if (++waited > JUMP_TIMEOUT) {
                ChatUtil.error("Could not get high enough to burrow.");
                setEnabled(false);
            }
            return;
        }
        burrow();
        setEnabled(false);
    }

    private void burrow() {
        if (center.isOn()) {
            BlockUtil.centerPlayer();
        }
        if (lift.is(Lift.PACKET)) {
            for (double step : LIFT) {
                sendLift(step);
            }
        }

        int slot = findSlot();
        if (slot == -1) {
            ChatUtil.error("No burrow block to hand.");
            return;
        }
        slots.select(slot);
        // The local placement is refused but the packet still goes out. The server
        // has the player up on the lift by then.
        BlockUtil.placeDirect(anchor, rotate.isOn(), true);
        slots.restore();

        if (lift.is(Lift.PACKET)) {
            sendLift(rubberband.getValue());
        } else {
            mc.player.absSnapTo(mc.player.getX(), mc.player.getY() + rubberband.getValue(), mc.player.getZ());
        }
    }

    private void sendLift(double offset) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY() + offset, mc.player.getZ(),
            false, mc.player.horizontalCollision));
    }

    private static boolean headroom(BlockPos feet) {
        return BlockUtil.isReplaceable(feet.above()) && BlockUtil.isReplaceable(feet.above(2));
    }

    private int findSlot() {
        if (source.is(Source.HELD)) {
            return mc.player.getMainHandItem().getItem() instanceof BlockItem
                ? InventoryUtil.selectedSlot() : -1;
        }
        return BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> true);
    }
}
