package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.modules.misc.ClickGuiModule;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.ColorSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

public final class EntityColors {

    public enum Mode { TYPE, DISTANCE, HEALTH, SINGLE }

    // A colour as its hue in degrees then its saturation and brightness from nought to one.
    private record Shade(float hue, float saturation, float brightness) {

        int color() {
            return ColorUtil.hsv(hue, saturation, brightness);
        }

        ColorSetting row(String name, String description) {
            return new ColorSetting(name, description, hue, saturation, brightness, false);
        }
    }

    // The colour of each kind before a player picks another. The rows of every module start
    // here and the radar and the world map keep to them.
    private static final Shade HOSTILE = new Shade(9, 0.81f, 1f);
    private static final Shade NEUTRAL = new Shade(42, 0.9f, 1f);
    private static final Shade PASSIVE = new Shade(120, 0.57f, 0.88f);
    private static final Shade WATER = new Shade(240, 0.9f, 1f);
    private static final Shade AMBIENT = new Shade(0, 0f, 0.1f);
    private static final Shade ITEM = new Shade(51, 0.75f, 1f);
    private static final Shade OTHER = new Shade(28, 0.87f, 1f);

    private static final float RED_HUE = 0;

    private final EnumSetting<Mode> mode;
    private final ColorSetting playerColor;
    private final ColorSetting hostileColor;
    private final ColorSetting neutralColor;
    private final ColorSetting passiveColor;
    private final ColorSetting waterColor;
    private final ColorSetting ambientColor;
    private final ColorSetting itemColor;
    private final ColorSetting otherColor;
    private final ColorSetting nearColor;
    private final ColorSetting farColor;
    private final NumberSetting farDistance;
    private final ColorSetting singleColor;
    private final BoolSetting teamColors;
    private final BoolSetting markFriends;
    private final BoolSetting markEnemies;

    // False for a module that never draws a player. The rows about players stay off its list.
    private boolean players = true;
    private boolean items = true;

    public EntityColors(Mode defaultMode) {
        this(defaultMode, RED_HUE);
    }

    public EntityColors(Mode defaultMode, float singleHue) {
        mode = new EnumSetting<>("Colour mode", "How the colour is chosen.", defaultMode)
            .describe(Mode.TYPE, "Each kind of entity has its own colour.")
            .describe(Mode.DISTANCE, "The near colour up close blending into the far colour further away.")
            .describe(Mode.HEALTH, "Green at full health down to red near death. Things without health keep their kind's colour.")
            .describe(Mode.SINGLE, "One colour for everything.");
        playerColor = new ColorSetting("Player colour", "Colour of other players.", 0, false)
            .under(mode, Mode.TYPE);
        hostileColor = HOSTILE.row("Hostile colour", "Colour of mobs that attack you.")
            .under(mode, Mode.TYPE);
        neutralColor = NEUTRAL.row("Neutral colour", "Colour of mobs that only fight back.")
            .under(mode, Mode.TYPE);
        passiveColor = PASSIVE.row("Passive colour", "Colour of animals and other peaceful mobs.")
            .under(mode, Mode.TYPE);
        waterColor = WATER.row("Water colour", "Colour of fish and squid and other sea life.")
            .under(mode, Mode.TYPE);
        ambientColor = AMBIENT.row("Ambient colour", "Colour of bats and other ambient creatures.")
            .under(mode, Mode.TYPE);
        itemColor = ITEM.row("Item colour", "Colour of dropped items.")
            .under(mode, Mode.TYPE);
        otherColor = OTHER.row("Other colour", "Colour of everything else such as crystals and boats.")
            .under(mode, Mode.TYPE);
        nearColor = new ColorSetting("Near colour", "Colour of whatever stands right beside you.",
            RED_HUE, ColorUtil.RAMP_SATURATION, 1f, false).under(mode, Mode.DISTANCE);
        farColor = new ColorSetting("Far colour", "Colour of whatever stands at the far distance or beyond.",
            ColorUtil.GREEN_HUE, ColorUtil.RAMP_SATURATION, 1f, false).under(mode, Mode.DISTANCE);
        farDistance = new NumberSetting("Far distance",
            "Blocks away at which the colour has blended fully into the far colour.", 32, 8, 128, 1, " blocks").min(1)
            .under(mode, Mode.DISTANCE);
        singleColor = new ColorSetting("Colour", "The colour everything gets.", singleHue, false)
            .under(mode, Mode.SINGLE);
        teamColors = Teams.colorSwitch();
        markFriends = new BoolSetting("Mark friends",
            "Paints friends in the friend colour whatever the mode. The colour is set in ClickGUI.", true);
        markEnemies = new BoolSetting("Mark enemies",
            "Paints the players on your enemy list in the enemy colour whatever the mode. The colour is set in"
                + " ClickGUI.", true);
    }

