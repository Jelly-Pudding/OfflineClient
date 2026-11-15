package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.CrystalGuard;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Holes;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.SwingMode;
import com.jellypudding.offlineclient.util.UseBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

// Missing support under a side is filled first.
public final class Surround extends Module {

    public enum Centre { NEVER, ON_ENABLE, INCOMPLETE, ALWAYS }

    // Blocks that are far too valuable to spend on a wall.
    private static final Set<Block> NEVER = Set.of(
        Blocks.ANCIENT_DEBRIS, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK);

    // How far off an enemy still decides which side is walled first.
    private static final double THREAT_RANGE = 12;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN));
    private final EnumSetting<Centre> centre = new EnumSetting<>("Centre",
        "When to move you to the middle of your block.", Centre.INCOMPLETE)
        .describe(Centre.NEVER, "Never moves you. The ring goes round every block your body touches.")
        .describe(Centre.ON_ENABLE, "Once when the module turns on.")
        .describe(Centre.INCOMPLETE, "Whilst any side is still open.")
        .describe(Centre.ALWAYS, "The whole time the module is on.");
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Waits until you are standing on something.", true);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one tick.", 4, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 0, 0, 5, 1, " ticks");
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Places on an open side with nothing to lean on. Off lays a support block under it first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Sends a look packet towards each block as it goes down.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.settingWithUses(SwingMode.BOTH);
    private final BoolSetting doubleHeight = new BoolSetting("Double height",
        "Also walls the four sides at head height to stop a face place.", false);
    private final BoolSetting protect = new BoolSetting("Protect",
        "Hits any crystal sitting on an open side before it can be set off.", true);
    private final ChoiceListSetting disableModules = new ChoiceListSetting("Disable modules",
        "Modules switched off whilst this is on.",
        () -> OfflineClient.INSTANCE.getModuleManager().togglableNames(this));
    private final BoolSetting restoreModules = new BoolSetting("Restore modules",
        "Switches those modules back on when this turns off.", true)
        .under(disableModules, () -> disableModules.size() > 0);
    private final BoolSetting toggleOnDeath = new BoolSetting("Toggle off on death",
        "Turns off when you die instead of walling your respawn.", true);
    private final BoolSetting toggleOnDone = new BoolSetting("Toggle off when done",
        "Turns off once every side is filled.", false);
    private final BoolSetting toggleOnMove = new BoolSetting("Toggle off on move",
        "Turns off if you leave the block you started on.", false);
    private final BoolSetting render = new BoolSetting("Render",
        "Draws the side positions by how well they hold.", true);
    private final BoxStyle boxStyle = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH).under(render);
    private final BoolSetting renderBelow = new BoolSetting("Show below",
        "Also draws the block under your feet.", false)
        .under(render);
    private final ColorSetting unbreakableColor = new ColorSetting("Unbreakable colour",
        "A side of bedrock.", 200, false).under(render);
    private final ColorSetting safeColor = new ColorSetting("Safe colour",
        "A side a crystal cannot open.", 120, false).under(render);
    private final ColorSetting weakColor = new ColorSetting("Weak colour",
        "A side a blast would clear.", 35, false).under(render);
    private final ColorSetting openColor = new ColorSetting("Open colour",
        "A side with nothing in it.", 0, false).under(render);

    private final CrystalGuard crystals = new CrystalGuard();
    private final List<Module> disabled = new ArrayList<>();
    private int timer;
    private BlockPos anchor;
    private final SlotSwap slots = new SlotSwap();

    public Surround() {
        super("Surround", "Places blast proof blocks around your feet to stop crystals.", Category.COMBAT);
        addSettings(blocks, centre, onlyOnGround, perTick, delay, airPlace, rotate, swing,
            doubleHeight, protect, disableModules, restoreModules, toggleOnDeath, toggleOnDone,
            toggleOnMove, render);
        addSettings(boxStyle.settings());
        addSettings(renderBelow, unbreakableColor, safeColor, weakColor, openColor);
        searchTags("obsidian", "crystal", "hole");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        anchor = null;
        crystals.forget();
        slots.forget();
        holdModules();
        if (inGame()) {
            anchor = mc.player.blockPosition();
            if (!centre.is(Centre.NEVER)) {
                BlockUtil.centerPlayer();
            }
        }
    }

    @Override
    protected void onDisable() {
        slots.restore();
        anchor = null;
        releaseModules();
    }

    private void holdModules() {
        disabled.clear();
        for (String name : disableModules.getValue()) {
            Module module = OfflineClient.INSTANCE.getModuleManager().get(name);
            if (module != null && module.isEnabled()) {
                module.setEnabled(false);
                disabled.add(module);
            }
        }
    }

    private void releaseModules() {
        if (restoreModules.isOn()) {
            for (Module module : disabled) {
                module.setEnabled(true);
            }
        }
        disabled.clear();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (mc.player.isSpectator()) {
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
        if (centre.is(Centre.ALWAYS)) {
            BlockUtil.centerPlayer();
        }
        List<BlockPos> body = Holes.feetBlocks(mc.player);
        // A crystal on an open side goes before the wall does.
        // The delay never holds this up.
        if (protect.isOn()) {
            protectSides(body);
        }
        if (timer > 0) {
            timer--;
            return;
        }

        List<BlockPos> missing = missing(body);
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
        if (centre.is(Centre.INCOMPLETE)) {
            BlockUtil.centerPlayer();
            // Back in the middle of one block the ring is the four round it.
            if (body.size() > 1) {
                missing = missing(Holes.feetBlocks(mc.player));
            }
        }
        if (placeRound(missing, slot) > 0) {
            timer = delay.getInt();
        }
        slots.restore();
    }

    // Keeps the wall on the block the player stands in.
    // False once the module has switched off.
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
        int round = Math.min(perTick.getInt(), UseBudget.remaining());
        int placed = 0;
        for (BlockPos pos : missing) {
            if (placed >= round) {
                break;
            }
            BlockPos target = pos;
            Direction support = BlockUtil.findPlaceSupport(pos);
            if (support == null) {
                BlockPos below = pos.below();
                Direction belowSupport = BlockUtil.blockFits(below) ? BlockUtil.findPlaceSupport(below) : null;
                if (belowSupport != null) {
                    target = below;
                    support = belowSupport;
                }
            }
            if (support == null && !airPlace.isOn()) {
                continue;
            }
            slots.select(slot);
            // Every side asks to turn and the rotation manager keeps the first one.
            boolean ok = support != null
                ? BlockUtil.place(target, support, rotate.isOn(), false)
                : BlockUtil.placeDirect(pos, rotate.isOn(), false);
            if (ok) {
                swing.getValue().use();
                placed++;
            }
        }
        return placed;
    }

    // Every open spot of the wall round the blocks the body stands in. Off the ground the
    // floor of the pocket comes first.
    private List<BlockPos> missing(List<BlockPos> body) {
        List<BlockPos> missing = missingSides(body);
        if (!mc.player.onGround()) {
            for (BlockPos block : body) {
                if (BlockUtil.blockFits(block.below())) {
                    missing.add(0, block.below());
                }
            }
        }
        return missing;
    }

    private List<BlockPos> missingSides(List<BlockPos> body) {
        List<BlockPos> ring = Holes.ringAround(body);
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : ring) {
            if (BlockUtil.blockFits(pos)) {
                result.add(pos);
            }
        }
        // The side facing the nearest enemy goes first.
        // That is where the crystal comes from.
        Player enemy = EntityUtil.nearestEnemy(THREAT_RANGE);
        if (enemy != null) {
            result.sort(Comparator.comparingDouble(pos -> enemy.distanceToSqr(Vec3.atCenterOf(pos))));
        }
        // The lower ring goes down first. The pocket is sealed before it is raised.
        if (doubleHeight.isOn()) {
            for (BlockPos side : ring) {
                if (BlockUtil.blockFits(side.above())) {
                    result.add(side.above());
                }
            }
        }
        return result;
    }

    private int findBlastBlock() {
        return BlockUtil.findRankedBlockSlot(blocks.getValue(), block -> !NEVER.contains(block));
    }

    // A side that is open or being mined is where a crystal would go.
    // Any crystal touching such a side is hit unless popping it would kill us.
    private void protectSides(List<BlockPos> body) {
        crystals.tick();
        for (BlockPos pos : Holes.ringAround(body)) {
            if (BlockUtil.isReplaceable(pos) || BlockUtil.crackStage(pos) >= 0) {
                crystals.clear(new AABB(pos).inflate(1), rotate.isOn(), swing.getValue());
            }
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        List<BlockPos> body = Holes.feetBlocks(mc.player);
        for (BlockPos side : Holes.ringAround(body)) {
            drawSide(batch, side);
            if (doubleHeight.isOn()) {
                drawSide(batch, side.above());
            }
        }
        if (renderBelow.isOn()) {
            for (BlockPos block : body) {
                drawSide(batch, block.below());
            }
        }
    }

    private void drawSide(DrawBatch batch, BlockPos pos) {
        boxStyle.draw(batch, pos, sideColor(pos), false);
    }

    private int sideColor(BlockPos pos) {
        return switch (Holes.wallOf(BlockUtil.state(pos))) {
            case OPEN -> openColor.getColor();
            case WEAK -> weakColor.getColor();
            case BLAST_PROOF -> safeColor.getColor();
            case UNBREAKABLE -> unbreakableColor.getColor();
        };
    }
}
