package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

// A small plan of what surrounds your feet so you can see at a glance whether a
// crystal can reach you.
public final class HoleElement extends HudElement {

    private enum Strength { UNBREAKABLE, STRONG, WEAK, OPEN }

    private static final int CELL = 8;
    private static final int GAP = 1;
    private static final int GRID = 3;

    private final ColorSetting unbreakable = new ColorSetting("Hole unbreakable colour",
        "Colour of a side no blast can take.", 120, 0.75f, 0.9f, false);
    private final ColorSetting strong = new ColorSetting("Hole strong colour",
        "Colour of a side that survives a blast.", 200, 0.7f, 0.9f, false);
    private final ColorSetting weak = new ColorSetting("Hole weak colour",
        "Colour of a side a blast would take out.", 40, 0.85f, 1f, false);
    private final ColorSetting open = new ColorSetting("Hole open colour",
        "Colour of a side that is not there at all.", 0, 0.85f, 0.9f, false);
    private final BoolSetting word = new BoolSetting("Hole word",
        "Write whether you are safe under the plan.", true);

    public HoleElement() {
        super("Hole", "What surrounds your feet and whether it holds.", false, 96, 55);
        add(unbreakable, strong, weak, open, word);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    // Only the four sides matter. The middle is where you stand.
    private static Strength at(BlockPos pos) {
        BlockState state = OfflineClient.MC.level.getBlockState(pos);
        if (!BlockUtil.blocksMotion(state)) {
            return Strength.OPEN;
        }
        if (state.getBlock().defaultDestroyTime() < 0) {
            return Strength.UNBREAKABLE;
        }
        return state.getBlock().getExplosionResistance() >= BlockUtil.BLAST_PROOF
            ? Strength.STRONG : Strength.WEAK;
    }

    private int colorOf(Strength strength) {
        return switch (strength) {
            case UNBREAKABLE -> unbreakable.getColor();
            case STRONG -> strong.getColor();
            case WEAK -> weak.getColor();
            case OPEN -> open.getColor();
        };
    }

    private String verdict() {
        LocalPlayer player = OfflineClient.MC.player;
        BlockPos feet = player.blockPosition();
        boolean safe = true;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Strength strength = at(feet.relative(side));
            safe &= strength == Strength.UNBREAKABLE || strength == Strength.STRONG;
        }
        return safe ? "In a hole" : "Out in the open";
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return;
        }
        BlockPos feet = player.blockPosition();
        for (int row = 0; row < GRID; row++) {
            for (int column = 0; column < GRID; column++) {
                int x = column * (CELL + GAP);
                int y = row * (CELL + GAP);
                if (row == 1 && column == 1) {
                    context.fill(x, y, x + CELL, y + CELL, 0x60FFFFFF);
                    continue;
                }
                // The corners cannot be reached by a blast through a side.
                if (row != 1 && column != 1) {
                    continue;
                }
                BlockPos pos = feet.offset(column - 1, 0, row - 1);
                context.fill(x, y, x + CELL, y + CELL, colorOf(at(pos)));
            }
        }
        if (word.isOn()) {
            context.text(font, verdict(), 0, GRID * (CELL + GAP) + GAP, 0xFFECECF4, true);
        }
    }

    @Override
    public int width(Font font) {
        int grid = GRID * (CELL + GAP) - GAP;
        return word.isOn() ? Math.max(grid, font.width(verdict())) : grid;
    }

    @Override
    public int height(Font font) {
        int grid = GRID * (CELL + GAP) - GAP;
        return word.isOn() ? grid + GAP + font.lineHeight : grid;
    }
}
