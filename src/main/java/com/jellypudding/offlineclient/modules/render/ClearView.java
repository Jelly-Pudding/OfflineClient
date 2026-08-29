package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;

// Overlay removal happens in HudMixin and ScreenEffectRendererMixin and
// FogRendererMixin and ParticleEngineMixin which check these settings.
public final class ClearView extends Module {

    private final BoolSetting pumpkin = new BoolSetting("Pumpkin",
        "Removes the carved pumpkin overlay.", true);
    private final BoolSetting powderSnow = new BoolSetting("Powder snow",
        "Removes the powder snow frost overlay.", true);
    private final BoolSetting vignette = new BoolSetting("Vignette",
        "Removes the dark border around the screen edges.", false);
    private final BoolSetting fire = new BoolSetting("Fire",
        "Removes the flames drawn over your view whilst you burn.", true);
    private final BoolSetting water = new BoolSetting("Water",
        "Removes the blue tint drawn over your view underwater.", false);
    private final BoolSetting blockInFace = new BoolSetting("Block in face",
        "Removes the block texture drawn over your view when your head is inside one.", true);
    private final BoolSetting fog = new BoolSetting("Fog",
        "Pushes fog far enough away that it never hides anything.", false);
    private final BoolSetting particles = new BoolSetting("Particles",
        "Stops every particle from spawning.", false);
    private final BoolSetting spyglass = new BoolSetting("Spyglass",
        "Removes the black frame whilst you look through a spyglass.", false);
    private final BoolSetting bossBars = new BoolSetting("Boss bars",
        "Hides boss health bars.", false);
    private final BoolSetting scoreboard = new BoolSetting("Scoreboard",
        "Hides the sidebar scoreboard.", false);
    private final BoolSetting titles = new BoolSetting("Titles",
        "Hides titles the server puts in the middle of the screen.", false);
    private final BoolSetting itemNames = new BoolSetting("Item names",
        "Hides the name that pops up above the hotbar on a slot change.", false);
    private final BoolSetting effectIcons = new BoolSetting("Effect icons",
        "Hides the potion effect icons in the top right corner.", false);
    private final BoolSetting totemPop = new BoolSetting("Totem pop",
        "Skips the totem animation that fills the screen.", false);

    public ClearView() {
        super("ClearView", "Removes screen overlays that hide what you need to see.", Category.RENDER);
        addSettings(pumpkin, powderSnow, vignette, fire, water, blockInFace, fog, particles,
            spyglass, bossBars, scoreboard, titles, itemNames, effectIcons, totemPop);
        searchTags("no fog", "no fire", "no overlay", "no particles", "norender");
    }

    public boolean blocksPumpkin() {
        return pumpkin.isOn();
    }

    public boolean blocksPowderSnow() {
        return powderSnow.isOn();
    }

    public boolean blocksVignette() {
        return vignette.isOn();
    }

    public boolean blocksFire() {
        return fire.isOn();
    }

    public boolean blocksWater() {
        return water.isOn();
    }

    public boolean blocksBlockInFace() {
        return blockInFace.isOn();
    }

    public boolean blocksFog() {
        return fog.isOn();
    }

    public boolean blocksParticles() {
        return particles.isOn();
    }

    public boolean blocksSpyglass() {
        return spyglass.isOn();
    }

    public boolean blocksBossBars() {
        return bossBars.isOn();
    }

    public boolean blocksScoreboard() {
        return scoreboard.isOn();
    }

    public boolean blocksTitles() {
        return titles.isOn();
    }

    public boolean blocksItemNames() {
        return itemNames.isOn();
    }

    public boolean blocksEffectIcons() {
        return effectIcons.isOn();
    }

    public boolean blocksTotemPop() {
        return totemPop.isOn();
    }
}
