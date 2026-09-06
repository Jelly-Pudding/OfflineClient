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
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.ExplosionUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import com.jellypudding.offlineclient.util.RotationPriority;
import com.jellypudding.offlineclient.util.SwingMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

// Missing support under a side is filled first.
public final class Surround extends Module {

    public enum Centre { NEVER, ON_ENABLE, INCOMPLETE, ALWAYS }

    // Blocks that are far too valuable to spend on a wall.
    private static final Set<Block> NEVER = Set.of(
        Blocks.ANCIENT_DEBRIS, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK);

    // How far off an enemy still decides which side is walled first.
    private static final double THREAT_RANGE = 12;

    // Ticks a hit crystal is left alone before it is hit again.
    private static final int HIT_MEMORY = 10;

    private final RegistryListSetting<Block> blocks = new RegistryListSetting<>("Blocks",
        "Blocks to use in order of preference.",
        BuiltInRegistries.BLOCK,
        List.of(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN));
    private final EnumSetting<Centre> centre = new EnumSetting<>("Centre",
        "When to snap to the middle of your block so every side lines up.", Centre.INCOMPLETE)
        .describe(Centre.NEVER, "Never moves you.")
        .describe(Centre.ON_ENABLE, "Once when the module turns on.")
        .describe(Centre.INCOMPLETE, "Whilst any side is still open.")
        .describe(Centre.ALWAYS, "The whole time the module is on.");
    private final BoolSetting onlyOnGround = new BoolSetting("Only on ground",
        "Wait until you are standing on something.", true);
    private final NumberSetting perTick = new NumberSetting("Blocks per tick",
        "How many blocks to place in one tick.", 4, 1, 4, 1);
    private final NumberSetting delay = new NumberSetting("Delay",
        "Ticks to wait between placing rounds.", 0, 0, 5, 1, " ticks");
    private final BoolSetting airPlace = new BoolSetting("Air place",
        "Places on an open side with nothing to lean on. Off lays a support block under it first.", true);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards each block as it goes down.", true);
    private final EnumSetting<SwingMode> swing = SwingMode.setting(SwingMode.BOTH);
    private final BoolSetting doubleHeight = new BoolSetting("Double height",
        "Also wall the four sides at head height to stop a face place.", false);
    private final BoolSetting protect = new BoolSetting("Protect",
        "Hits any crystal sitting on an open side before it can be set off.", true);
    private final ChoiceListSetting disableModules = new ChoiceListSetting("Disable modules",
        "Modules switched off whilst this is on.", Surround::moduleNames);
    private final BoolSetting restoreModules = new BoolSetting("Restore modules",
        "Switches those modules back on when this turns off.", true)
        .under(disableModules, () -> disableModules.size() > 0);
    private final BoolSetting toggleOnDeath = new BoolSetting("Toggle off on death",
        "Turn off when you die instead of walling your respawn.", true);
    private final BoolSetting toggleOnDone = new BoolSetting("Toggle off when done",
        "Turn off once all four sides are filled.", false);
    private final BoolSetting toggleOnMove = new BoolSetting("Toggle off on move",
        "Turn off if you leave the block you started on.", false);
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

    private final Map<Integer, Integer> hitCrystals = new HashMap<>();
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

    private static List<String> moduleNames() {
        List<String> names = new ArrayList<>();
        for (Module module : OfflineClient.INSTANCE.getModuleManager().getAll()) {
            if (module.isTogglable() && !(module instanceof Surround)) {
                names.add(module.getName());
            }
        }
        return names;
    }

    @Override
    protected void onEnable() {
        timer = 0;
        anchor = null;
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
        // A crystal on an open side goes before the wall does.
        // The delay never holds this up.
        if (protect.isOn()) {
            protectSides(feet);
        }
        if (centre.is(Centre.ALWAYS)) {
            BlockUtil.centerPlayer();
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
        if (centre.is(Centre.INCOMPLETE)) {
            BlockUtil.centerPlayer();
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
            if (support == null && !airPlace.isOn()) {
                continue;
            }
            slots.select(slot);
            // Every side asks to turn and the rotation manager keeps the first one.
            boolean ok = support != null
                ? BlockUtil.place(target, support, rotate.isOn(), false)
                : BlockUtil.placeDirect(pos, rotate.isOn(), false);
            if (ok) {
                swing.getValue().swing(InteractionHand.MAIN_HAND);
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
        // The side facing the nearest enemy goes first.
        // That is where the crystal comes from.
        Player enemy = EntityUtil.nearestEnemy(THREAT_RANGE);
        if (enemy != null) {
            result.sort(Comparator.comparingDouble(pos -> enemy.distanceToSqr(Vec3.atCenterOf(pos))));
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

    // A side that is open or being mined is where a crystal would go.
    // Any crystal touching such a side is hit unless popping it would kill us.
    private void protectSides(BlockPos feet) {
        int now = mc.player.tickCount;
        hitCrystals.values().removeIf(tick -> now - tick > HIT_MEMORY);
        float health = EntityUtil.totalHealth(mc.player);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos pos = feet.relative(side);
            if (!BlockUtil.isReplaceable(pos) && !beingMined(pos)) {
                continue;
            }
            AABB reach = new AABB(pos).inflate(1);
            for (Entity crystal : mc.level.getEntities((Entity) null, reach, e -> e instanceof EndCrystal)) {
                if (ExplosionUtil.crystalDamage(mc.player, crystal.position()) >= health) {
                    continue;
                }
                // One hit a crystal. A second packet before it pops is wasted.
                if (hitCrystals.containsKey(crystal.getId())) {
                    continue;
                }
                hitCrystals.put(crystal.getId(), now);
                if (rotate.isOn()) {
                    BlockUtil.faceVector(crystal.position(), RotationPriority.ATTACK);
                }
                mc.player.connection.send(new ServerboundAttackPacket(crystal.getId()));
                swing.getValue().swing(InteractionHand.MAIN_HAND);
            }
        }
    }

    // True whilst somebody has a crack pattern on the block.
    private boolean beingMined(BlockPos pos) {
        for (SortedSet<BlockDestructionProgress> progress : mc.level.destructionProgress().values()) {
            if (!progress.isEmpty() && progress.last().getPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!render.isOn() || !inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        BlockPos feet = mc.player.blockPosition();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            drawSide(batch, feet.relative(side));
            if (doubleHeight.isOn()) {
                drawSide(batch, feet.above().relative(side));
            }
        }
        if (renderBelow.isOn()) {
            drawSide(batch, feet.below());
        }
    }

    private void drawSide(DrawBatch batch, BlockPos pos) {
        boxStyle.draw(batch, pos, sideColor(pos), false);
    }

    private int sideColor(BlockPos pos) {
        if (BlockUtil.isReplaceable(pos)) {
            return openColor.getColor();
        }
        BlockState state = BlockUtil.state(pos);
        if (state.getBlock().defaultDestroyTime() < 0) {
            return unbreakableColor.getColor();
        }
        return state.getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF
            ? safeColor.getColor() : weakColor.getColor();
    }
}
