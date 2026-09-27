package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PostMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.DamageUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.LivingEntity;

// The server adds knockback to a full strength hit from anyone it believes is sprinting
// and then stops that sprint. Every hit lands soft until word of the stop reaches the
// client and it starts again. A sprint start sent just ahead of each hit earns the
// knockback whether you run or not.
public final class Knockback extends Module {

    // The server calls a hit full strength past this share of the attack cooldown.
    private static final float FULL_STRENGTH = 0.9f;

    private final BoolSetting keepCrits = new BoolSetting("Keep crits",
        "Leaves a hit alone whilst you fall. It lands as a critical hit instead.", true);

    // True only whilst the start for a hit goes out.
    private boolean sendingStart;
    private boolean resyncDue;

    public Knockback() {
        super("Knockback", "Renews your sprint before each hit to knock the target back as far as a sprinting hit.",
            Category.COMBAT);
        addSettings(keepCrits);
        searchTags("super knockback", "more knockback", "w tap", "sprint reset", "kb");
    }

    @Override
    protected void onDisable() {
        resync();
    }

    // Read by AntiHunger. The start it lets through ends with the hit it rides on.
    public boolean sendingStart() {
        return sendingStart;
    }

    // Runs after Sprint and Criticals have had their say about the hit.
    @Subscribe(priority = -100)
    private void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !(event.getPacket() instanceof ServerboundAttackPacket attack)
            || !inGame() || mc.player.isSpectator()) {
            return;
        }
        Criticals criticals = Modules.active(Criticals.class);
        if (criticals != null && criticals.releasing()) {
            return;
        }
        if (!(mc.level.getEntity(attack.entityId()) instanceof LivingEntity)
            || mc.player.getAttackStrengthScale(0.5f) <= FULL_STRENGTH) {
            return;
        }
        if (keepCrits.isOn() && DamageUtil.critFall(mc.player)) {
            return;
        }
        sendingStart = true;
        send(ServerboundPlayerCommandPacket.Action.START_SPRINTING);
        sendingStart = false;
        resyncDue = true;
    }

    // The attack packet has gone out by this point. The server hears the sprint the
    // client really holds.
    @Subscribe
    private void onPostMotion(PostMotionEvent event) {
        resync();
    }

    private void resync() {
        if (!resyncDue) {
            return;
        }
        resyncDue = false;
        if (inGame()) {
            send(mc.player.isSprinting() ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
                : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING);
        }
    }

    private void send(ServerboundPlayerCommandPacket.Action action) {
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player, action));
    }
}
