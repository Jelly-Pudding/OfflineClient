package com.jellypudding.offlineclient.hud.elements;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.gui.GuiTheme;
import com.jellypudding.offlineclient.hud.HudElement;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.Holes;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

public final class HoleElement extends HudElement {

    private static final int CELL = 8;
    private static final int GAP = 1;
    private static final int PLAN = 3 * CELL + 2 * GAP;
    private static final int YOU = 0x60FFFFFF;
    private static final int CRACK = 0xB0FF2020;
    private static final int CRACK_STAGES = 10;
    private static final float ICON_SCALE = CELL / 16f;
    private static final int SAMPLE_CRACK = 6;

    // The column and row of each side in the order sides gives them. The front is at the top.
    private static final int[][] PLACES = {{1, 0}, {0, 1}, {2, 1}, {1, 2}};

    // Minus one is untouched.
    private record Side(BlockState state, Holes.Wall wall, int crack) {

        static Side of(BlockState state, int crack) {
            return new Side(state, Holes.wallOf(state), crack);
        }
    }

    private static final List<Side> SAMPLE = List.of(Side.of(Blocks.OBSIDIAN.defaultBlockState(), SAMPLE_CRACK),
        Side.of(Blocks.BEDROCK.defaultBlockState(), -1), Side.of(Blocks.OBSIDIAN.defaultBlockState(), -1),
        Side.of(Blocks.OBSIDIAN.defaultBlockState(), -1));

    private final ColorSetting unbreakable = new ColorSetting("Hole unbreakable colour",
        "Colour of a side no blast can take.", 120, 0.75f, 0.9f, false);
    private final ColorSetting strong = new ColorSetting("Hole strong colour",
        "Colour of a side that survives a blast.", 200, 0.7f, 0.9f, false);
    private final ColorSetting weak = new ColorSetting("Hole weak colour",
        "Colour of a side a blast would take out.", 40, 0.85f, 1f, false);
    private final ColorSetting open = new ColorSetting("Hole open colour",
        "Colour of a side that is not there at all.", 0, 0.85f, 0.9f, false);
    private final BoolSetting cracks = new BoolSetting("Hole cracks",
        "Fills a side red as someone breaks it.", true);
    private final BoolSetting turns = new BoolSetting("Hole turns with you",
        "Puts the way you face at the top of the plan.", false);
    private final BoolSetting icons = new BoolSetting("Hole icons",
        "Draws each side as the block it is made of.", false);
    private final BoolSetting word = new BoolSetting("Hole word",
        "Writes whether you stand in a hole under the plan.", true);

    public HoleElement() {
        super("Hole", "What surrounds your feet and whether it holds.", false, 96, 55);
        add(unbreakable, strong, weak, open, cracks, turns, icons, word);
    }

    @Override
    public boolean visible() {
        return isActive() && OfflineClient.MC.player != null;
    }

    private List<Side> sides() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player == null) {
            return List.of();
        }
        Direction front = turns.isOn()
            ? Direction.fromYRot(OfflineClient.MC.gameRenderer.mainCamera().yRot()) : Direction.NORTH;
        List<BlockPos> body = Holes.feetBlocks(player);
        List<Side> sides = new ArrayList<>(PLACES.length);
        List<Direction> ways = List.of(front, front.getCounterClockWise(), front.getClockWise(),
            front.getOpposite());
        for (Direction way : ways) {
            sides.add(weakest(body, way));
        }
        if (editing() && sides.stream().allMatch(side -> side.wall() == Holes.Wall.OPEN)) {
            return SAMPLE;
        }
        return sides;
    }

    // Somebody across an edge has two blocks beside the body on one side.
    private static Side weakest(List<BlockPos> body, Direction way) {
        Side weakest = null;
        for (BlockPos block : body) {
            BlockPos pos = block.relative(way);
            if (body.contains(pos)) {
                continue;
            }
            Side side = Side.of(BlockUtil.state(pos), BlockUtil.crackStage(pos));
            int order = weakest == null ? -1 : side.wall().compareTo(weakest.wall());
            if (order < 0 || (order == 0 && side.crack() > weakest.crack())) {
                weakest = side;
            }
        }
        return weakest;
    }

    private int colorOf(Holes.Wall wall) {
        return switch (wall) {
            case UNBREAKABLE -> unbreakable.getColor();
            case BLAST_PROOF -> strong.getColor();
            case WEAK -> weak.getColor();
            case OPEN -> open.getColor();
        };
    }

    // The word follows the one hole rule of the client whatever the plan shows.
    private static String verdict(List<Side> sides) {
        boolean safe = sides == SAMPLE || !sides.isEmpty() && Holes.playerInHole();
        return safe ? "In a hole" : "Out in the open";
    }

    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
        List<Side> sides = sides();
        if (sides.isEmpty()) {
            return;
        }
        context.fill(CELL + GAP, CELL + GAP, 2 * CELL + GAP, 2 * CELL + GAP, YOU);
        for (int i = 0; i < sides.size(); i++) {
            Side side = sides.get(i);
            int x = PLACES[i][0] * (CELL + GAP);
            int y = PLACES[i][1] * (CELL + GAP);
            context.fill(x, y, x + CELL, y + CELL, colorOf(side.wall()));
            if (icons.isOn()) {
                icon(context, side.state(), x, y);
            }
            if (cracks.isOn() && side.crack() >= 0) {
                int depth = Math.round((side.crack() + 1) * CELL / (float) CRACK_STAGES);
                context.fill(x, y + CELL - depth, x + CELL, y + CELL, CRACK);
            }
        }
        if (word.isOn()) {
            write(context, font, verdict(sides), 0, PLAN + GAP, GuiTheme.HUD_TEXT);
        }
    }

    private static void icon(GuiGraphicsExtractor context, BlockState state, int x, int y) {
        ItemStack stack = new ItemStack(state.getBlock());
        if (stack.isEmpty()) {
            return;
        }
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(ICON_SCALE, ICON_SCALE);
        context.item(stack, 0, 0);
        pose.popMatrix();
    }

    @Override
    public int width(Font font) {
        return word.isOn() ? Math.max(PLAN, font.width(verdict(sides()))) : PLAN;
    }

    @Override
    public int height(Font font) {
        return word.isOn() ? PLAN + GAP + font.lineHeight : PLAN;
    }
}
