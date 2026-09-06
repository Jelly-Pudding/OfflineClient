package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChunkScanner;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class VoidEsp extends Module {

    // The top bedrock layer of the nether roof. The only one always solid.
    private static final int NETHER_ROOF_Y = 127;

    // Holes only ever touch sideways. Every one sits on the same layer.
    private static final Direction[] AROUND = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private record Hole(int x, int y, int z, boolean roof) {
    }

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you. Only chunks the game has loaded can be searched.", 6, 1, 12, 1, " chunks").max(64);
    private final NumberSetting depth = new NumberSetting("Depth",
        "How many bedrock layers must be missing.", 1, 1, 5, 1).min(1).max(5);
    private final BoolSetting airOnly = new BoolSetting("Air only",
        "Only count columns that are completely open.", false);
    private final BoolSetting netherRoof = new BoolSetting("Nether roof",
        "Also search the roof whilst you are in the nether.", true);
    private final BoolSetting connected = new BoolSetting("Connected",
        "Holes that touch leave out the faces they share so a wide hole draws as one box.", true);
    private final BoxStyle floorStyle = new BoxStyle("Floor", BoxStyle.Shape.BOTH, 351);
    private final BoxStyle roofStyle = new BoxStyle("Roof", BoxStyle.Shape.BOTH, 300)
        .under(netherRoof);

    private final ChunkScanner<Hole> scanner = new ChunkScanner<>();

    // The holes the neighbour keys were built from.
    private List<Hole> known = List.of();
    private final LongOpenHashSet keys = new LongOpenHashSet();

    // The scan settings the cached chunks were built with.
    private int lastLayers = -1;
    private boolean lastOpen;
    private boolean lastRoof;

    public VoidEsp() {
        super("VoidESP", "Shows holes in the bedrock that lead to the void.", Category.RENDER);
        addSettings(range, depth, airOnly, netherRoof, connected);
        addSettings(floorStyle.settings());
        addSettings(roofStyle.settings());
        searchTags("void", "bedrock", "roof");
    }

    @Override
    public String getSuffix() {
        return scanner.size() == 0 ? null : String.valueOf(scanner.size());
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
        known = List.of();
        keys.clear();
    }

    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        scanner.markChanged(event.getPacket());
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (mc.level.dimension() == Level.END) {
            scanner.reset();
            return;
        }
        int layers = depth.getInt();
        boolean open = airOnly.isOn();
        boolean roof = netherRoof.isOn() && mc.level.dimension() == Level.NETHER;
        // The scan reads these once a chunk. A change has to throw the cache away.
        if (layers != lastLayers || open != lastOpen || roof != lastRoof) {
            lastLayers = layers;
            lastOpen = open;
            lastRoof = roof;
            scanner.reset();
        }

        scanner.update(range.getInt(), (view, out) -> {
            int baseX = view.pos().getMinBlockX();
            int baseZ = view.pos().getMinBlockZ();
            int floorY = view.minY();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    if (isHole(view, worldX, worldZ, floorY, 1, layers, open)) {
                        out.add(new Hole(worldX, floorY, worldZ, false));
                    }
                    if (roof && isHole(view, worldX, worldZ, NETHER_ROOF_Y, -1, layers, open)) {
                        out.add(new Hole(worldX, NETHER_ROOF_Y, worldZ, true));
                    }
                }
            }
        });
    }

    private static boolean isHole(ChunkScanner.View view, int x, int z, int startY, int step,
                                  int layers, boolean airOnly) {
        for (int i = 0; i < layers; i++) {
            BlockState state = view.get(x, startY + step * i, z);
            if (airOnly ? !state.isAir() : state.is(Blocks.BEDROCK)) {
                return false;
            }
        }
        return true;
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        List<Hole> holes = scanner.results();
        boolean join = connected.isOn();
        if (join && holes != known) {
            known = holes;
            keys.clear();
            for (Hole hole : holes) {
                keys.add(BlockPos.asLong(hole.x(), hole.y(), hole.z()));
            }
        }
        for (Hole hole : holes) {
            AABB box = new AABB(hole.x(), hole.y(), hole.z(),
                hole.x() + 1, hole.y() + 1, hole.z() + 1);
            BoxStyle style = hole.roof() ? roofStyle : floorStyle;
            style.drawJoined(batch, box, join ? sharedSides(hole) : 0, true);
        }
    }

    // A bit for every side that another hole is pressed against.
    private int sharedSides(Hole hole) {
        long key = BlockPos.asLong(hole.x(), hole.y(), hole.z());
        int hidden = 0;
        for (Direction side : AROUND) {
            if (keys.contains(BlockPos.offset(key, side))) {
                hidden |= DrawBatch.sideBit(side);
            }
        }
        return hidden;
    }
}
