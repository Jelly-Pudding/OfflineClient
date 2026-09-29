package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render2DEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RenderUtil;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CalibratedSculkSensorBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// A sensor hears an event when the block of the event lies within its radius counted
// between whole block positions and wool does not wall it off. Sneaking silences
// steps and landings but treading on sculk sets it off whatever you do.
public final class SculkRange extends Module {

    public enum Shape { RINGS, SPHERES }

    private enum Kind {
        SENSOR(8),
        // A calibrated sensor listens twice as far.
        CALIBRATED(16),
        // A shrieker only listens for a sensor clicking near it.
        SHRIEKER(8);

        private final int radius;

        Kind(int radius) {
            this.radius = radius;
        }
    }

    private enum Danger {
        NONE(0, ""),
        SENSOR(0xFFFFC040, "A sculk sensor would hear your step"),
        SHRIEKER(0xFFFF5050, "A sensor near a shrieker would hear your step"),
        TRODDEN(0xFFFF5050, "Treading on sculk sets it off even whilst sneaking");

        private final int color;
        private final String text;

        Danger(int color, String text) {
            this.color = color;
            this.text = text;
        }
    }

    // Built on the scanner thread.
    private record Listener(BlockPos pos, Kind kind) {
    }

    // One side of a block square on the floor.
    private record Edge(int x1, int z1, int x2, int z2) {
    }

