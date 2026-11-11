package com.jellypudding.offlineclient.hud;

import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Locale;

// A piece of the game's own HUD. Left switched off the game draws it where it always does.
// Switched on the game still draws it and this decides where and how big. Hide then takes it
// off the screen.
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

    // Hide only acts on a part that is switched on and only shows then.
    public boolean hidden() {
        return isActive() && hide.isOn();
    }

    public void setHidden(boolean hidden) {
        hide.setValue(hidden);
    }

    // A part the client places is on the screen unless Hide is on. One left to the game only
    // counts when it is a part the game draws most of the time.
    @Override
    public boolean visible() {
        return isActive() ? !hide.isOn() : part.steady();
    }

    @Override
    public Box home(int screenWidth, int screenHeight) {
        return part.home(screenWidth, screenHeight);
    }

    // It may sit flush with the edge as it does at home.
    @Override
    public boolean touchesEdges() {
        return true;
    }

    // The game draws its own bars whatever the rest of the overlay does.
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
