package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;

// Hits end crystals for the modules that set off one that is already standing. Each crystal
// gets one hit. A second packet before it pops is wasted.
public final class CrystalGuard {

    private static final Minecraft MC = OfflineClient.MC;

    // Ticks a hit crystal is left alone before it is hit again.
    private static final int HIT_MEMORY = 10;

    private final Cooldowns<Integer> hit = new Cooldowns<>();

    // Once a tick before anything is hit.
    public void tick() {
        hit.tick();
    }

    public void forget() {
        hit.clear();
    }

    // False when the crystal is gone or was hit a moment ago.
    public boolean hit(Entity crystal, boolean rotate, SwingMode swing) {
        if (crystal.isRemoved() || hit.contains(crystal.getId())) {
            return false;
        }
        hit.put(crystal.getId(), HIT_MEMORY);
        if (rotate) {
            BlockUtil.faceVector(crystal.getBoundingBox().getCenter(), RotationPriority.ATTACK);
        }
        AttackTimer.packetHit(crystal);
        swing.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    // Hits every crystal that touches the box unless its blast could kill you.
    public void clear(AABB area, boolean rotate, SwingMode swing) {
        float health = EntityUtil.totalHealth(MC.player);
        for (Entity crystal : MC.level.getEntities((Entity) null, area, entity -> entity instanceof EndCrystal)) {
            if (ExplosionUtil.crystalDamage(MC.player, crystal.position()) < health) {
                hit(crystal, rotate, swing);
            }
        }
    }
}