    // A step sounds each time you cover this much ground.
    private static final double STRIDE = 1 / 0.6;
    // Slower than this counts as standing still.
    private static final double MOVING = 1.0E-3;
    // How far under the feet the floor is looked for.
    private static final double GROUND_PROBE = 1.0E-5;
    // How far above the floor a ring is drawn.
    private static final double LIFT = 0.02;

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to scan around you.", 3, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final EnumSetting<Shape> shape = new EnumSetting<>("Shape",
        "How each range is drawn.", Shape.RINGS)
        .describe(Shape.RINGS, "The edge of each range at the height of your feet.")
        .describe(Shape.SPHERES, "A wire ball around each block.");
    private final BoolSetting blocks = new BoolSetting("Blocks",
        "Also outlines each sensor and shrieker.", true);
    private final ColorSetting sensorColor = new ColorSetting("Sensor colour",
        "Colour of sculk sensors and their range.", 185, false);
    private final ColorSetting calibratedColor = new ColorSetting("Calibrated colour",
        "Colour of calibrated sculk sensors and their range.", 285, false);
    private final ColorSetting shriekerColor = new ColorSetting("Shrieker colour",
        "Colour of sculk shriekers and how far a clicking sensor wakes them.", 0, false);
    private final BoolSetting warning = new BoolSetting("Warning",
        "Writes a warning under the crosshair when your next step would set off sculk.", true);
    private final BoolSetting sneak = new BoolSetting("Sneak for me",
        "Holds sneak whilst a sensor would hear your next step.", false);

    private final ChunkScanner<Listener> scanner = new ChunkScanner<>();
    private final Map<Kind, List<Edge>> rings = new EnumMap<>(Kind.class);
    private final HeldKey sneakKey = new HeldKey(options -> options.keyShift);
    // What the rings were last built from.
    private List<Listener> ringSource;
    private int ringLevel;
    private Danger danger = Danger.NONE;
    // A sensor would hear the next step. Sneaking silences that.
    private boolean heard;

    public SculkRange() {
        super("SculkRange", "Shows how far sculk sensors and shriekers hear and warns before a step sets one off.",
            Category.RENDER);
        addSettings(range, shape, blocks, sensorColor, calibratedColor, shriekerColor, warning, sneak);
        searchTags("sculk", "sensor", "shrieker", "warden", "ancient city", "vibration");
    }

    @Override
    public String getSuffix() {
        return count(scanner.size());
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
        sneakKey.letGo();
    }

    private void reset() {
        scanner.reset();
        rings.clear();
        ringSource = null;
        danger = Danger.NONE;
        heard = false;
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            sneakKey.letGo();
            return;
        }
        scanner.update(range.getInt(), (view, out) -> view.forEachMatching(SculkRange::isSculk,
            (x, y, z, state) -> out.add(new Listener(new BlockPos(x, y, z), kindOf(state)))));
        List<Listener> all = scanner.results();
        judge(all);
        // Sneaking on a mount only gets you off it.
        if (sneak.isOn() && heard && !mc.player.isPassenger()) {
            sneakKey.hold();
        } else {
            sneakKey.letGo();
        }
        int level = mc.player.blockPosition().getY();
        if (shape.is(Shape.RINGS) && (all != ringSource || level != ringLevel)) {
            ringSource = all;
            ringLevel = level;
            buildRings(all, level);
        }
    }

    private static boolean isSculk(BlockState state) {
        return state.is(Blocks.SCULK_SENSOR) || state.is(Blocks.CALIBRATED_SCULK_SENSOR)
            || state.is(Blocks.SCULK_SHRIEKER);
    }

    private static Kind kindOf(BlockState state) {
        if (state.is(Blocks.SCULK_SHRIEKER)) {
            return Kind.SHRIEKER;
        }
        return state.is(Blocks.CALIBRATED_SCULK_SENSOR) ? Kind.CALIBRATED : Kind.SENSOR;
    }

    // Weighs a step here and one stride further on. A mount steps for its rider and flight
    // makes no steps.
    private void judge(List<Listener> all) {
        danger = Danger.NONE;
        heard = false;
        Entity walker = mc.player.getRootVehicle();
        if (walker == mc.player && (mc.player.isSpectator() || mc.player.getAbilities().flying
            || mc.player.isFallFlying())) {
            return;
        }
        Vec3 feet = walker.position();
        BlockPos floor = walker.getOnPos();
        Danger worst = heardAt(feet, floor, all);
        boolean trodden = tripped(floor);
        Vec3 motion = walker.getDeltaMovement();
        double speed = motion.horizontalDistance();
        if (speed >= MOVING) {
            Vec3 ahead = feet.add(motion.x / speed * STRIDE, 0, motion.z / speed * STRIDE);
            BlockPos aheadFloor = BlockPos.containing(ahead.x, feet.y - GROUND_PROBE, ahead.z);
            Danger next = heardAt(ahead, aheadFloor, all);
            worst = next.ordinal() > worst.ordinal() ? next : worst;
            trodden = trodden || tripped(aheadFloor);
        }
        heard = worst != Danger.NONE;
        danger = trodden ? Danger.TRODDEN : worst;
    }

    // The worst a step made here would wake by being heard.
    private Danger heardAt(Vec3 feet, BlockPos floor, List<Listener> all) {
        if (mc.level.getBlockState(floor).is(BlockTags.DAMPENS_VIBRATIONS)) {
            return Danger.NONE;
        }
        BlockPos at = BlockPos.containing(feet);
        Danger worst = Danger.NONE;
        for (Listener listener : all) {
            if (listener.kind() == Kind.SHRIEKER || !within(listener.pos(), at, listener.kind())) {
                continue;
            }
            BlockState state = mc.level.getBlockState(listener.pos());
            if (!listening(listener.pos(), state) || walledOff(at, listener.pos())) {
                continue;
            }
            if (wakesShrieker(listener.pos(), all)) {
                return Danger.SHRIEKER;
            }
            worst = Danger.SENSOR;
        }
        return worst;
    }

    // Standing on a sensor or shrieker sets it off without any hearing involved.
    private boolean tripped(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.SCULK_SHRIEKER)) {
            return !state.getValue(SculkShriekerBlock.SHRIEKING);
        }
        return listening(pos, state);
    }

    private static boolean within(BlockPos listener, BlockPos event, Kind kind) {
        return listener.distSqr(event) <= kind.radius * kind.radius;
    }

    // Idle and not tuned away from steps.
    private boolean listening(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof SculkSensorBlock) || !SculkSensorBlock.canActivate(state)) {
            return false;
        }
        if (!state.is(Blocks.CALIBRATED_SCULK_SENSOR)) {
            return true;
        }
        // Powered from behind it hears only events of the frequency the power gives.
        Direction back = state.getValue(CalibratedSculkSensorBlock.FACING).getOpposite();
        int tuning = mc.level.getSignal(pos.relative(back), back);
        return tuning == 0 || tuning == VibrationSystem.getGameEventFrequency(GameEvent.STEP);
    }

    // A sensor that clicks makes every quiet shrieker in reach cry out. Only a shrieker the
    // world placed can call a warden.
    private boolean wakesShrieker(BlockPos sensor, List<Listener> all) {
        for (Listener listener : all) {
            if (listener.kind() != Kind.SHRIEKER || !within(listener.pos(), sensor, Kind.SHRIEKER)) {
                continue;
            }
            BlockState state = mc.level.getBlockState(listener.pos());
            if (state.is(Blocks.SCULK_SHRIEKER) && state.getValue(SculkShriekerBlock.CAN_SUMMON)
                && !state.getValue(SculkShriekerBlock.SHRIEKING)
                && !walledOff(sensor, listener.pos())) {
                return true;
            }
        }
        return false;
    }

    // True when wool cuts every line the game tries between the two blocks.
    private boolean walledOff(BlockPos from, BlockPos to) {
        return VibrationSystem.Listener.isOccluded(mc.level, Vec3.atCenterOf(from), Vec3.atCenterOf(to));
    }

    // The outline of every floor square at your height from which each kind would hear you.
    private void buildRings(List<Listener> all, int level) {
        rings.clear();
        for (Kind kind : Kind.values()) {
            LongOpenHashSet cells = new LongOpenHashSet();
            for (Listener listener : all) {
                if (listener.kind() == kind) {
                    addSlice(cells, listener.pos(), kind.radius, level);
                }
            }
            rings.put(kind, outline(cells));
        }
    }

    private static void addSlice(LongOpenHashSet cells, BlockPos centre, int radius, int level) {
        int dy = centre.getY() - level;
        int left = radius * radius - dy * dy;
        if (left < 0) {
            return;
        }
        int reachX = (int) Math.floor(Math.sqrt(left));
        for (int dx = -reachX; dx <= reachX; dx++) {
            int reachZ = (int) Math.floor(Math.sqrt(left - dx * dx));
            for (int dz = -reachZ; dz <= reachZ; dz++) {
                cells.add(BlockPos.asLong(centre.getX() + dx, level, centre.getZ() + dz));
            }
        }
    }

    // The sides of the squares that face a square outside the set.
    private static List<Edge> outline(LongOpenHashSet cells) {
        List<Edge> edges = new ArrayList<>();
        for (LongIterator it = cells.iterator(); it.hasNext(); ) {
            long cell = it.nextLong();
            int shared = DrawBatch.sharedSides(cells, cell);
            int x = BlockPos.getX(cell);
            int z = BlockPos.getZ(cell);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if ((shared & DrawBatch.sideBit(side)) != 0) {
                    continue;
                }
                edges.add(switch (side) {
                    case NORTH -> new Edge(x, z, x + 1, z);
                    case SOUTH -> new Edge(x, z + 1, x + 1, z + 1);
                    case WEST -> new Edge(x, z, x, z + 1);
                    default -> new Edge(x + 1, z, x + 1, z + 1);
                });
            }
        }
        return edges;
    }

    private int colorOf(Kind kind) {
        return switch (kind) {
            case SENSOR -> sensorColor.getColor();
            case CALIBRATED -> calibratedColor.getColor();
            case SHRIEKER -> shriekerColor.getColor();
        };
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (!inGame()) {
            return;
        }
        DrawBatch batch = event.getBatch();
        List<Listener> all = scanner.results();
        if (blocks.isOn()) {
            for (Listener listener : all) {
                batch.outlineBlock(listener.pos(), colorOf(listener.kind()), true);
            }
        }
        if (shape.is(Shape.SPHERES)) {
            for (Listener listener : all) {
                batch.sphere(Vec3.atCenterOf(listener.pos()), listener.kind().radius, colorOf(listener.kind()), true);
            }
            return;
        }
        double y = mc.player.getPosition(event.getPartialTicks()).y + LIFT;
        for (Map.Entry<Kind, List<Edge>> entry : rings.entrySet()) {
            int color = colorOf(entry.getKey());
            for (Edge edge : entry.getValue()) {
                batch.line(new Vec3(edge.x1(), y, edge.z1()), new Vec3(edge.x2(), y, edge.z2()), color, true);
            }
        }
    }

    // Sneaking already silences steps on foot and says nothing more. A mount never sneaks.
    @Subscribe
    private void onRender2D(Render2DEvent event) {
        if (!warning.isOn() || !inGame() || danger == Danger.NONE
            || (danger != Danger.TRODDEN && !mc.player.isPassenger() && Modules.sneaking())) {
            return;
        }
        RenderUtil.underCrosshair(event.getContext(), mc.font, danger.text, danger.color);
    }
}
