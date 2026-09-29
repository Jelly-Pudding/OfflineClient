package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChatWarning;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.InventoryUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.UseBudget;
import com.jellypudding.offlineclient.util.WorldWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Seals you inside a ball or a box of blocks. The walls go up nearest your body first and a
// block with nothing to lean on goes straight into its empty space.
public final class Cocoon extends Module {

    public enum Shape { BALL, BOX }

    public enum Fill { SHELL, SOLID }

    public enum Pick { STRONGEST, HELD }

    // A ball or a box any bigger lies wholly out of reach.
    private static final double MAX_RADIUS = 6;

    // Ticks the crouch is held before a block over your head goes down. The server has to
    // take the smaller pose first or it refuses the block.
    private static final int CROUCH_TICKS = 2;

    private final EnumSetting<Shape> shape = new EnumSetting<>("Form",
        "The form the walls take.", Shape.BALL)
        .describe(Shape.BALL, "A ball round your body.")
        .describe(Shape.BOX, "A box round your body.");
    private final NumberSetting ballRadius = new NumberSetting("Ball radius",
        "How far the ball reaches from your body. One and a half fills in the edges and two the corners.",
        1, 1, 5, 0.5, " blocks").min(1).max(MAX_RADIUS).under(shape, Shape.BALL);
    private final NumberSetting boxRadius = new NumberSetting("Box radius",
        "How far the box reaches from your body.", 1, 1, 4, 1, " blocks").min(1).max(MAX_RADIUS)
        .under(shape, Shape.BOX);
    private final EnumSetting<Fill> fill = new EnumSetting<>("Fill",
        "Which blocks of the shape are filled.", Fill.SHELL)
        .describe(Fill.SHELL, "Only the outer layer. It needs far fewer blocks.")
        .describe(Fill.SOLID, "Every open block inside the walls too.");
    private final EnumSetting<Pick> pick = new EnumSetting<>("Pick",
        "Which block from your hotbar the walls are made of.", Pick.STRONGEST)
        .describe(Pick.STRONGEST, "The one that stands up best to explosions.")
        .describe(Pick.HELD, "The one in your hand whilst it can build. The strongest one otherwise.");
    private final RegistryListSetting<Block> never = new RegistryListSetting<>("Never use",
        "Blocks never used for the walls. Click to pick them.", BuiltInRegistries.BLOCK,
        List.of(Blocks.TNT, Blocks.ANCIENT_DEBRIS, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK));
    private final NumberSetting perRound = new NumberSetting("Blocks per round",
        "How many blocks go down in one round.", 4, 1, 8, 1).min(1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between rounds.", 0, 0, 10, 1, " ticks").min(0);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block.", true);
    private final BoolSetting centre = new BoolSetting("Centre",
        "Moves you to the middle of your block when the module turns on.", true);
    private final BoolSetting crouch = new BoolSetting("Crouch for roof",
        "Crouches whilst a block over your head would not fit standing up. Needed on slabs and snow.", true);
    private final BoolSetting toggleOff = new BoolSetting("Toggle off when done",
        "Turns off once every space is filled.", true);
    private final BoolSetting render = new BoolSetting("Show blocks",
        "Outlines the spaces still to fill.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 150f).under(render);

    private final SlotSwap slots = new SlotSwap();
    private final HeldKey sneak = new HeldKey(options -> options.keyShift);
    private final WorldWatch world = new WorldWatch();
    private final ChatWarning warning = new ChatWarning();

    // The spaces the last tick found. Drawn until the next one.
    private List<BlockPos> pending = List.of();

    private int timer;
    private int crouched;
    private boolean placed;

    public Cocoon() {
        super("Cocoon", "Seals you inside a ball or a box of blocks.", Category.COMBAT);
        addSettings(shape, ballRadius, boxRadius, fill, pick, never, perRound, delay, rotate, centre, crouch,
            toggleOff, render);
        addSettings(style.settings());
        searchTags("self trap", "panic", "bunker", "shell");
    }

    @Override
    public boolean savesEnabledState() {
        return false;
    }

    @Override
    public String getSuffix() {
        return count(pending.size(), "left");
    }

    @Override
    protected void onEnable() {
        timer = 0;
        crouched = 0;
        placed = false;
        pending = List.of();
        slots.forget();
        warning.clear();
        if (!inGame()) {
            setEnabled(false);
            return;
        }
        world.accept();
        if (centre.isOn()) {
            BlockUtil.centerPlayer();
        }
    }

    @Override
    protected void onDisable() {
        slots.restore();
        sneak.letGo();
        pending = List.of();
    }

    // Fires without a world as well. Leaving the server turns it off.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        if (mc.level == null) {
            setEnabled(false);
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        pending = List.of();
        if (world.changed() || mc.player.isDeadOrDying()) {
            setEnabled(false);
            return;
        }
        if (mc.player.isSpectator()) {
            return;
        }
        List<BlockPos> open = openCells();
        pending = open;
        if (open.isEmpty()) {
            sneak.letGo();
            slots.restore();
            if (toggleOff.isOn() && placed) {
                setEnabled(false);
            }
            return;
        }
        boolean crouchReady = crouchFor(open);
        if (timer > 0) {
            timer--;
            return;
        }
        int slot = blockSlot();
        if (slot == -1) {
            warning.say("No block in your hotbar can build the walls.");
            return;
        }
        warning.clear();
        placeRound(open, slot, crouchReady);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (render.isOn()) {
            style.drawAll(event.getBatch(), pending, false);
        }
    }

