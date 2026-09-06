package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.EnumSetting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;

// How a module turns towards whatever it is acting on.
public enum FaceMode {
    OFF, SERVER, CLIENT, SPAM;

    public static EnumSetting<FaceMode> setting(FaceMode defaultValue) {
        return new EnumSetting<>("Face target", "Whether to turn towards what you act on.",
            defaultValue)
            .describe(OFF, "Never turn. The fastest and the most obvious.")
            .describe(SERVER, "Turn only in the packets the server reads.")
            .describe(CLIENT, "Turn your real view the way a player would.")
            .describe(SPAM, "Send an extra look packet before every action.");
    }

    // True once the angle is close enough to act on.
    public boolean face(Vec3 point, RotationPriority priority) {
        return face(RotationManager.yawTo(point), RotationManager.pitchTo(point), priority);
    }

    public boolean face(float yaw, float pitch, RotationPriority priority) {
        if (this == SERVER) {
            RotationManager.request(yaw, pitch, priority);
            return RotationManager.isFacing(yaw, pitch, RotationManager.BLOCK_TOLERANCE);
        }
        return apply(yaw, pitch, priority);
    }

    // For angles where the exact number changes the outcome such as bed direction.
    public boolean faceExact(float yaw, float pitch, RotationPriority priority) {
        if (this == SERVER) {
            RotationManager.requestExact(yaw, pitch, priority);
            return RotationManager.sentIsFacing(yaw, pitch, RotationManager.BLOCK_TOLERANCE);
        }
        return apply(yaw, pitch, priority);
    }

    private boolean apply(float yaw, float pitch, RotationPriority priority) {
        LocalPlayer player = OfflineClient.MC.player;
        if (this == OFF || player == null) {
            return this == OFF;
        }
        if (this == CLIENT) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            return true;
        }
        // The extra packet lands before the action so the server reads it first.
        RotationManager.requestExact(yaw, pitch, priority);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch,
            player.onGround(), player.horizontalCollision));
        return true;
    }
}
