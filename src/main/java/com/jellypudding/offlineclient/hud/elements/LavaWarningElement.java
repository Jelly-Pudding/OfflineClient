package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.hud.RowCache;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.Tally;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

// It looks through whatever is in between. Stone under your boots is no help once you dig through
// it.
public final class LavaWarningElement extends HudElement {

    private static final int SAMPLE_DEPTH = 3;

    // A box that ends right on a block edge does not reach into the next block.
    private static final double EDGE = 1e-7;

    private final NumberSetting depth = new NumberSetting("Lava warning depth",
        "How many blocks under your feet it looks for lava.", 8, 1, 32, 1, " blocks");
    private final ColorSetting color = new ColorSetting("Lava warning colour",
        "Colour of the warning.", 20, 1f, 1f, false);
    private final RowCache<String> lines = new RowCache<>(this::build);

    public LavaWarningElement() {
        super("Lava warning", "Warns whilst lava lies close under your feet and says how far down.", false, 50, 60);
        add(depth, color);
    }

    @Override
    public boolean visible() {
        return isActive() && !shown().isEmpty();
    }

    private List<String> shown() {
        List<String> built = lines.get();
        return built.isEmpty() && editing() ? List.of(lineFor(SAMPLE_DEPTH)) : built;
    }

    private List<String> build() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return List.of();
        }
        Level level = player.level();
        int reach = (int) Math.min(depth.getValue(), level.getHeight());
        int below = lavaBelow(level, player.getBoundingBox(), reach, level.getMinY());
        return below == 0 ? List.of() : List.of(lineFor(below));
    }

    private static String lineFor(int below) {
        return "Lava " + Tally.counted(below, "block") + " below";
    }

    public static int lavaBelow(BlockGetter level, AABB box, int reach, int lowest) {
        int feet = Mth.floor(box.minY);
        int deepest = Math.max(lowest, feet - reach);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = feet - 1; y >= deepest; y--) {
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - EDGE); x++) {
                for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - EDGE); z++) {
                    if (level.getFluidState(pos.set(x, y, z)).is(FluidTags.LAVA)) {
                        return feet - y;
                    }
                }
            }
        }
        return 0;
    }

    // The line may have gone between the frame asking whether to draw and the draw itself.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        for (String line : shown()) {
            write(context, font, line, 0, 0, color.getColor());
        }
    }

    @Override
    public int width(Font font) {
        int widest = 1;
        for (String line : shown()) {
            widest = Math.max(widest, font.width(line));
        }
        return widest;
    }

    @Override
    public int height(Font font) {
        return font.lineHeight;
    }
}
