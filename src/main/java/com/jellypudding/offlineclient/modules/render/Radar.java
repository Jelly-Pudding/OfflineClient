package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.hud.elements.RadarElement;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.player.Player;

// What the radar shows and how. The Radar element of the HUD draws it whilst this is on.
public final class Radar extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How many blocks the radar reaches from you to its nearest edge.", 64, 16, 256, 8, " blocks").min(4);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns the radar until the way you face points up.", true);
    private final BoolSetting players = new BoolSetting("Players",
        "Show other players.", true);
    private final BoolSetting sleeping = new BoolSetting("Sleeping players",
        "Keep players who are in a bed on the radar.", true).under(players);
    private final BoolSetting hostile = new BoolSetting("Hostile mobs",
        "Show zombies and skeletons and the rest.", false);
    private final BoolSetting slimes = new BoolSetting("Slimes",
        "Slimes and magma cubes show.", true).under(hostile);
    private final BoolSetting passive = new BoolSetting("Passive mobs",
        "Show animals and villagers.", false);
    private final BoolSetting water = new BoolSetting("Water animals",
        "Show fish and squid and dolphins.", false);
    private final BoolSetting bats = new BoolSetting("Bats",
        "Show bats.", false);
    private final BoolSetting invisible = new BoolSetting("Invisible",
        "Show entities that are under an invisibility effect.", true);
    private final BoolSetting items = new BoolSetting("Items",
        "Show dropped items.", false);
    private final BoolSetting names = new BoolSetting("Names",
        "Write player names next to their dot.", true);
    private final BoolSetting height = new BoolSetting("Height tint",
        "Fade dots that are far above or below you.", true);
    private final NumberSetting tintDepth = new NumberSetting("Tint depth",
        "Blocks above or below you at which a dot is at its faintest.", 40, 5, 128, 1, " blocks")
        .min(1).under(height);
    private final NumberSetting tintStrength = new NumberSetting("Tint strength",
        "How faint the furthest dots get.", 65, 10, 100, 5, "%").min(0).max(100)
        .under(height);

    public Radar() {
        super("Radar", "Draws a small map of nearby entities where you place it with .hud edit.",
            Category.RENDER);
        addSettings(range, rotate, players, sleeping, hostile, slimes, passive, water, bats, invisible,
            items, names, height, tintDepth, tintStrength);
        searchTags("entity map");
    }

    @Override
    protected void onEnable() {
        if (!Modules.hudShows(RadarElement.class)) {
            ChatUtil.message("§bRadar §7shows on the HUD. Turn on the HUD and its Radar element to see it.");
        }
    }

    // True for an entity that gets a dot.
    public boolean shows(Entity entity) {
        if (entity == mc.player) {
            return false;
        }
        if (entity instanceof LivingEntity living && !living.isAlive()) {
            return false;
        }
        if (!invisible.isOn() && entity.isInvisible()) {
            return false;
        }
        return switch (EntityUtil.kindOf(entity)) {
            case PLAYER -> players.isOn() && !entity.isSpectator()
                && (sleeping.isOn() || !((Player) entity).isSleeping());
            case HOSTILE -> hostile.isOn() && (slimes.isOn() || !(entity instanceof AbstractCubeMob));
            case PASSIVE -> passive.isOn();
            case WATER -> water.isOn();
            case AMBIENT -> bats.isOn();
            case ITEM -> items.isOn();
            case OTHER -> false;
        };
    }

    public double range() {
        return range.getValue();
    }

    public boolean turns() {
        return rotate.isOn();
    }

    public boolean writesNames() {
        return names.isOn();
    }

    // The colour of its dot. Height tint fades it the further above or below you it is.
    public int colorOf(Entity entity) {
        int color = EntityUtil.colorOf(entity);
        if (!height.isOn() || mc.player == null) {
            return color;
        }
        double drop = Math.abs(entity.getY() - mc.player.getY());
        float faintest = 1 - tintStrength.getFloat() / 100f;
        return ColorUtil.fade(color, (float) Math.clamp(1 - drop / tintDepth.getValue(), faintest, 1));
    }
}
