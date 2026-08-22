package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared helpers for modules that look at entities around the player.
 */
public final class EntityUtil {

    private EntityUtil() {
    }

    /** Checks an entity against the usual player and mob and item filters. */
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
     * Distance from the player's eyes to the nearest point of the entity's
     * hitbox. This is how the game measures reach.
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

    /** The entity hitbox at its interpolated render position. */
    public static AABB lerpedBox(Entity entity, float partialTicks) {
        Vec3 lerped = entity.getPosition(partialTicks);
        return entity.getBoundingBox().move(lerped.subtract(entity.position()));
    }

    /**
     * ESP color for an entity. Friends are blue. Players fade from red when
     * close to green when far. Mobs are orange and items are yellow.
     */
    public static int colorOf(Entity entity) {
        if (entity instanceof Player player) {
            if (OfflineClient.INSTANCE.getFriendManager().isFriend(player.getGameProfile().name())) {
                return 0xFF4080FF;
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

    /**
     * The closest other player within range that combat modules may act
     * on. Friends and spectators never count. Null when nobody is close.
     */
    public static Player nearestEnemy(double range) {
        Minecraft mc = OfflineClient.MC;
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
