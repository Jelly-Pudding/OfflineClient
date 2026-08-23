package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.PacketUtil;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.item.Items;

// Spoofing starts only once the drop passes the threshold. Short hops keep sending the truth.
public final class NoFall extends Module {

    // A jump between dimensions moves the player further than any fall.
    private static final double TELEPORT_DROP = 64;

    private final NumberSetting minFall = new NumberSetting("Min fall",
        "Start spoofing once you have dropped this many blocks.", 3, 1, 20, 0.5, " blocks").min(0.5);
    private final BoolSetting elytra = new BoolSetting("Elytra",
        "Also protect whilst gliding. Some servers may rubberband.", true);
    private final NumberSetting minElytraFall = new NumberSetting("Min elytra fall",
        "Separate threshold whilst gliding.", 2, 1, 20, 0.5, " blocks").min(0.5)
        .visibleWhen(elytra::isOn);
    private final BoolSetting pauseOnMace = new BoolSetting("Pause on mace",
        "Stop spoofing whilst holding a mace. Smash damage needs a real fall.", true);

    private double descent;

    private double lastY;

    /**
     * A single honest packet in the middle of a fall lets the server bank the
     * drop and hurt the player. Read from the packet thread.
     */
    private volatile boolean spoofing;

    private boolean tracking;

    public NoFall() {
        super("NoFall", "Stops fall damage by telling the server you are on the ground.", Category.MOVEMENT);
        addSettings(minFall, elytra, minElytraFall, pauseOnMace);
        searchTags("fall damage");
    }

    @Override
    public String getSuffix() {
        return spoofing ? "active" : null;
    }

    @Override
    protected void onEnable() {
        reset();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        descent = 0;
        spoofing = false;
        tracking = false;
    }

    // Runs once per tick just before the movement packet is built.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame()) {
            reset();
            return;
        }
        double y = mc.player.getY();
        if (!tracking) {
            lastY = y;
            tracking = true;
        }
        double drop = lastY - y;
        lastY = y;

        if (mc.player.onGround()) {
            descent = 0;
            spoofing = false;
            return;
        }
        if (drop > TELEPORT_DROP || drop < -TELEPORT_DROP) {
            // A teleport is not a fall.
            descent = 0;
            return;
        }
        if (drop > 0) {
            descent += drop;
        } else if (drop < 0 || mc.player.getDeltaMovement().y > 0) {
            // Any climb wipes the fall the server was tracking.
            descent = 0;
        }
        if (pauseOnMace.isOn() && holdingMace()) {
            spoofing = false;
            return;
        }
        double threshold = mc.player.isFallFlying() ? minElytraFall.getValue() : minFall.getValue();
        if (descent >= threshold) {
            spoofing = true;
        }
    }

    private boolean holdingMace() {
        return mc.player.getMainHandItem().is(Items.MACE)
            || mc.player.getOffhandItem().is(Items.MACE);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!spoofing || !(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
            || packet.isOnGround()) {
            return;
        }
        // The packet thread can lose the player mid handler.
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // Creative mode has no fall damage.
        if (player.getAbilities().invulnerable) {
            return;
        }
        if (player.isFallFlying() && !elytra.isOn()) {
            return;
        }
        event.setPacket(PacketUtil.withOnGround(packet, player, true));
    }
}
