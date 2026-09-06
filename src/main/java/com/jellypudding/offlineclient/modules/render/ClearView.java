package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;

// Overlay removal happens in HudMixin and ScreenEffectRendererMixin and
// FogRendererMixin and ParticleEngineMixin which check these settings.
public final class ClearView extends Module {

    public enum Particles { KEEP, HIDE_ALL, HIDE_CHOSEN }

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
    private final EnumSetting<Particles> particles = new EnumSetting<>("Particles",
        "Which particles are stopped from spawning.", Particles.KEEP)
        .describe(Particles.KEEP, "Every particle spawns as normal.")
        .describe(Particles.HIDE_ALL, "No particle spawns at all.")
        .describe(Particles.HIDE_CHOSEN, "Only the particle types picked below are stopped.");
    private final RegistryListSetting<ParticleType<?>> particleTypes = new RegistryListSetting<>(
        "Particle types", "The particles that are stopped.", BuiltInRegistries.PARTICLE_TYPE, List.of())
        .under(particles, Particles.HIDE_CHOSEN);
    private final BoolSetting eatingCrumbs = new BoolSetting("Eating crumbs",
        "Stops the crumbs that fly whilst something is eaten.", false);
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
    private final BoolSetting crosshair = new BoolSetting("Crosshair",
        "Hides the crosshair.", false);
    private final BoolSetting magicText = new BoolSetting("Magic text",
        "Scrambled text is drawn as plain letters.", false);
    private final BoolSetting signatureBar = new BoolSetting("Chat signature bar",
        "Hides the coloured bar beside signed chat lines.", false);

    public ClearView() {
        super("ClearView", "Removes screen overlays that hide what you need to see.", Category.RENDER);
        addSettings(pumpkin, powderSnow, vignette, fire, water, blockInFace, fog, particles,
            particleTypes, eatingCrumbs, spyglass, bossBars, scoreboard, titles, itemNames,
            effectIcons, totemPop, crosshair, magicText, signatureBar);
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

    public boolean blocksAllParticles() {
        return particles.is(Particles.HIDE_ALL);
    }

    public boolean blocksParticle(ParticleType<?> type) {
        return particles.is(Particles.HIDE_ALL)
            || particles.is(Particles.HIDE_CHOSEN) && particleTypes.contains(type);
    }

    public boolean blocksEatingCrumbs() {
        return eatingCrumbs.isOn();
    }

    public boolean blocksCrosshair() {
        return crosshair.isOn();
    }

    public boolean blocksMagicText() {
        return magicText.isOn();
    }

    public boolean blocksSignatureBar() {
        return signatureBar.isOn();
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
