package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Pose;

import java.util.List;

// A sneak only the server is told of whilst the client stands up. Each owner holds its own. Two
// at once agree because each keeps every input packet crouched on its way out whilst it wants to.
public final class ServerSneak {

    // A respawn or a dimension change wipes what the server was told. A fresh body is told again.
    private final LifeWatch body = new LifeWatch();
    // Read on whatever thread sends a packet.
    private volatile boolean wanted;

    // Tells the server whenever the wish changes. The server throws a rider it takes for sneaking
    // off the mount and the wish rests for the ride. A wish that ends hands the server back to
    // the sneak the client itself holds and a sneak key the player keeps down goes on.
    public void want(boolean sneak) {
        LocalPlayer player = OfflineClient.MC.player;
        boolean freshBody = body.changed();
        boolean told = sneak && player != null && !player.isPassenger();
        if (told != wanted || told && freshBody) {
            wanted = told;
            InputUtil.sendShift(told || player != null && player.isShiftKeyDown());
        }
    }

    // The owner hands each packet on its way out to this.
    public void keep(PacketSendEvent event) {
        if (wanted) {
            InputUtil.keepShift(event);
        }
    }

    public void release() {
        want(false);
    }

    // The server sends you your own pose. A sneak only it was told of comes back as a crouch that
    // lowers your view and your box until your own tick stands you up again. Standing or crouching
    // is for your own tick to decide. Whilst you do either the server's word on it is left out.
    public static List<SynchedEntityData.DataValue<?>> keepStance(LocalPlayer player,
                                                                  List<SynchedEntityData.DataValue<?>> sent) {
        if (!isStance(player.getPose())) {
            return sent;
        }
        return sent.stream().filter(value -> !(value.value() instanceof Pose pose && isStance(pose))).toList();
    }

    private static boolean isStance(Pose pose) {
        return pose == Pose.STANDING || pose == Pose.CROUCHING;
    }
}
