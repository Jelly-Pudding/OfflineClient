package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Lower scores win.
public enum TargetPriority {
    NEAREST, FURTHEST, LOW_HEALTH, HIGH_HEALTH, CLOSEST_ANGLE, ANGLE_AND_DISTANCE;

    // More than any health a living thing can have.
    private static final double NO_HEALTH = 1.0E9;

    public double score(Entity entity) {
        return switch (this) {
            case NEAREST -> OfflineClient.MC.player.distanceToSqr(entity);
            case FURTHEST -> -OfflineClient.MC.player.distanceToSqr(entity);
            case LOW_HEALTH -> entity instanceof LivingEntity living
                ? EntityUtil.totalHealth(living) : lifeless(entity);
            case HIGH_HEALTH -> entity instanceof LivingEntity living
                ? -EntityUtil.totalHealth(living) : lifeless(entity);
            case CLOSEST_ANGLE -> EntityUtil.lookAngleTo(entity);
            case ANGLE_AND_DISTANCE -> EntityUtil.lookAngleTo(entity)
                * OfflineClient.MC.player.distanceTo(entity);
        };
    }

    // A crystal or a boat has no health to compare. Under either health order it comes after
    // every living target and the nearest of its like goes first.
    private static double lifeless(Entity entity) {
        return NO_HEALTH + OfflineClient.MC.player.distanceToSqr(entity);
    }

    // A setting with a line for each choice. The verb reads as "Attacks".
    public static EnumSetting<TargetPriority> setting(String verb, TargetPriority defaultValue) {
        return new EnumSetting<>("Priority", "Which target to pick when several are in range.", defaultValue)
            .describe(NEAREST, verb + " the closest target.")
            .describe(FURTHEST, verb + " the furthest target still in range.")
            .describe(LOW_HEALTH, verb + " whoever has the least health left.")
            .describe(HIGH_HEALTH, verb + " whoever has the most health left.")
            .describe(CLOSEST_ANGLE, verb + " whoever is nearest your crosshair.")
            .describe(ANGLE_AND_DISTANCE,
                verb + " whoever is nearest your crosshair with the close ones favoured.");
    }
}
