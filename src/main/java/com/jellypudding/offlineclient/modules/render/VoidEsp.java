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
import com.jellypudding.offlineclient.util.ChunkScanner;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class VoidEsp extends Module {

    // The top bedrock layer of the nether roof. The only one always solid.
    private static final int NETHER_ROOF_Y = 127;

    private static final int FLOOR_COLOR = 0xFFFF3050;
    private static final int ROOF_COLOR = 0xFFFF30FF;

    private record Hole(int x, int y, int z, boolean roof) {
    }

    private final NumberSetting range = new NumberSetting("Range",
        "Chunk radius to search around you.", 6, 1, 12, 1, " chunks").max(24);
    private final NumberSetting depth = new NumberSetting("Depth",
        "How many bedrock layers must be missing.", 1, 1, 5, 1).min(1).max(5);
    private final BoolSetting airOnly = new BoolSetting("Air only",
        "Only count columns that are completely open.", false);
    private final BoolSetting netherRoof = new BoolSetting("Nether roof",
        "Also search the roof whilst you are in the nether.", true);
    private final BoolSetting fill = new BoolSetting("Fill",
        "Adds a faint tint inside each box.", true);

    private final ChunkScanner<Hole> scanner = new ChunkScanner<>();

    public VoidEsp() {
        super("VoidESP", "Shows holes in the bedrock that lead to the void.", Category.RENDER);
        addSettings(range, depth, airOnly, netherRoof, fill);
        searchTags("void", "bedrock", "roof");
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
        if (mc.level.dimension() == Level.END) {
            scanner.reset();
            return;
        }
        int layers = depth.getInt();
        boolean open = airOnly.isOn();
        boolean roof = netherRoof.isOn() && mc.level.dimension() == Level.NETHER;

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
        boolean tint = fill.isOn();
        for (Hole hole : holes) {
            int color = hole.roof() ? ROOF_COLOR : FLOOR_COLOR;
            AABB box = new AABB(hole.x(), hole.y(), hole.z(),
                hole.x() + 1, hole.y() + 1, hole.z() + 1);
            batch.outlineBox(box, color, true);
            if (tint) {
                batch.solidBox(box, ColorUtil.withAlpha(color, 60), true);
            }
        }
    }
}
