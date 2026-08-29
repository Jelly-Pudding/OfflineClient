package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;

// Each item is skipped in its own renderer mixin. ClearView covers what is drawn over the screen.
public final class NoRender extends Module {

    public enum Banners { SHOW, POLE_ONLY, HIDE }

    private final BoolSetting armour = new BoolSetting("Armour",
        "Nobody is drawn wearing armour.", false);
    private final BoolSetting beaconBeams = new BoolSetting("Beacon beams",
        "Beacons keep their block and lose the beam.", true);
    private final BoolSetting fallingBlocks = new BoolSetting("Falling blocks",
        "Sand and gravel and anvils vanish whilst they fall.", false);
    private final BoolSetting worldBorder = new BoolSetting("World border",
        "The border wall is not drawn.", false);
    private final BoolSetting glint = new BoolSetting("Enchantment glint",
        "Enchanted items lose their shimmer.", false);
    private final BoolSetting fireworks = new BoolSetting("Firework explosions",
        "Rockets still fly but the burst is not drawn.", false);
    private final BoolSetting signText = new BoolSetting("Sign text",
        "Signs are drawn blank.", false);
    private final EnumSetting<Banners> banners = new EnumSetting<>("Banners",
        "How banners are drawn.", Banners.SHOW)
        .describe(Banners.SHOW, "Banners are drawn as normal.")
        .describe(Banners.POLE_ONLY, "The pole stays and the cloth goes.")
        .describe(Banners.HIDE, "Banners are not drawn at all.");
    private final BoolSetting itemFrames = new BoolSetting("Item frames",
        "Item frames and whatever they hold are not drawn.", false);

    public NoRender() {
        super("NoRender", "Leaves out things in the world you do not need drawn.",
            Category.RENDER);
        addSettings(armour, beaconBeams, fallingBlocks, worldBorder, glint, fireworks,
            signText, banners, itemFrames);
        searchTags("hide", "no armor", "beacon", "glint", "banner", "sign");
    }

    public boolean hidesArmour() {
        return isEnabled() && armour.isOn();
    }

    public boolean hidesBeaconBeams() {
        return isEnabled() && beaconBeams.isOn();
    }

    public boolean hidesFallingBlocks() {
        return isEnabled() && fallingBlocks.isOn();
    }

    public boolean hidesWorldBorder() {
        return isEnabled() && worldBorder.isOn();
    }

    public boolean hidesGlint() {
        return isEnabled() && glint.isOn();
    }

    public boolean hidesFireworks() {
        return isEnabled() && fireworks.isOn();
    }

    public boolean hidesSignText() {
        return isEnabled() && signText.isOn();
    }

    public Banners bannerMode() {
        return isEnabled() ? banners.getValue() : Banners.SHOW;
    }

    public boolean hidesItemFrames() {
        return isEnabled() && itemFrames.isOn();
    }
}
