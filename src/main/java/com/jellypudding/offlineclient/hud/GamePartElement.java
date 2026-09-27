package com.jellypudding.offlineclient.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

// A piece of the game's own HUD. The game still draws it and this only decides
// where and how big. Switched off it is hidden.
public final class GamePartElement extends HudElement {

    private final GamePart part;

    public GamePartElement(GamePart part) {
        super(part.label(), part.description(), true, part.usualX(), part.usualY());
        this.part = part;
    }

    public GamePart part() {
        return part;
    }

    @Override
    public Box home(int screenWidth, int screenHeight) {
        return part.home(screenWidth, screenHeight);
    }

    // The game draws it through HudManager.drawGamePart.
    @Override
    public void render(GuiGraphicsExtractor context, Font font) {
    }

    @Override
    public int width(Font font) {
        return part.width();
    }

    @Override
    public int height(Font font) {
        return part.height();
    }
}
