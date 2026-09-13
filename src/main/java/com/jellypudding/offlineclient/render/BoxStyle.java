package com.jellypudding.offlineclient.render;

import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import com.jellypudding.offlineclient.util.ColorUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// The shape and colours of the boxes a render module draws.
public final class BoxStyle {

    public enum Shape { LINES, SIDES, BOTH }

    private static final float DEFAULT_SATURATION = 0.75f;

    private final EnumSetting<Shape> shape;
    // Null for a style whose colours come from the module per box.
    private final ColorSetting lineColor;
    private final ColorSetting fillColor;
    private final NumberSetting fillOpacity;

    // A switch the whole style sits beneath. Always open until under is called.
    private Supplier<Boolean> gate = () -> true;

    // A prefix such as "Safe" gives "Safe shape" and "Safe line colour"
    // for a module with several styles.
    public BoxStyle(String prefix, Shape defaultShape, float hue) {
        this(prefix, defaultShape, hue, DEFAULT_SATURATION, true);
    }

    public BoxStyle(Shape defaultShape, float hue) {
        this("", defaultShape, hue, DEFAULT_SATURATION, true);
    }

    // A style whose colours start out white.
    public static BoxStyle white(Shape defaultShape) {
        return new BoxStyle("", defaultShape, 0, 0, true);
    }

    // A shape and opacity with no colours of its own for a module that colours each box itself.
    public static BoxStyle shapeOnly(Shape defaultShape) {
        return new BoxStyle("", defaultShape, 0, DEFAULT_SATURATION, false);
    }

    private BoxStyle(String prefix, Shape defaultShape, float hue, float saturation, boolean ownColours) {
        String lead = prefix.isEmpty() ? "" : prefix + " ";
        shape = new EnumSetting<>(cap(lead + "shape"), "How the box is drawn.", defaultShape)
            .describe(Shape.LINES, "An outline of the edges.")
            .describe(Shape.SIDES, "Tinted faces with no edges.")
            .describe(Shape.BOTH, "Edges and tinted faces.");
        lineColor = ownColours
            ? new ColorSetting(cap(lead + "line colour"), "Colour of the edges.", hue, saturation, 1f, false)
                .under(shape, () -> gate.get() && shape.isAny(Shape.LINES, Shape.BOTH))
            : null;
        fillColor = ownColours
            ? new ColorSetting(cap(lead + "fill colour"), "Colour of the faces.", hue, saturation, 1f, false)
                .under(shape, () -> gate.get() && shape.isAny(Shape.SIDES, Shape.BOTH))
            : null;
        fillOpacity = new NumberSetting(cap(lead + "fill opacity"), "How solid the faces are.",
            25, 5, 100, 5, "%").min(1).max(100)
            .under(shape, () -> gate.get() && shape.isAny(Shape.SIDES, Shape.BOTH));
    }

    // Makes every row of the style a sub option of a switch.
    public BoxStyle under(BoolSetting parent) {
        shape.under(parent);
        gate = parent::isOn;
        return this;
    }

    // Makes every row of the style a sub option of one or more choices of an enum.
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final <E extends Enum<E>> BoxStyle under(EnumSetting<E> parent, E... values) {
        shape.under(parent, values);
        gate = () -> parent.isAny(values);
        return this;
    }

    // The shape of the style for a module that draws the boxes itself.
    public Shape shape() {
        return shape.getValue();
    }

    // The fill opacity as a share from nought to one.
    public float fillShare() {
        return fillOpacity.getFloat() / 100f;
    }

    public int fillColor() {
        return ColorUtil.fade(fillColor.getColor(), fillOpacity.getFloat() / 100f);
    }

    private static String cap(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(shape);
        if (lineColor != null) {
            all.add(lineColor);
            all.add(fillColor);
        }
        all.add(fillOpacity);
        return all.toArray(new Setting<?>[0]);
    }

    public int lineColor() {
        return lineColor.getColor();
    }

    public void draw(DrawBatch batch, AABB box, boolean throughWalls) {
        draw(batch, box, lineColor.getColor(), fillColor.getColor(), throughWalls);
    }

    public void draw(DrawBatch batch, BlockPos pos, boolean throughWalls) {
        draw(batch, DrawBatch.blockBox(pos), throughWalls);
    }

    public void drawAll(DrawBatch batch, Iterable<BlockPos> positions, boolean throughWalls) {
        for (BlockPos pos : positions) {
            draw(batch, pos, throughWalls);
        }
    }

    // The same shape in a colour worked out per box such as an entity colour.
    public void draw(DrawBatch batch, AABB box, int color, boolean throughWalls) {
        draw(batch, box, color, color, throughWalls);
    }

    public void draw(DrawBatch batch, BlockPos pos, int color, boolean throughWalls) {
        draw(batch, DrawBatch.blockBox(pos), color, color, throughWalls);
    }

    // The usual colours at a share of their strength for a box that is fading out.
    public void drawFading(DrawBatch batch, AABB box, float strength, boolean throughWalls) {
        draw(batch, box, ColorUtil.fade(lineColor.getColor(), strength),
            ColorUtil.fade(fillColor.getColor(), strength), throughWalls);
    }

    // One face of a box in the usual colours.
    public void drawFace(DrawBatch batch, AABB box, Direction side, boolean throughWalls) {
        if (!shape.is(Shape.SIDES)) {
            batch.outlineFace(box, side, lineColor.getColor(), throughWalls);
        }
        if (!shape.is(Shape.LINES)) {
            batch.solidFace(box, side, fillColor(), throughWalls);
        }
    }

    // A box with the faces it shares with a neighbour left out. A run of
    // touching boxes reads as one shape. The mask holds a bit per side.
    public void drawJoined(DrawBatch batch, AABB box, int hidden, boolean throughWalls) {
        drawJoined(batch, box, hidden, lineColor.getColor(), fillColor.getColor(), throughWalls);
    }

    public void drawJoined(DrawBatch batch, AABB box, int hidden, int line, int fill,
                           boolean throughWalls) {
        if (!shape.is(Shape.SIDES)) {
            batch.outlineBoxPart(box, hidden, line, throughWalls);
        }
        if (!shape.is(Shape.LINES)) {
            batch.solidBoxPart(box, hidden, ColorUtil.fade(fill, fillShare()), throughWalls);
        }
    }

    public boolean drawsLines() {
        return !shape.is(Shape.SIDES);
    }

    public boolean drawsSides() {
        return !shape.is(Shape.LINES);
    }

    // The same shape with an edge colour and a face colour worked out per box.
    public void draw(DrawBatch batch, AABB box, int line, int fill, boolean throughWalls) {
        if (!shape.is(Shape.SIDES)) {
            batch.outlineBox(box, line, throughWalls);
        }
        if (!shape.is(Shape.LINES)) {
            batch.solidBox(box, ColorUtil.fade(fill, fillOpacity.getFloat() / 100f), throughWalls);
        }
    }
}
