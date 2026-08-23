package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Missing support under a side is filled first.
public final class Surround extends Module {

    // Blocks that are far too valuable to spend on a wall.
    private static final Set<Block> NEVER = Set.of(
        Blocks.ANCIENT_DEBRIS, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK);

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN));
    private final BoolSetting center = new BoolSetting("Center",
        "Snap to the middle of your block to line every side up.", true);
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Wait until you are standing on something.", true);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one tick.", 4, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 0, 0, 5, 1, " ticks");
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet toward each block as it goes down.", true);
    private final BoolSetting doubleHeight = new BoolSetting("Double height",
        "Also wall the four sides at head height to stop a face place.", false);
    private final BoolSetting toggleOnDeath = new BoolSetting("Toggle off on death",
        "Turn off when you die instead of walling your respawn.", true);
    private final BoolSetting toggleOnDone = new BoolSetting("Toggle off when done",
        "Turn off once all four sides are filled.", false);
    private final BoolSetting toggleOnMove = new BoolSetting("Toggle off on move",
        "Turn off if you leave the block you started on.", false);
    private final BoolSetting render = new BoolSetting("Show sides",
        "Outline the four side positions by how well they hold.", true);

    private int timer;
    private BlockPos anchor;
    private final SlotSwap slots = new SlotSwap();

    public Surround() {
        super("Surround", "Places blast proof blocks around your feet to stop crystals.", Category.COMBAT);
        addSettings(blocks, center, onlyOnGround, perTick, delay, rotate,
            doubleHeight, toggleOnDeath, toggleOnDone, toggleOnMove, render);
        searchTags("obsidian", "crystal", "hole");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        anchor = null;
        slots.forget();
        if (inGame()) {
            anchor = mc.player.blockPosition();
            if (center.isOn()) {
                BlockUtil.centerPlayer();
            }
        }
    }

    @Override
    protected void onDisable() {
        slots.restore();
        anchor = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (mc.player.isDeadOrDying()) {
            if (toggleOnDeath.isOn()) {
                setEnabled(false);
            }
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        if (!keepAnchor(feet)) {
            return;
        }
        if (onlyOnGround.isOn() && !mc.player.onGround()) {
            return;
        }
        if (timer > 0) {
            timer--;
            return;
        }

        List<BlockPos> missing = missingSides(feet);
        // Off the ground the floor of the pocket comes first.
        if (!mc.player.onGround() && canFill(feet.below())) {
            missing.add(0, feet.below());
        }
        if (missing.isEmpty()) {
            slots.restore();
            if (toggleOnDone.isOn()) {
                setEnabled(false);
            }
            return;
        }

        int slot = findBlastBlock();
        if (slot == -1) {
            slots.restore();
            return;
        }
        if (center.isOn()) {
            BlockUtil.centerPlayer();
        }
        if (placeRound(missing, slot) > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    /**
     * Keeps the wall on the block the player stands in. False once the
     * module has switched itself off.
     */
    private boolean keepAnchor(BlockPos feet) {
        // Null on the first tick after enabling from the GUI with no world loaded.
        if (anchor == null || feet.equals(anchor)) {
            anchor = feet;
            return true;
        }
        if (toggleOnMove.isOn()) {
            setEnabled(false);
            return false;
        }
        anchor = feet;
        return true;
    }

    private int placeRound(List<BlockPos> missing, int slot) {
        int placed = 0;
        for (BlockPos pos : missing) {
            if (placed >= perTick.getInt()) {
                break;
            }
            BlockPos target = pos;
            Direction support = BlockUtil.findPlaceSupport(pos);
            if (support == null) {
                BlockPos below = pos.below();
                Direction belowSupport = canFill(below) ? BlockUtil.findPlaceSupport(below) : null;
                if (belowSupport != null) {
                    target = below;
                    support = belowSupport;
                }
            }
            slots.select(slot);
            // Every side asks to turn and the rotation manager keeps the first one.
            boolean ok = support != null
                ? BlockUtil.place(target, support, rotate.isOn(), true)
                : BlockUtil.placeDirect(pos, rotate.isOn(), true);
            if (ok) {
                placed++;
            }
        }
        return placed;
    }

    private List<BlockPos> missingSides(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            if (canFill(pos)) {
                result.add(pos);
            }
        }
        // The lower ring goes down first. The pocket is sealed before it is raised.
        if (doubleHeight.isOn()) {
            BlockPos head = feet.above();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = head.relative(side);
                if (canFill(pos)) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    private boolean canFill(BlockPos pos) {
        if (!BlockUtil.isReplaceable(pos)) {
            return false;
        }
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        return mc.level.isUnobstructed(obsidian, pos, CollisionContext.empty());
    }

    private int findBlastBlock() {
        return BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> !NEVER.contains(block));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            event.getBatch().outlineBox(new AABB(pos), sideColor(pos), false);
        }
    }

    /**
     * Green when the side shrugs off a crystal. Orange when a blast would
     * clear what is there and red when the side is open.
     */
    private int sideColor(BlockPos pos) {
        if (BlockUtil.isReplaceable(pos)) {
            return 0xFFE03030;
        }
        BlockState state = BlockUtil.state(pos);
        return state.getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF
            ? 0xFF30E030 : 0xFFE08820;
    }
}