    public EntityColors withoutPlayers() {
        players = false;
        return this;
    }

    public EntityColors withoutItems() {
        items = false;
        return this;
    }

    public Setting<?>[] settings() {
        List<Setting<?>> all = new ArrayList<>();
        all.add(mode);
        if (players) {
            all.add(playerColor);
        }
        all.addAll(List.of(hostileColor, neutralColor, passiveColor, waterColor, ambientColor));
        if (items) {
            all.add(itemColor);
        }
        all.addAll(List.of(otherColor, nearColor, farColor, farDistance, singleColor));
        if (players) {
            all.addAll(List.of(teamColors, markFriends, markEnemies));
        }
        return all.toArray(new Setting<?>[0]);
    }

    // ClickGUI holds the colour and its default stands in before the client is built.
    public static int friendColor() {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        return gui != null ? gui.friendColor()
            : ColorUtil.hsv(ClickGuiModule.FRIEND_HUE, ClickGuiModule.FRIEND_SATURATION, 1f);
    }

    public static int enemyColor() {
        ClickGuiModule gui = Modules.get(ClickGuiModule.class);
        return gui != null ? gui.enemyColor()
            : ColorUtil.hsv(ClickGuiModule.ENEMY_HUE, ClickGuiModule.ENEMY_SATURATION, 1f);
    }

    // A friend or a listed enemy first and then the side the server puts a player on. Everyone
    // else and everything else goes by the mode.
    public int colorOf(Entity entity) {
        if (entity instanceof Player player) {
            if (markFriends.isOn() && EntityUtil.isFriend(player)) {
                return friendColor();
            }
            if (markEnemies.isOn() && EntityUtil.isListedEnemy(player)) {
                return enemyColor();
            }
            int team = teamColors.isOn() ? Teams.colorOf(player) : 0;
            if (team != 0) {
                return team;
            }
        }
        return switch (mode.getValue()) {
            case TYPE -> typeColor(entity);
            case DISTANCE -> distanceColor(entity);
            case HEALTH -> entity instanceof LivingEntity living ? healthColor(living) : typeColor(entity);
            case SINGLE -> singleColor.getColor();
        };
    }

    private int typeColor(Entity entity) {
        return switch (EntityKind.of(entity)) {
            // A mannequin can turn up where no player is ever drawn.
            case PLAYER -> players ? playerColor.getColor() : otherColor.getColor();
            case ITEM -> itemColor.getColor();
            case HOSTILE -> hostileColor.getColor();
            case NEUTRAL -> neutralColor.getColor();
            case PASSIVE -> passiveColor.getColor();
            case WATER -> waterColor.getColor();
            case AMBIENT -> ambientColor.getColor();
            case OTHER -> otherColor.getColor();
        };
    }

    private int distanceColor(Entity entity) {
        Player self = OfflineClient.MC.player;
        double away = self == null ? farDistance.getValue() : self.distanceTo(entity);
        float share = (float) Math.clamp(away / farDistance.getValue(), 0, 1);
        return ColorUtil.lerpHue(nearColor.getColor(), farColor.getColor(), share);
    }

    private static int healthColor(LivingEntity living) {
        if (living.getMaxHealth() <= 0) {
            return kindColor(living);
        }
        return ColorUtil.redToGreen(EntityUtil.healthShare(living));
    }

    public static int kindColor(Entity entity) {
        return switch (EntityKind.of(entity)) {
            case PLAYER -> EntityUtil.isFriend(entity) ? friendColor()
                : EntityUtil.isListedEnemy(entity) ? enemyColor() : EntityUtil.distanceColor(entity);
            case ITEM -> ITEM.color();
            case HOSTILE -> HOSTILE.color();
            case NEUTRAL -> NEUTRAL.color();
            case PASSIVE -> PASSIVE.color();
            case WATER -> WATER.color();
            case AMBIENT -> AMBIENT.color();
            case OTHER -> OTHER.color();
        };
    }
}
