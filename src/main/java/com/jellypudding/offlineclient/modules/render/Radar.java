package com.jellypudding.offlineclient.modules.render;

import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.RegistryListSetting;
import com.jellypudding.offlineclient.util.ColorUtil;
import com.jellypudding.offlineclient.util.EntityColors;
import com.jellypudding.offlineclient.util.EntityKind;
import com.jellypudding.offlineclient.util.EntitySpecies;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

// The Radar element of the HUD draws it whilst this is on.
public final class Radar extends Module {

    private final NumberSetting range = new NumberSetting("Range",
        "How many blocks the radar reaches from you to its nearest edge.", 64, 16, 256, 8, " blocks").min(4);
    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Turns the radar until the way you face points up.", true);
    private final BoolSetting players = new BoolSetting("Players",
        "Shows other players.", true);
    private final BoolSetting sleeping = new BoolSetting("Sleeping players",
        "Keeps players who are in a bed on the radar.", true).under(players);
    private final BoolSetting creative = new BoolSetting("Creative players",
        "Keeps players in creative mode on the radar.", true).under(players);
    private final BoolSetting hostile = new BoolSetting("Hostile mobs",
        "Shows zombies and skeletons and the rest.", false);
    private final BoolSetting slimes = new BoolSetting("Slimes",
        "Slimes and magma cubes show.", true).under(hostile);
    private final BoolSetting neutral = new BoolSetting("Neutral mobs",
        "Shows mobs that only fight back such as endermen and wolves. Their dots are amber.", false);
    private final BoolSetting passive = new BoolSetting("Passive mobs",
        "Shows animals and villagers.", false);
    private final BoolSetting water = new BoolSetting("Water animals",
        "Shows fish and squid and dolphins and axolotls and turtles.", false);
    private final BoolSetting bats = new BoolSetting("Bats",
        "Shows bats.", false);
    private final RegistryListSetting<EntityType<?>> others = new RegistryListSetting<>("Other entities",
        "Other kinds of entity that get a dot such as crystals and boats. A kind on this list shows"
            + " whatever the switches above say.", BuiltInRegistries.ENTITY_TYPE, List.of());
    private final BoolSetting invisible = new BoolSetting("Invisible",
        "Shows entities that are under an invisibility effect.", true);
    private final BoolSetting items = new BoolSetting("Items",
        "Shows dropped items.", false);
    private final BoolSetting names = new BoolSetting("Names",
        "Writes player names next to their dot.", true);
    private final BoolSetting height = new BoolSetting("Height tint",
        "Fades dots that are far above or below you.", true);
    private final NumberSetting tintDepth = new NumberSetting("Tint depth",
        "Blocks above or below you at which a dot is at its faintest.", 40, 5, 128, 1, " blocks")
        .min(1).under(height);
    private final NumberSetting tintStrength = new NumberSetting("Tint strength",
        "How faint the furthest dots get.", 65, 10, 100, 5, "%").min(0).max(100)
        .under(height);

    public Radar() {
        super("Radar", "Draws a small map of nearby entities where you place it with .hud edit.",
            Category.RENDER);
        addSettings(range, rotate, players, sleeping, creative, hostile, slimes, neutral, passive, water, bats,
            others, invisible, items, names, height, tintDepth, tintStrength);
        searchTags("entity map");
    }

    @Override
    protected void onEnable() {
        Modules.tellHudOff(this);
    }

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
        if (others.contains(entity.getType())) {
            return true;
        }
        // A tiny slime does no harm and still goes by the row that names it.
        if (EntitySpecies.of(entity) == EntitySpecies.SLIME) {
            return hostile.isOn() && slimes.isOn();
        }
        return switch (EntityKind.of(entity)) {
            case PLAYER -> players.isOn() && !entity.isSpectator() && !leftOut(entity);
            case HOSTILE -> hostile.isOn();
            case NEUTRAL -> neutral.isOn();
            case PASSIVE -> passive.isOn();
            case WATER -> water.isOn();
            case AMBIENT -> bats.isOn();
            case ITEM -> items.isOn();
            case OTHER -> false;
        };
    }

    // A mannequin neither sleeps nor has a game mode.
    private boolean leftOut(Entity entity) {
        return entity instanceof Player player && (!sleeping.isOn() && player.isSleeping()
            || !creative.isOn() && player.isCreative());
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

    public int colorOf(Entity entity) {
        int color = EntityColors.kindColor(entity);
        if (!height.isOn() || mc.player == null) {
            return color;
        }
        double drop = Math.abs(entity.getY() - mc.player.getY());
        float faintest = 1 - tintStrength.getFloat() / 100f;
        return ColorUtil.fade(color, (float) Math.clamp(1 - drop / tintDepth.getValue(), faintest, 1));
    }
}
