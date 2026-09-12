package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// How a render module colours an entity. One colour per kind of entity or a fade
// with distance or with health or the same colour for everything.
public final class EntityColors {

    public enum Mode { TYPE, DISTANCE, HEALTH, SINGLE }

    // Hue zero is red and hue one hundred and twenty is green.
    private static final float GREEN_HUE = 120;

    private final EnumSetting<Mode> mode;
    private final ColorSetting playerColor;
    private final ColorSetting hostileColor;
    private final ColorSetting passiveColor;
    private final ColorSetting waterColor;
    private final ColorSetting ambientColor;
    private final ColorSetting itemColor;
    private final ColorSetting otherColor;
    private final NumberSetting fadeDistance;
    private final ColorSetting singleColor;
    private final BoolSetting friendColor;

    public EntityColors(Mode defaultMode) {
        mode = new EnumSetting<>("Colour mode", "How the colour is chosen.", defaultMode)
            .describe(Mode.TYPE, "Each kind of entity has its own colour.")
            .describe(Mode.DISTANCE, "Red up close through yellow to green far away.")
            .describe(Mode.HEALTH, "Green at full health down to red near death. Things without health keep their kind's colour.")
            .describe(Mode.SINGLE, "One colour for everything.");
        playerColor = new ColorSetting("Player colour", "Colour of other players.", 0, false)
            .under(mode, Mode.TYPE);
        hostileColor = new ColorSetting("Hostile colour", "Colour of mobs that attack you.", 9, 0.81f, 1f, false)
            .under(mode, Mode.TYPE);
        passiveColor = new ColorSetting("Passive colour", "Colour of animals and other peaceful mobs.", 120, 0.57f, 0.88f, false)
            .under(mode, Mode.TYPE);
        waterColor = new ColorSetting("Water colour", "Colour of fish and squid and other sea life.", 240, 0.9f, 1f, false)
            .under(mode, Mode.TYPE);
        ambientColor = new ColorSetting("Ambient colour", "Colour of bats and other ambient creatures.", 0, 0f, 0.1f, false)
            .under(mode, Mode.TYPE);
        itemColor = new ColorSetting("Item colour", "Colour of dropped items.", 51, 0.75f, 1f, false)
            .under(mode, Mode.TYPE);
        otherColor = new ColorSetting("Other colour", "Colour of everything else such as crystals and boats.", 28, 0.87f, 1f, false)
            .under(mode, Mode.TYPE);
        fadeDistance = new NumberSetting("Fade distance",
            "Blocks away at which the colour has gone fully green.", 32, 8, 128, 1, " blocks").min(1)
            .under(mode, Mode.DISTANCE);
        singleColor = new ColorSetting("Colour", "The colour everything gets.", 0, false)
            .under(mode, Mode.SINGLE);
        friendColor = new BoolSetting("Friend colour", "Paint friends blue whatever the mode.", true);
    }

    public Setting<?>[] settings() {
        return new Setting<?>[] {mode, playerColor, hostileColor, passiveColor, waterColor, ambientColor,
            itemColor, otherColor, fadeDistance, singleColor, friendColor};
    }

    public int colorOf(Entity entity) {
        if (friendColor.isOn() && EntityUtil.isFriend(entity)) {
            return EntityUtil.FRIEND_COLOR;
        }
        return switch (mode.getValue()) {
            case TYPE -> typeColor(entity);
            case DISTANCE -> distanceColor(entity);
            case HEALTH -> entity instanceof LivingEntity living ? healthColor(living) : typeColor(entity);
            case SINGLE -> singleColor.getColor();
        };
    }

    private int typeColor(Entity entity) {
        if (entity instanceof Player) {
            return playerColor.getColor();
        }
        return switch (EntityUtil.kindOf(entity)) {
            case ITEM -> itemColor.getColor();
            case HOSTILE -> hostileColor.getColor();
            case PASSIVE -> passiveColor.getColor();
            case WATER -> waterColor.getColor();
            case AMBIENT -> ambientColor.getColor();
            case PLAYER, OTHER -> otherColor.getColor();
        };
    }

    // Red at the feet and green at the fade distance with yellow half way.
    private int distanceColor(Entity entity) {
        Player self = OfflineClient.MC.player;
        double away = self == null ? fadeDistance.getValue() : self.distanceTo(entity);
        float share = (float) Math.clamp(away / fadeDistance.getValue(), 0, 1);
        return ColorUtil.hsv(share * GREEN_HUE, 0.85f, 1f);
    }

    private static int healthColor(LivingEntity living) {
        float max = living.getMaxHealth();
        if (max <= 0) {
            return EntityUtil.colorOf(living);
        }
        float left = Math.clamp(EntityUtil.totalHealth(living) / max, 0f, 1f);
        return ColorUtil.hsv(left * GREEN_HUE, 0.85f, 1f);
    }
}
