package com.jellypudding.offlineclient.modules.hunting;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.render.HeightBand;
import com.jellypudding.offlineclient.render.JoinedCells;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ChoiceListSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.worldgen.DugPassages;
import com.jellypudding.offlineclient.worldgen.DugPassages.Find;
import com.jellypudding.offlineclient.worldgen.DugPassages.Kind;
import com.jellypudding.offlineclient.worldgen.DugPassages.Rules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class TunnelEsp extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 4, 1, 8, 1, " chunks").max(ChunkMap.MAX_VIEW_DISTANCE);
    private final HeightBand band = new HeightBand("passages");
    private final NumberSetting widest = new NumberSetting("Widest",
        "How many blocks wide a tunnel or shaft may be.", 1, 1, 5, 1, " blocks").max(DugPassages.WIDEST_SEEN);
    private final BoolSetting onlyAir = new BoolSetting("Only air",
        "Counts only air as open. Torches and rails then block a passage.", false);
    private final BoolSetting naturalWalls = new BoolSetting("Natural walls",
        "Only counts passages walled mostly by natural ground and leaves out mineshafts and strongholds.", true);
    private final BoolSetting corridors = new BoolSetting("Corridors",
        "Marks straight tunnels up to Widest blocks across.", true);
    private final NumberSetting minLength = new NumberSetting("Min length",
        "How many blocks long a tunnel must be.", 3, 1, 32, 1, " blocks").under(corridors);
    private final NumberSetting minHeight = new NumberSetting("Min height",
        "How low a tunnel or zigzag may be.", 2, 1, 6, 1, " blocks");
    private final NumberSetting maxHeight = new NumberSetting("Max height",
        "How tall a tunnel or zigzag may be. Below Min height it counts as Min height.", 3, 1, 6, 1, " blocks");
    private final BoolSetting shafts = new BoolSetting("Shafts",
        "Marks upright holes up to Widest blocks across with walls on every side.", true);
    private final NumberSetting minDepth = new NumberSetting("Min depth",
        "How deep a shaft must be.", 4, 2, 64, 1, " blocks").under(shafts);
    private final BoolSetting waterShafts = new BoolSetting("Water shafts",
        "Also marks shafts full of water such as drops and bubble lifts.", true).under(shafts);
    private final BoolSetting staircases = new BoolSetting("Staircases",
        "Marks stairs one block wide that climb a block with every step.", true);
    private final NumberSetting stairSteps = new NumberSetting("Stair steps",
        "How many steps a staircase must have.", 3, 2, 32, 1).under(staircases);
    private final NumberSetting minHeadroom = new NumberSetting("Min headroom",
        "How low the space over a step may be.", 3, 2, 6, 1, " blocks").under(staircases);
    private final NumberSetting maxHeadroom = new NumberSetting("Max headroom",
        "How tall the space over a step may be. Below Min headroom it counts as Min headroom.", 5, 2, 8, 1,
        " blocks").under(staircases);
    private final BoolSetting diagonals = new BoolSetting("Diagonals",
        "Marks tunnels that zigzag at an angle through the rock.", true);
    private final NumberSetting diagonalSteps = new NumberSetting("Diagonal steps",
        "How many straight runs a zigzag must have.", 3, 2, 32, 1).under(diagonals);
    private final NumberSetting shortestRun = new NumberSetting("Shortest run",
        "How few blocks a zigzag may go straight before it turns.", 1, 1, 8, 1, " blocks")
        .under(diagonals);
    private final NumberSetting longestRun = new NumberSetting("Longest run",
        "How many blocks a zigzag may go straight before it turns. Below Shortest run it counts as Shortest run.",
        3, 1, 8, 1, " blocks").under(diagonals);
    private final ChoiceListSetting skipped = new ChoiceListSetting("Skipped dimensions",
        "Dimensions where nothing is searched. Click to pick them.", ServerInfo::dimensions)
        .labelled(ServerInfo::dimensionName);
    private final BoolSetting fullHeight = new BoolSetting("Full height",
        "Draws tunnels and steps as tall as their opening. Shafts are always drawn whole.", false);
    private final NumberSetting height = new NumberSetting("Box height",
        "How tall the drawn box of a tunnel or step is.", 0.15, 0.05, 1, 0.05).min(0.01).unless(fullHeight);
    private final BoolSetting connected = new BoolSetting("Connected",
        "Draws touching boxes as one shape without the faces they share.", true);
    private final BoxStyle style = BoxStyle.shapeOnly(BoxStyle.Shape.BOTH);
    private final ColorSetting corridorColor = new ColorSetting("Corridor colour",
        "Colour of straight tunnels.", 39, false).under(corridors);
    private final ColorSetting shaftColor = new ColorSetting("Shaft colour",
        "Colour of shafts.", 190, false).under(shafts);
    private final ColorSetting stairsColor = new ColorSetting("Stairs colour",
        "Colour of staircases.", 120, false).under(staircases);
    private final ColorSetting diagonalColor = new ColorSetting("Diagonal colour",
        "Colour of zigzag tunnels.", 280, false).under(diagonals);

    private final ChunkScanner<Find> scanner = new ChunkScanner<>(DugPassages.VIEW_CHUNKS);

    private final JoinedCells<Find> joined = new JoinedCells<>(find -> BlockPos.asLong(find.x(), find.y(), find.z()));

    // The rules the cached chunks were scanned with.
    private Rules rules;

    public TunnelEsp() {
        super("TunnelESP", "Highlights hand dug tunnels shafts and staircases underground.", Category.HUNTING);
        addSettings(range);
        addSettings(band.settings());
        addSettings(widest, onlyAir, naturalWalls, corridors, minLength, minHeight, maxHeight, shafts, minDepth,
            waterShafts, staircases, stairSteps, minHeadroom, maxHeadroom, diagonals, diagonalSteps, shortestRun,
            longestRun, skipped, fullHeight, height, connected);
        addSettings(style.settings());
        addSettings(corridorColor, shaftColor, stairsColor, diagonalColor);
        searchTags("tunnel", "base finder", "corridor", "hole", "shaft", "staircase", "stairs", "diagonal",
            "highway");
    }

    @Override
    public String getSuffix() {
        return count(scanner.size());
    }

    @Override
    protected void onEnable() {
        forget();
    }

    @Override
    protected void onDisable() {
        forget();
    }

    private void forget() {
        scanner.reset();
        joined.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (skipped.contains(mc.level.dimension().identifier().toString())) {
            forget();
            return;
        }
        // Each max counts as at least its min.
        HeightBand.Rows rows = band.rows();
        Rules now = new Rules(rows.from(), rows.to(), widest.getInt(), onlyAir.isOn(), naturalWalls.isOn(),
            corridors.isOn(), minLength.getInt(), minHeight.getInt(), Math.max(minHeight.getInt(), maxHeight.getInt()),
            shafts.isOn(), minDepth.getInt(), waterShafts.isOn(), staircases.isOn(), stairSteps.getInt(),
            minHeadroom.getInt(), Math.max(minHeadroom.getInt(), maxHeadroom.getInt()), diagonals.isOn(),
            diagonalSteps.getInt(), shortestRun.getInt(), Math.max(shortestRun.getInt(), longestRun.getInt()));
        // The scan reads the rules once a chunk. A change has to throw the cache away. A chunk's
        // verdict reads Widest blocks into its neighbours and a change that far in rescans it.
        if (!now.equals(rules)) {
            rules = now;
            scanner.borderReach(Math.max(DugPassages.CELL_BORDER, now.widest() + 1));
            scanner.reset();
        }
        Rules scanRules = rules;
        scanner.update(range.getInt(), (view, out) -> DugPassages.scan(view, scanRules, out));
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        List<Find> finds = scanner.results();
        boolean join = connected.isOn();
        if (join) {
            joined.update(finds);
        }
        for (Find find : finds) {
            double tall = find.kind() == Kind.SHAFT || fullHeight.isOn() ? find.height() : height.getValue();
            AABB box = new AABB(find.x(), find.y(), find.z(), find.x() + 1, find.y() + tall, find.z() + 1);
            int color = colorOf(find.kind());
            style.drawJoined(batch, box, join ? joined.hiddenSides(find) : 0, color, color, true);
        }
    }

    private int colorOf(Kind kind) {
        return switch (kind) {
            case CORRIDOR -> corridorColor.getColor();
            case SHAFT -> shaftColor.getColor();
            case STAIRS -> stairsColor.getColor();
            case DIAGONAL -> diagonalColor.getColor();
        };
    }
}
