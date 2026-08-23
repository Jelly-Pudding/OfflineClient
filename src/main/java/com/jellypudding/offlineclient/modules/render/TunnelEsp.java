package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.DrawBatch;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

// Highlights one wide two high corridors. Natural caves almost never make that shape.
public final class TunnelEsp extends Module {

    private static final int COLOR = 0xFFFFA030;

    // How open a neighbouring column is.
    private enum Side {
        WALKABLE,
        PART_BLOCKED,
        BLOCKED
    }

    private record Spot(int x, int y, int z) {
    }

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 4, 1, 8, 1, " chunks").max(12);
    private final NumberSetting bottom = new NumberSetting("Bottom",
        "Lowest height to search.", -60, -64, 320, 4).min(-2048).max(2048);
    private final NumberSetting top = new NumberSetting("Top",
        "Highest height to search.", 62, -64, 320, 4).min(-2048).max(2048);
    private final NumberSetting height = new NumberSetting("Box height",
        "How tall the drawn box is.", 0.15, 0.05, 1, 0.05).min(0.01);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", true);

    private final ChunkScanner<Spot> scanner = new ChunkScanner<>();

    public TunnelEsp() {
        super("TunnelESP", "Highlights hand dug tunnels underground.", Category.RENDER);
        addSettings(range, bottom, top, height, fill);
        searchTags("tunnel", "base finder", "corridor");
    }

    @Override
    public String getSuffix() {
        return scanner.size() == 0 ? null : String.valueOf(scanner.size());
    }

    @Override
    protected void onEnable() {
        scanner.reset();
    }

    @Override
    protected void onDisable() {
        scanner.reset();
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
        int low = bottom.getInt();
        int high = top.getInt();
        scanner.update(range.getInt(), (view, out) -> {
            int baseX = view.pos().getMinBlockX();
            int baseZ = view.pos().getMinBlockZ();
            int from = Math.max(low, view.minY());
            int to = Math.min(high, view.maxY() - 2);

            boolean[][][] found = new boolean[16][16][Math.max(0, to - from + 1)];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = from; y <= to; y++) {
                        if (isTunnel(view, baseX + x, y, baseZ + z)) {
                            found[x][z][y - from] = true;
                        }
                    }
                }
            }
            // A single lonely spot is almost always a natural gap.
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int i = 0; i < found[x][z].length; i++) {
                        if (found[x][z][i] && (onEdge(x, z) || hasNeighbour(found, x, z, i))) {
                            out.add(new Spot(baseX + x, from + i, baseZ + z));
                        }
                    }
                }
            }
        });
    }

    private static boolean onEdge(int x, int z) {
        return x == 0 || x == 15 || z == 0 || z == 15;
    }

    private static boolean hasNeighbour(boolean[][][] found, int x, int z, int i) {
        return found[x - 1][z][i] || found[x + 1][z][i]
            || found[x][z - 1][i] || found[x][z + 1][i];
    }

    // True for a spot that is open along one axis and walled on the other.
    private static boolean isTunnel(ChunkScanner.View view, int x, int y, int z) {
        if (!canStandIn(view, x, y, z)) {
            return false;
        }
        Side east = sideAt(view, x + 1, y, z);
        if (east == Side.PART_BLOCKED) {
            return false;
        }
        Side west = sideAt(view, x - 1, y, z);
        if (west == Side.PART_BLOCKED) {
            return false;
        }
        Side south = sideAt(view, x, y, z + 1);
        if (south == Side.PART_BLOCKED) {
            return false;
        }
        Side north = sideAt(view, x, y, z - 1);
        if (north == Side.PART_BLOCKED) {
            return false;
        }
        boolean alongX = east == Side.WALKABLE && west == Side.WALKABLE
            && south == Side.BLOCKED && north == Side.BLOCKED;
        boolean alongZ = south == Side.WALKABLE && north == Side.WALKABLE
            && east == Side.BLOCKED && west == Side.BLOCKED;
        return alongX || alongZ;
    }

    private static Side sideAt(ChunkScanner.View view, int x, int y, int z) {
        if (canStandIn(view, x, y, z)) {
            return Side.WALKABLE;
        }
        if (!passable(view, x, y, z) && !passable(view, x, y + 1, z)) {
            return Side.BLOCKED;
        }
        return Side.PART_BLOCKED;
    }

    // A floor to stand on with two clear blocks above and a ceiling on top.
    private static boolean canStandIn(ChunkScanner.View view, int x, int y, int z) {
        return solid(view, x, y - 1, z)
            && passable(view, x, y, z)
            && passable(view, x, y + 1, z)
            && !passable(view, x, y + 2, z);
    }

    private static boolean solid(ChunkScanner.View view, int x, int y, int z) {
        BlockState state = view.get(x, y, z);
        return BlockUtil.blocksMotion(state) && state.getFluidState().isEmpty();
    }

    private static boolean passable(ChunkScanner.View view, int x, int y, int z) {
        BlockState state = view.get(x, y, z);
        return !BlockUtil.blocksMotion(state) && state.getFluidState().isEmpty();
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        DrawBatch batch = event.getBatch();
        double boxHeight = height.getValue();
        boolean tint = fill.isOn();
        for (Spot spot : scanner.results()) {
            AABB box = new AABB(spot.x(), spot.y(), spot.z(),
                spot.x() + 1, spot.y() + boxHeight, spot.z() + 1);
            batch.outlineBox(box, COLOR, true);
            if (tint) {
                batch.solidBox(box, ColorUtil.withAlpha(COLOR, 60), true);
            }
        }
    }
}
