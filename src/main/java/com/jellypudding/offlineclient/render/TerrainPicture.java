package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.util.TerrainLayer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix3x2fStack;

// The land of a map as one texture with a texel for every block or more. It paints a margin past
// what the view shows and only paints again once the view leaves what it painted or the land
// changes. The pose moves and turns it between paints. Render thread only.
public final class TerrainPicture implements AutoCloseable {

    // Texels painted past each side of the view. The view moves this far before a repaint.
    private static final int MARGIN = 32;
    // The texture grows in steps of this many texels.
    private static final int GROWTH = 64;
    // The most texels the view itself takes across or down. A wider view gives each texel more land.
    private static final int MOST_TEXELS = 2048;
    // From this many blocks a texel each texel takes the colour of its whole chunk.
    private static final double COARSE = SectionPos.SECTION_SIZE;
    // Land seen to hold nothing such as the void.
    private static final int VOID_COLOUR = 0xFF0C0C12;
    // One colour for every value a packed byte can hold.
    private static final int PACKED_COLOURS = 256;
    private static final int[] PALETTE = palette();

    private DynamicTexture texture;
    private int textureWidth;
    private int textureHeight;
    // What the texture holds. Its corner texel counted in steps from the world origin and how
    // many texels across and down were painted.
    private int left;
    private int top;
    private int across;
    private int down;
    private double step;
    private TerrainLayer layer;
    private ChunkTints tints;
    private int layerRevision;
    private int tintRevision;
    private long paintedAt;

    // Draws the layer into the view with the tints over it. Null tints paint none. A change to
    // the land is painted once the gap has passed since the last paint. A picture that shows each
    // block keeps the tiles of the land it painted.
    public void draw(GuiGraphicsExtractor context, MapView view, TerrainLayer layer, ChunkTints tints, long gapMs) {
        double reachX = view.reachAcross() * view.zoom();
        double reachZ = view.reachDown() * view.zoom();
        double wanted = Math.max(Math.max(1, view.zoom()), 2 * Math.max(reachX, reachZ) / MOST_TEXELS);
        int needLeft = Mth.floor((view.centreX() - reachX) / wanted);
        int needTop = Mth.floor((view.centreZ() - reachZ) / wanted);
        int needRight = Mth.ceil((view.centreX() + reachX) / wanted);
        int needBottom = Mth.ceil((view.centreZ() + reachZ) / wanted);
        boolean covers = texture != null && wanted == step && layer == this.layer && tints == this.tints
            && needLeft >= left && needTop >= top && needRight <= left + across && needBottom <= top + down;
        boolean changed = layer.revision() != layerRevision || tints != null && tints.revision() != tintRevision;
        if (!covers || changed && System.currentTimeMillis() - paintedAt >= gapMs) {
            paint(layer, tints, wanted, needLeft - MARGIN, needTop - MARGIN, needRight - needLeft + 2 * MARGIN,
                needBottom - needTop + 2 * MARGIN);
        }
        if (step < COARSE) {
            layer.want(Mth.floor(left * step), Mth.floor(top * step), Mth.ceil((left + across) * step),
                Mth.ceil((top + down) * step));
        }
        double zoom = view.zoom();
        Matrix3x2fStack pose = context.pose();
        context.enableScissor(view.left(), view.top(), view.left() + view.width(), view.top() + view.height());
        pose.pushMatrix();
        pose.translate(view.middleX(), view.middleY());
        pose.rotate(view.turn());
        pose.translate((float) ((left * step - view.centreX()) / zoom), (float) ((top * step - view.centreZ()) / zoom));
        float scale = (float) (step / zoom);
        pose.scale(scale, scale);
        context.blit(texture.getTextureView(), texture.getSampler(), 0, 0, across, down,
            0, across / (float) textureWidth, 0, down / (float) textureHeight);
        pose.popMatrix();
        context.disableScissor();
    }

    private void paint(TerrainLayer layer, ChunkTints tints, double step, int left, int top, int across, int down) {
        fit(across, down);
        NativeImage pixels = texture.getPixels();
        boolean coarse = step >= COARSE;
        boolean tinted = tints != null && !tints.isEmpty();
        for (int row = 0; row < down; row++) {
            int z = Mth.floor((top + row + 0.5) * step);
            for (int column = 0; column < across; column++) {
                int x = Mth.floor((left + column + 0.5) * step);
                int colour = PALETTE[layer.packed(x, z, coarse)];
                if (tinted) {
                    colour = tints.over(colour, SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
                }
                pixels.setPixel(column, row, colour);
            }
        }
        texture.upload();
        this.layer = layer;
        this.tints = tints;
        this.step = step;
        this.left = left;
        this.top = top;
        this.across = across;
        this.down = down;
        layerRevision = layer.revision();
        tintRevision = tints == null ? 0 : tints.revision();
        paintedAt = System.currentTimeMillis();
    }

    // A texture at least this big. It only ever grows.
    private void fit(int across, int down) {
        if (texture != null && textureWidth >= across && textureHeight >= down) {
            return;
        }
        int width = Math.max(textureWidth, roundUp(across));
        int height = Math.max(textureHeight, roundUp(down));
        close();
        textureWidth = width;
        textureHeight = height;
        texture = new DynamicTexture(() -> "OfflineClient map", width, height, true);
    }

    private static int roundUp(int texels) {
        return (texels + GROWTH - 1) / GROWTH * GROWTH;
    }

    // Lets go of the texture. The next draw makes a new one.
    @Override
    public void close() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }

    // The colour of every packed map colour. A colour of none is clear apart from the void.
    private static int[] palette() {
        int[] colours = new int[PACKED_COLOURS];
        for (int packed = 0; packed < colours.length; packed++) {
            colours[packed] = MapColor.getColorFromPackedId(packed);
        }
        colours[TerrainLayer.VOID] = VOID_COLOUR;
        return colours;
    }
}
