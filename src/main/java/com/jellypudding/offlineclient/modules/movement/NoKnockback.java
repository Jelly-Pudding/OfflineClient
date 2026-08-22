package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;

public final class NoKnockback extends Module {

    private final NumberSetting horizontal = new NumberSetting("Horizontal",
        "How much horizontal knockback to take.", 0, 0, 100, 1, "%");
    private final NumberSetting vertical = new NumberSetting("Vertical",
        "How much vertical knockback to take.", 0, 0, 100, 1, "%");

    public NoKnockback() {
        super("NoKnockback", "Reduces or removes the knockback you take.", Category.MOVEMENT);
        addSettings(horizontal, vertical);
        searchTags("velocity", "knockback", "antikb");
    }

    @Override
    public String getSuffix() {
        return horizontal.getInt() + "/" + vertical.getInt();
    }

    @Subscribe
    private void onKnockback(KnockbackEvent event) {
        if (!inGame()) {
            return;
        }
        Vec3 current = mc.player.getDeltaMovement();
        double h = horizontal.getValue() / 100.0;
        double v = vertical.getValue() / 100.0;
        event.setX(current.x + (event.getX() - current.x) * h);
        event.setY(current.y + (event.getY() - current.y) * v);
        event.setZ(current.z + (event.getZ() - current.z) * h);
    }

    /**
     * Explosions push the player through their own packet instead of the
     * usual knockback path. Swallow it and apply the scaled push ourselves.
     */
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundExplodePacket packet)
            || packet.playerKnockback().isEmpty()) {
            return;
        }
        double h = horizontal.getValue() / 100.0;
        double v = vertical.getValue() / 100.0;
        if (h >= 1 && v >= 1) {
            return;
        }
        event.cancel();
        Vec3 knockback = packet.playerKnockback().get();
        Vec3 scaled = new Vec3(knockback.x * h, knockback.y * v, knockback.z * h);
        // Packets arrive off the game thread.
        mc.schedule(() -> {
            if (mc.player != null && scaled.lengthSqr() > 0) {
                mc.player.addDeltaMovement(scaled);
            }
        });
    }
}
