package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class EntityUtil {

    // Health points in one heart.
    private static final double HEART = 2;

    // Friends are drawn in blue wherever they appear.
    public static final int FRIEND_COLOR = 0xFF4080FF;

    private EntityUtil() {
    }

    // Zero hearts turns the check off.
    public static boolean healthAtOrBelow(double hearts) {
        Player player = OfflineClient.MC.player;
        if (hearts <= 0 || player == null) {
            return false;
        }
        return player.getHealth() + player.getAbsorptionAmount() <= hearts * HEART;
    }

    // The holder on EntityType itself is deprecated. The lookup goes via the registry.
    public static boolean typeIs(Entity entity, TagKey<EntityType<?>> tag) {
        return BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(entity.getType()).is(tag);
    }

    // The account name of a player or null.
    public static String nameOf(Player player) {
        return player == null ? null : player.getGameProfile().name();
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
     * Friends are blue. Players fade from red when close to green when far
     * and mobs are orange with items yellow.
     */
    public static int colorOf(Entity entity) {
        if (entity instanceof Player player) {
            if (OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
                return FRIEND_COLOR;
            }
            float distance = OfflineClient.MC.player == null ? 20
                : OfflineClient.MC.player.distanceTo(player);
            float f = distance / 20f;
            int r = (int) (Math.clamp(2 - f, 0, 1) * 255);
            int g = (int) (Math.clamp(f, 0, 1) * 255);
            return 0xFF000000 | r << 16 | g << 8;
        }
        if (entity instanceof ItemEntity) {
            return 0xFFFFE040;
        }
        return 0xFFFF8020;
    }

    // Friends and spectators never count. Null when nobody is close.
    public static Player nearestEnemy(double range) {
        Minecraft mc = OfflineClient.MC;
        if (mc.player == null || mc.level == null) {
            return null;
        }
        Player best = null;
        double bestDistance = range;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
                continue;
            }
            double distance = mc.player.distanceTo(player);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }
}
