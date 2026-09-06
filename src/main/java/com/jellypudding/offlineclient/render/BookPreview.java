package com.jellypudding.offlineclient.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;

// The first page of a book drawn on the open book picture inside a tooltip.
public final class BookPreview implements ClientTooltipComponent {

    private static final int SIZE = 128;
    // The page picture fills three quarters of the book texture.
    private static final int TEXTURE_SPAN = 171;
    private static final int TEXT_X = 16;
    private static final int TEXT_Y = 12;
    private static final int TEXT_WIDTH = 112;
    private static final int LINE_HEIGHT = 8;
    private static final float TEXT_SCALE = 0.7f;
    private static final int INK = 0xFF000000;

    private final Component page;

    public BookPreview(Component page) {
        this.page = page;
    }

    @Override
    public int getWidth(Font font) {
        return SIZE - 16;
    }

    @Override
    public int getHeight(Font font) {
        return SIZE + 6;
    }

    @Override
    public void extractImage(Font font, int x, int y, int tooltipWidth, int tooltipHeight,
                             GuiGraphicsExtractor context) {
        context.blit(RenderPipelines.GUI_TEXTURED, BookViewScreen.BOOK_LOCATION, x - 10, y,
            0, 0, SIZE, SIZE, TEXTURE_SPAN, TEXTURE_SPAN);
        Matrix3x2fStack pose = context.pose();
        pose.pushMatrix();
        pose.translate(x + TEXT_X, y + TEXT_Y);
        pose.scale(TEXT_SCALE, TEXT_SCALE);
        int lineY = 0;
        for (FormattedCharSequence line : font.split(page, TEXT_WIDTH)) {
            context.text(font, line, 0, lineY, INK, false);
            lineY += LINE_HEIGHT;
        }
        pose.popMatrix();
    }
}
