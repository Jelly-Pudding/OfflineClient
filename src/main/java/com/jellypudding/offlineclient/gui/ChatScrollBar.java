package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.Mth;

// A grabbable bar beside the open chat. Vanilla draws a sliver you can only look at.
// Everything is worked out in screen pixels from the chat's own scaled layout.
public final class ChatScrollBar {

    // Where the chat sits on screen. The same numbers ChatComponent lays itself out with.
    private static final int CHAT_LEFT = 4;
    private static final int CHAT_BOTTOM_MARGIN = 40;
    // Vanilla puts its sliver four scaled pixels past the chat width.
    private static final int TRACK_GAP = 4;

    private static final int WIDTH = 3;
    // Extra grab room either side of the track.
    private static final int GRAB = 3;
    private static final int MIN_THUMB = 8;

    // The screen rectangle of the track and thumb plus what the thumb stands for.
    private record Geometry(int x, int top, int bottom, int thumbTop, int thumbBottom,
                            double scale, int lines, int page, int lineHeight) {

        int range() {
            return lines - page;
        }
    }

    private boolean dragging;
    private double dragStartY;
    private int dragStartPos;

    private static ChatComponent chat() {
        return OfflineClient.MC.gui.hud.getChat();
    }

    // Null whilst everything fits on one page and there is nothing to scroll.
    private static Geometry geometry(int screenHeight) {
        ChatComponent chat = chat();
        int lines = chat.trimmedMessages.size();
        int page = chat.getLinesPerPage();
        if (lines <= page || page <= 0) {
            return null;
        }
        double scale = chat.getScale();
        int lineHeight = chat.getLineHeight();
        int chatWidth = Mth.ceil(chat.getWidth() / scale);
        int bottomScaled = Mth.floor((screenHeight - CHAT_BOTTOM_MARGIN) / scale);
        int pageHeight = page * lineHeight;

        int x = (int) Math.round((CHAT_LEFT + chatWidth + TRACK_GAP) * scale);
        int bottom = (int) Math.round(bottomScaled * scale);
        int top = (int) Math.round((bottomScaled - pageHeight) * scale);
        int thumbHeight = Math.max(MIN_THUMB, (int) Math.round(pageHeight * pageHeight * scale
            / (lines * lineHeight)));
        int thumbBottom = bottom - (int) Math.round((bottom - top - thumbHeight)
            * (double) chat.chatScrollbarPos / (lines - page));
        return new Geometry(x, top, bottom, thumbBottom - thumbHeight, thumbBottom, scale,
            lines, page, lineHeight);
    }

    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        Geometry g = geometry(context.guiHeight());
        if (g == null) {
            return;
        }
        boolean hovered = dragging || over(g, mouseX, mouseY);
        context.fill(g.x(), g.top(), g.x() + WIDTH, g.bottom(), GuiTheme.SCROLL_TRACK);
        context.guiRenderState.up();
        context.fill(g.x(), g.thumbTop(), g.x() + WIDTH, g.thumbBottom(),
            hovered ? GuiTheme.accent() : GuiTheme.scrollThumb());
    }

    private static boolean over(Geometry g, double mouseX, double mouseY) {
        return mouseX >= g.x() - GRAB && mouseX < g.x() + WIDTH + GRAB
            && mouseY >= g.top() && mouseY < g.bottom();
    }

    // True when the click landed on the bar. The thumb starts a drag and the track jumps.
    public boolean mouseClicked(double mouseX, double mouseY, int screenHeight) {
        Geometry g = geometry(screenHeight);
        if (g == null || !over(g, mouseX, mouseY)) {
            return false;
        }
        if (mouseY < g.thumbTop() || mouseY >= g.thumbBottom()) {
            // The thumb lands centred under the pointer.
            double fromBottom = (g.bottom() - mouseY) / (g.bottom() - g.top());
            scrollTo((int) Math.round(fromBottom * g.lines() - g.page() / 2.0), g);
        }
        dragging = true;
        dragStartY = mouseY;
        dragStartPos = chat().chatScrollbarPos;
        return true;
    }

    public boolean mouseDragged(double mouseY, int screenHeight) {
        Geometry g = geometry(screenHeight);
        if (!dragging || g == null) {
            return false;
        }
        double travel = g.bottom() - g.top() - (g.thumbBottom() - g.thumbTop());
        double moved = (dragStartY - mouseY) / Math.max(1, travel) * g.range();
        scrollTo(dragStartPos + (int) Math.round(moved), g);
        return true;
    }

    public void mouseReleased() {
        dragging = false;
    }

    private static void scrollTo(int position, Geometry g) {
        ChatComponent chat = chat();
        chat.scrollChat(Math.clamp(position, 0, g.range()) - chat.chatScrollbarPos);
    }
}
