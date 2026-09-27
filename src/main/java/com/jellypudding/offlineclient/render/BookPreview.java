package com.jellypudding.offlineclient.render;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;

import java.util.List;
import java.util.Optional;

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

    // The lines that fit on the page picture.
    private static final int MAX_LINES = 18;
    // The narrowest letter with its gap is two pixels. No more text than this can show.
    private static final int MAX_CHARACTERS = MAX_LINES * TEXT_WIDTH / 2;

    private final Component page;

    public BookPreview(Component page) {
        this.page = page;
    }

    // The start of a page up to what the picture can show with its styles kept. A server can
    // send a page millions of letters long and the tooltip lays it out every frame.
    public static MutableComponent head(Component page) {
        MutableComponent head = Component.empty();
        int[] left = {MAX_CHARACTERS};
        page.visit((style, text) -> {
            String part = text.length() > left[0] ? text.substring(0, left[0]) : text;
            head.append(Component.literal(part).setStyle(style));
            left[0] -= part.length();
            return left[0] > 0 ? Optional.empty() : FormattedText.STOP_ITERATION;
        }, Style.EMPTY);
        return head;
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
        List<FormattedCharSequence> lines = font.split(page, TEXT_WIDTH);
        for (int i = 0; i < Math.min(lines.size(), MAX_LINES); i++) {
            context.text(font, lines.get(i), 0, i * LINE_HEIGHT, INK, false);
        }
        pose.popMatrix();
    }
}
