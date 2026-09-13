package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.util.ChunkRebuild;
import net.minecraft.world.level.Level;

// Recolours the world on your screen only. The mixins ask this what to paint.
public final class Ambience extends Module {

    // Ticks the settings must sit still before the chunks are rebuilt.
    private static final int REBUILD_DELAY = 10;

    private final BoolSetting endSky = new BoolSetting("End sky",
        "Draws the End sky box in every dimension.", false);
    private final BoolSetting customSky = new BoolSetting("Custom sky colour",
        "Paints the sky in colours of your own.", false);
    private final ColorSetting overworldSky = new ColorSetting("Overworld sky",
        "Sky colour in the overworld.", 210, 1f, 1f, false)
        .under(customSky);
    private final ColorSetting netherSky = new ColorSetting("Nether sky",
        "Sky colour in the nether.", 0, 1f, 0.4f, false)
        .under(customSky);
    private final ColorSetting endSkyColor = new ColorSetting("End sky colour",
        "Sky colour in the end.", 277, 0.67f, 0.35f, false)
        .under(customSky);
    private final BoolSetting customClouds = new BoolSetting("Custom cloud colour",
        "Paints the clouds in a colour of your own.", false);
    private final ColorSetting cloudColor = new ColorSetting("Cloud colour",
        "Colour of the clouds.", 0, 1f, 0.4f, false)
        .under(customClouds);
    private final BoolSetting customLightning = new BoolSetting("Custom lightning colour",
        "Paints lightning bolts in a colour of your own.", false);
    private final ColorSetting lightningColor = new ColorSetting("Lightning colour",
        "Colour of a lightning bolt.", 0, 1f, 0.4f, false)
        .under(customLightning);
    private final BoolSetting customGrass = new BoolSetting("Custom grass colour",
        "Paints grass in a colour of your own.", false);
    private final ColorSetting grassColor = new ColorSetting("Grass colour",
        "Colour of grass.", 0, 1f, 0.4f, false)
        .under(customGrass);
    private final BoolSetting customFoliage = new BoolSetting("Custom foliage colour",
        "Paints leaves and vines in a colour of your own.", false);
    private final ColorSetting foliageColor = new ColorSetting("Foliage colour",
        "Colour of leaves and vines.", 0, 1f, 0.4f, false)
        .under(customFoliage);
    private final BoolSetting customWater = new BoolSetting("Custom water colour",
        "Paints water in a colour of your own.", false);
    private final ColorSetting waterColor = new ColorSetting("Water colour",
        "Colour of water.", 0, 1f, 0.4f, false)
        .under(customWater);
    private final BoolSetting customLava = new BoolSetting("Custom lava colour",
        "Paints lava in a colour of your own.", false);
    private final ColorSetting lavaColor = new ColorSetting("Lava colour",
        "Colour of lava.", 0, 1f, 0.4f, false)
        .under(customLava);
    private final BoolSetting customFog = new BoolSetting("Custom fog colour",
        "Paints the fog in a colour of your own.", false);
    private final ColorSetting fogColor = new ColorSetting("Fog colour",
        "Colour of the fog.", 0, 1f, 0.4f, false)
        .under(customFog);

    // The block colours only change when a chunk is built again.
    private int lastBlockColours;
    private int rebuildCooldown;

    public Ambience() {
        super("Ambience", "Recolours the sky and the clouds and the ground around you.",
            Category.RENDER);
        addSettings(endSky, customSky, overworldSky, netherSky, endSkyColor,
            customClouds, cloudColor, customLightning, lightningColor,
            customGrass, grassColor, customFoliage, foliageColor,
            customWater, waterColor, customLava, lavaColor, customFog, fogColor);
        searchTags("sky colour", "cloud colour", "grass colour", "water colour", "fog colour");
    }

    @Override
    protected void onEnable() {
        lastBlockColours = blockColourKey();
        rebuild();
    }

    @Override
    protected void onDisable() {
        rebuild();
    }

    // The rebuild fires once the colours sit still for half a second.
    @Subscribe
    private void onTick(TickEvent event) {
        int key = blockColourKey();
        if (key != lastBlockColours) {
            lastBlockColours = key;
            rebuildCooldown = REBUILD_DELAY;
        } else if (rebuildCooldown > 0 && --rebuildCooldown == 0) {
            rebuild();
        }
    }

    private int blockColourKey() {
        int key = 0;
        key = key * 31 + (customGrass.isOn() ? grassColor.getColor() : 0);
        key = key * 31 + (customFoliage.isOn() ? foliageColor.getColor() : 0);
        key = key * 31 + (customWater.isOn() ? waterColor.getColor() : 0);
        key = key * 31 + (customLava.isOn() ? lavaColor.getColor() : 0);
        return key;
    }

    private static void rebuild() {
        ChunkRebuild.now();
    }

    public boolean drawsEndSky() {
        return isEnabled() && endSky.isOn();
    }

    public boolean paintsSky() {
        return isEnabled() && customSky.isOn();
    }

    // The colour for the dimension the player is standing in.
    public int skyColor() {
        if (mc.level == null) {
            return overworldSky.getColor();
        }
        if (mc.level.dimension() == Level.NETHER) {
            return netherSky.getColor();
        }
        return mc.level.dimension() == Level.END ? endSkyColor.getColor() : overworldSky.getColor();
    }

    public boolean paintsClouds() {
        return isEnabled() && customClouds.isOn();
    }

    public int cloudColor() {
        return cloudColor.getColor();
    }

    public boolean paintsLightning() {
        return isEnabled() && customLightning.isOn();
    }

    public int lightningColor() {
        return lightningColor.getColor();
    }

    public boolean paintsGrass() {
        return isEnabled() && customGrass.isOn();
    }

    public int grassColor() {
        return grassColor.getColor();
    }

    public boolean paintsFoliage() {
        return isEnabled() && customFoliage.isOn();
    }

    public int foliageColor() {
        return foliageColor.getColor();
    }

    public boolean paintsWater() {
        return isEnabled() && customWater.isOn();
    }

    public int waterColor() {
        return waterColor.getColor();
    }

    public boolean paintsLava() {
        return isEnabled() && customLava.isOn();
    }

    public int lavaColor() {
        return lavaColor.getColor();
    }

    public boolean paintsFog() {
        return isEnabled() && customFog.isOn();
    }

    public int fogColor() {
        return fogColor.getColor();
    }
}
