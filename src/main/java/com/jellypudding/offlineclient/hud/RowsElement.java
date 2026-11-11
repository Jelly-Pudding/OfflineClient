package com.jellypudding.offlineclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

// An element that is a block of text rows one under another. Each row lines up with the side the
// element is anchored to.
public abstract class RowsElement extends HudElement {

    protected RowsElement(String name, String description, boolean on, double startX, double startY) {
        super(name, description, on, startX, startY);
    }

    // Asked several times a frame. Rows that take long to work out belong in a RowCache.
    protected abstract List<TextRow> rows(Font font);

    @Override
    public final void render(GuiGraphicsExtractor context, Font font) {
        TextRow.draw(context, font, rows(font), alignment());
    }

    @Override
    public final int width(Font font) {
        return TextRow.widest(rows(font));
    }

    @Override
    public final int height(Font font) {
        return TextRow.height(font, rows(font).size());
    }
}