    private void placeRound(List<BlockPos> open, int slot, boolean crouchReady) {
        slots.select(slot);
        int done = 0;
        for (BlockPos cell : open) {
            if (done >= perRound.getInt() || UseBudget.remaining() == 0) {
                break;
            }
            if ((!crouchReady && needsCrouch(cell)) || !BlockUtil.blockFits(cell)) {
                continue;
            }
            if (BlockUtil.placeAny(cell, rotate.isOn(), true)) {
                done++;
            }
        }
        slots.restore();
        if (done > 0) {
            placed = true;
            timer = delay.getInt();
        }
    }

    // Holds a crouch whilst a space over your head needs one. True once the crouch has held
    // long enough for the server to take it.
    private boolean crouchFor(List<BlockPos> open) {
        if (!crouch.isOn() || open.stream().noneMatch(this::needsCrouch)) {
            sneak.letGo();
            crouched = 0;
            return false;
        }
        sneak.hold();
        crouched = mc.player.isCrouching() ? crouched + 1 : 0;
        return crouched > CROUCH_TICKS;
    }

    // The spaces of the shape that still take a block. A space over your head that only your
    // standing body fills counts whilst you may crouch for it.
    private List<BlockPos> openCells() {
        List<BlockPos> open = new ArrayList<>();
        for (BlockPos cell : shapeCells(mc.player.blockPosition())) {
            if (!BlockUtil.isReplaceable(cell) || !BlockUtil.serverReaches(cell)) {
                continue;
            }
            boolean clear = crouch.isOn() && needsCrouch(cell)
                ? mc.level.isUnobstructed(mc.player, Shapes.block().move(cell))
                : BlockUtil.unobstructed(cell);
            if (clear) {
                open.add(cell);
            }
        }
        return open;
    }

    // True when the space cuts into your standing body and would miss it whilst you crouch.
    private boolean needsCrouch(BlockPos cell) {
        AABB space = new AABB(cell);
        Vec3 at = mc.player.position();
        return mc.player.getDimensions(Pose.STANDING).makeBoundingBox(at).intersects(space)
            && !mc.player.getDimensions(Pose.CROUCHING).makeBoundingBox(at).intersects(space);
    }

    // Every space of the shape round the feet and head in order out from the body. The lower
    // of two spaces the same distance out comes first.
    private List<BlockPos> shapeCells(BlockPos feet) {
        double radius = shape.is(Shape.BALL) ? ballRadius.getValue() : boxRadius.getInt();
        int span = (int) Math.ceil(radius);
        Map<BlockPos, Double> inside = new HashMap<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -span; dy <= span + 1; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    double out = distanceOut(dx, dy, dz);
                    if (out <= radius) {
                        inside.put(new BlockPos(dx, dy, dz), out);
                    }
                }
            }
        }
        List<BlockPos> cells = new ArrayList<>();
        for (Map.Entry<BlockPos, Double> entry : inside.entrySet()) {
            BlockPos offset = entry.getKey();
            // The two spaces the body stands in are never filled.
            if (entry.getValue() > 0 && (fill.is(Fill.SOLID) || onEdge(offset, inside))) {
                cells.add(offset);
            }
        }
        cells.sort(Comparator.<BlockPos>comparingDouble(inside::get).thenComparingInt(BlockPos::getY));
        List<BlockPos> positions = new ArrayList<>(cells.size());
        for (BlockPos offset : cells) {
            positions.add(feet.offset(offset));
        }
        return positions;
    }

    // How far a space lies from the body. A ball measures from the middle of the feet space or the
    // head space and a box counts whole layers out.
    private double distanceOut(int dx, int dy, int dz) {
        if (shape.is(Shape.BALL)) {
            double flat = dx * dx + dz * dz;
            return Math.sqrt(flat + Math.min(dy * dy, (dy - 1) * (dy - 1)));
        }
        int vertical = dy < 0 ? -dy : Math.max(0, dy - 1);
        return Math.max(Math.max(Math.abs(dx), Math.abs(dz)), vertical);
    }

    // A space with a side open to the outside of the shape.
    private static boolean onEdge(BlockPos offset, Map<BlockPos, Double> inside) {
        for (Direction side : Direction.values()) {
            if (!inside.containsKey(offset.relative(side))) {
                return true;
            }
        }
        return false;
    }

    private int blockSlot() {
        if (pick.is(Pick.HELD) && mc.player.getMainHandItem().getItem() instanceof BlockItem item
            && allowed(item.getBlock())) {
            return InventoryUtil.selectedSlot();
        }
        return BlockUtil.findStrongestBlockSlot(this::allowed);
    }

    // A plain full block that stays where it is put. Sand and gravel would fall off a roof.
    private boolean allowed(Block block) {
        return !never.contains(block) && !(block instanceof FallingBlock)
            && BlockUtil.isBuildingBlock(block, mc.player.blockPosition());
    }
}
