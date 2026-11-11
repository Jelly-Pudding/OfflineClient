package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.NearestCut;
import com.jellypudding.offlineclient.worldgen.OreKind;
import com.jellypudding.offlineclient.worldgen.OreTables;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// The ore picks and colours the ore finders share and the boxes they draw. Touching boxes of
// one ore are joined into one shape.
public final class OreMarks {

    // One ore block to draw.
    public record Spot(int x, int y, int z, OreKind kind) {
    }

    // A kept spot whose block could still be ore with the sides it shares with the same ore.
    private record Mark(AABB box, int hidden, OreKind kind) {
    }

    private static final Direction[] SIDES = Direction.values();

    private final ChoiceListSetting ores;
    private final NumberSetting limit = new NumberSetting("Limit",
        "Most ore blocks drawn at once with the nearest first.", 2000, 100, 5000, 100).min(1);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.LINES);
    private final Map<OreKind, ColorSetting> colours = new EnumMap<>(OreKind.class);

    private final NearestCut<Spot> drawn = new NearestCut<>(
        (spot, eye) -> eye.distanceToSqr(spot.x() + 0.5, spot.y() + 0.5, spot.z() + 0.5));
    private List<Mark> marks = List.of();

    public OreMarks(String description, List<OreKind> offered) {
        List<String> labels = offered.stream().map(OreKind::label).toList();
        ores = new ChoiceListSetting("Ores", description, () -> labels,
            List.of(OreKind.DIAMOND.label(), OreKind.ANCIENT_DEBRIS.label()));
        for (OreKind kind : offered) {
            float[] shade = ColorUtil.hsvOf(kind.colour());
            ColorSetting colour = new ColorSetting(kind.label() + " colour",
                "The colour " + kind.label().toLowerCase(Locale.ROOT) + " is drawn in.",
                shade[0], shade[1], shade[2], false).visibleWhen(() -> ores.contains(kind.label()));
            colours.put(kind, colour);
        }
    }

    // The ore list. A finder lists it first.
    public ChoiceListSetting picks() {
        return ores;
    }

    // How the picked ores are drawn. A finder lists these after its own settings.
    public Setting<?>[] look() {
        List<Setting<?>> look = new ArrayList<>();
        look.add(limit);
        look.addAll(List.of(style.settings()));
        look.addAll(colours.values());
        return look.toArray(Setting<?>[]::new);
    }

    public boolean picked(OreKind kind) {
        return ores.contains(kind.label());
    }

    // Once a tick. Keeps the nearest of these up to the limit and looks their blocks over again
    // even when the list is the same. Hand over a new list whenever the spots change.
    public void update(List<Spot> spots) {
        drawn.update(spots, limit.getInt());
        ClientLevel level = OfflineClient.MC.level;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        List<Spot> held = new ArrayList<>(drawn.result().size());
        Long2ObjectOpenHashMap<OreKind> kinds = new Long2ObjectOpenHashMap<>();
        for (Spot spot : drawn.result()) {
            pos.set(spot.x(), spot.y(), spot.z());
            if (couldStillHold(level, pos, next, spot.kind())) {
                held.add(spot);
                kinds.put(pos.asLong(), spot.kind());
            }
        }
        List<Mark> found = new ArrayList<>(held.size());
        for (Spot spot : held) {
            AABB box = new AABB(spot.x(), spot.y(), spot.z(), spot.x() + 1, spot.y() + 1, spot.z() + 1);
            long key = BlockPos.asLong(spot.x(), spot.y(), spot.z());
            found.add(new Mark(box, DrawBatch.sharedSides(kinds, key, spot.kind()), spot.kind()));
        }
        marks = found;
    }

    public void clear() {
        drawn.clear();
        marks = List.of();
    }

    // The colours are looked up every frame. A rainbow keeps moving between updates.
    public void draw(DrawBatch batch) {
        for (Mark mark : marks) {
            int colour = colours.get(mark.kind()).getColor();
            style.drawJoined(batch, mark.box(), mark.hidden(), colour, colour, true);
        }
    }

    // The ore a block counts as. Raw iron and raw copper blocks only grow in the large veins
    // and count as their ore. Null for any other block.
    public static OreKind kindOf(Block block) {
        if (block == Blocks.RAW_IRON_BLOCK) {
            return OreKind.IRON;
        }
        if (block == Blocks.RAW_COPPER_BLOCK) {
            return OreKind.COPPER;
        }
        return OreTables.kindOf(block);
    }

    // Dug out ground shows as air or fluid. Anti xray sends a block that touches open space
    // as it really is and an exposed block of any other kind proves the spot wrong.
    private static boolean couldStillHold(ClientLevel level, BlockPos pos, BlockPos.MutableBlockPos next,
                                          OreKind kind) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (kindOf(state.getBlock()) == kind.ore()) {
            return true;
        }
        for (Direction side : SIDES) {
            next.setWithOffset(pos, side);
            if (!level.getBlockState(next).isRedstoneConductor(level, next)) {
                return false;
            }
        }
        return true;
    }
}
