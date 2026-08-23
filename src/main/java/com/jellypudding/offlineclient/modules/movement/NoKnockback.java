package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.KnockbackEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

public final class NoKnockback extends Module {

    private final NumberSetting horizontal = new NumberSetting("Horizontal",
        "How much horizontal knockback to take.", 0, 0, 100, 1, "%");
    private final NumberSetting vertical = new NumberSetting("Vertical",
        "How much vertical knockback to take.", 0, 0, 100, 1, "%");
    private final BoolSetting separateExplosions = new BoolSetting("Separate explosions",
        "Give explosions their own pair of sliders.", false);
    private final NumberSetting explosionHorizontal = new NumberSetting("Explosion horizontal",
        "How much sideways push to take from explosions.", 100, 0, 100, 1, "%")
        .visibleWhen(separateExplosions::isOn);
    private final NumberSetting explosionVertical = new NumberSetting("Explosion vertical",
        "How much upward push to take from explosions.", 100, 0, 100, 1, "%")
        .visibleWhen(separateExplosions::isOn);

    public NoKnockback() {
        super("NoKnockback", "Reduces or removes the knockback you take.", Category.MOVEMENT);
        addSettings(horizontal, vertical, separateExplosions, explosionHorizontal,
            explosionVertical);
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

    // Explosions push the player through their own packet instead of the usual knockback path.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!(event.getPacket() instanceof ClientboundExplodePacket packet)
            || packet.playerKnockback().isEmpty()) {
            return;
        }
        double h = (separateExplosions.isOn() ? explosionHorizontal : horizontal).getValue() / 100.0;
        double v = (separateExplosions.isOn() ? explosionVertical : vertical).getValue() / 100.0;
        if (h >= 1 && v >= 1) {
            return;
        }
        Vec3 push = packet.playerKnockback().get();
        // Scaling the packet keeps the particles and the sound.
        event.setPacket(new ClientboundExplodePacket(
            packet.center(), packet.radius(), packet.blockCount(),
            Optional.of(new Vec3(push.x * h, push.y * v, push.z * h)),
            packet.explosionParticle(), packet.explosionSound(), packet.blockParticles()));
    }
}
