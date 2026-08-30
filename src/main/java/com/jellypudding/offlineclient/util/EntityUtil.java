package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.AgeableWaterCreature;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

public final class EntityUtil {

    // Health points in one heart.
    private static final double HEART = 2;

    // Friends are drawn in blue wherever they appear.
    public static final int FRIEND_COLOR = 0xFF4080FF;
    public static final int ITEM_COLOR = 0xFFFFE040;
    // Anything that hunts the player.
    public static final int HOSTILE_COLOR = 0xFFFF5030;
    public static final int PASSIVE_COLOR = 0xFF60E060;
    public static final int WATER_COLOR = 0xFF40C8FF;
    public static final int AMBIENT_COLOR = 0xFFB080FF;
    public static final int MOB_COLOR = 0xFFFF8020;

    // Blocks at which a player reads as far away. Closer fades towards red.
    private static final float FADE_DISTANCE = 20;

    private EntityUtil() {
    }

    // Health plus the absorption hearts on top of it.
    public static float totalHealth(LivingEntity entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    // The most the bar can hold with the absorption on top.
    public static float totalMaxHealth(LivingEntity entity) {
        return entity.getMaxHealth() + entity.getAbsorptionAmount();
    }

    // Zero hearts turns the check off.
    public static boolean healthAtOrBelow(double hearts) {
        Player player = OfflineClient.MC.player;
        if (hearts <= 0 || player == null) {
            return false;
        }
        return totalHealth(player) <= hearts * HEART;
    }

    // True when an end crystal sits within the distance.
    public static boolean crystalNearby(double distance) {
        Minecraft mc = OfflineClient.MC;
        if (mc.level == null || mc.player == null) {
            return false;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof EndCrystal && mc.player.distanceTo(entity) <= distance) {
                return true;
            }
        }
        return false;
    }

    // The holder on EntityType itself is deprecated. The lookup goes via the registry.
    public static boolean typeIs(Entity entity, TagKey<EntityType<?>> tag) {
        return BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).is(tag);
    }

    // The account name of a player or null.
    public static String nameOf(Player player) {
        return player == null ? null : player.getGameProfile().name();
    }

    public static boolean isFriend(Entity entity) {
        return entity instanceof Player player
            && OfflineClient.INSTANCE.getFriendManager().isFriend(nameOf(player));
    }

    // A living player who is not a friend and not watching from spectator.
    public static boolean isEnemy(Entity entity) {
        return entity instanceof Player player && player.isAlive() && !player.isSpectator()
            && !isFriend(player);
    }

    // The broad families the filters in the render modules work with.
    public enum Kind { PLAYER, HOSTILE, PASSIVE, WATER, AMBIENT, ITEM, OTHER }

    /**
     * Hostile covers anything that hunts the player. Water is fish and squid
     * and dolphins. Ambient is bats. Passive is every other living thing.
     */
    public static Kind kindOf(Entity entity) {
        if (entity instanceof Player) {
            return Kind.PLAYER;
        }
        if (entity instanceof ItemEntity) {
            return Kind.ITEM;
        }
        if (entity instanceof Enemy) {
            return Kind.HOSTILE;
        }
        if (entity instanceof WaterAnimal || entity instanceof AgeableWaterCreature) {
            return Kind.WATER;
        }
        if (entity instanceof AmbientCreature) {
            return Kind.AMBIENT;
        }
        return entity instanceof LivingEntity ? Kind.PASSIVE : Kind.OTHER;
    }

    public static boolean matches(Entity entity, boolean players, boolean mobs, boolean items) {
        if (entity instanceof Player player) {
            return players && player.isAlive() && !player.isSpectator();
        }
        if (entity instanceof ItemEntity) {
            return items;
        }
        if (entity instanceof LivingEntity living) {
            return mobs && living.isAlive();
        }
        return false;
    }

    /**
     * From the eyes to the nearest point of the hitbox. This is how the game
     * measures reach.
     */
    public static double reachDistance(Player from, Entity to) {
        Vec3 eye = from.getEyePosition();
        AABB box = to.getBoundingBox();
        Vec3 closest = new Vec3(
            Math.clamp(eye.x, box.minX, box.maxX),
            Math.clamp(eye.y, box.minY, box.maxY),
            Math.clamp(eye.z, box.minZ, box.maxZ));
        return eye.distanceTo(closest);
    }

    // The distance covered on the last tick. The client keeps no velocity field.
    public static Vec3 velocityOf(Entity entity) {
        return new Vec3(entity.getX() - entity.xOld, entity.getY() - entity.yOld,
            entity.getZ() - entity.zOld);
    }

    // Degrees between where the player looks and the middle of the target.
    public static double lookAngleTo(Entity target) {
        Player player = OfflineClient.MC.player;
        if (player == null) {
            return 180;
        }
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        double length = toTarget.length();
        if (length < 1.0E-4) {
            return 0;
        }
        double cosine = Math.clamp(player.getLookAngle().dot(toTarget) / length, -1, 1);
        return Math.toDegrees(Math.acos(cosine));
    }

    public static AABB lerpedBox(Entity entity, float partialTicks) {
        Vec3 lerped = entity.getPosition(partialTicks);
        return entity.getBoundingBox().move(lerped.subtract(entity.position()));
    }

    /**
     * Friends are blue. Players fade from red when close to green when far.
     * Hostile mobs are red and passive ones green with sea life blue and
     * bats purple. Items are yellow.
     */
    public static int colorOf(Entity entity) {
        if (entity instanceof Player) {
            return isFriend(entity) ? FRIEND_COLOR : distanceColor(entity);
        }
        return switch (kindOf(entity)) {
            case ITEM -> ITEM_COLOR;
            case HOSTILE -> HOSTILE_COLOR;
            case PASSIVE -> PASSIVE_COLOR;
            case WATER -> WATER_COLOR;
            case AMBIENT -> AMBIENT_COLOR;
            case PLAYER, OTHER -> MOB_COLOR;
        };
    }

    // Red within arm's reach through yellow to green at twice the fade distance.
    public static int distanceColor(Entity entity) {
        Player self = OfflineClient.MC.player;
        float f = (self == null ? FADE_DISTANCE : self.distanceTo(entity)) / FADE_DISTANCE;
        int r = (int) (Math.clamp(2 - f, 0, 1) * 255);
        int g = (int) (Math.clamp(f, 0, 1) * 255);
        return 0xFF000000 | r << 16 | g << 8;
    }

    // The closest entity within the range that passes the test. Null when none does.
    public static Entity nearest(double range, Predicate<Entity> test) {
        Minecraft mc = OfflineClient.MC;
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Entity best = null;
        double bestDistance = range;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || !test.test(entity)) {
                continue;
            }
            double distance = mc.player.distanceTo(entity);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    // Friends and spectators never count. Null when nobody is close.
    public static Player nearestEnemy(double range) {
        return (Player) nearest(range, EntityUtil::isEnemy);
    }
}
