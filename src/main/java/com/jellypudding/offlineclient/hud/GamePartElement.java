package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

// Switched on the game still draws it and this decides where and how big.
public final class GamePartElement extends HudElement {

    private final GamePart part;
    private final BoolSetting hide;

    public GamePartElement(GamePart part) {
        super(part.label(), part.description(), false, part.usualX(), part.usualY(), Background.NONE);
        this.part = part;
        hide = new BoolSetting("Hide " + part.label().toLowerCase(Locale.ROOT), "Takes it off the screen.", false)
            .under(active);
        add(hide);
    }

    public GamePart part() {
        return part;
    }

    public boolean hidden() {
        return isActive() && hide.isOn();
    }

    public void setHidden(boolean hidden) {
        hide.setValue(hidden);
    }

    @Override
    public boolean visible() {
        return isActive() ? !hide.isOn() : part.steady();
    }

    @Override
    public Box home(int screenWidth, int screenHeight) {
        return part.home(screenWidth, screenHeight);
    }

    @Override
    public boolean touchesEdges() {
        return true;
    }

    @Override
    public boolean staysWhenHidden() {
        return true;
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
