package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Random;

// The hits modules swing and the wait between one and the next.
// The spread keeps the gap from landing on the same number every time.
public final class AttackTimer {

    // The game reads the charge of a hit half a tick on.
    private static final float CHARGE_READ = 0.5f;

    private final NumberSetting delay = new NumberSetting("Hit delay",
        "Ticks to wait between hits.", 0, 0, 20, 1, " ticks");
    // Evenly spaced hits are the easiest pattern for a server to spot.
    private final NumberSetting randomise = new NumberSetting("Randomise",
        "Extra random ticks added to the wait.", 2, 0, 10, 1, " ticks");
    private final BoolSetting tpsSync = new BoolSetting("TPS sync",
        "Waits longer whilst the server lags to avoid wasted hits.", true);
    // A player who never misses stands out as much as one who never waits.
    private final NumberSetting missChance = new NumberSetting("Miss chance",
        "Share of swings that hit thin air on purpose. Each one spends the cooldown like a real miss.",
        0, 0, 50, 1, "%").max(100);

    private final Random random = new Random();

    // Wall clock milliseconds.
    private long readyAt;

    // The settings in the order a module should show them.
    public Setting<?>[] settings() {
        return new Setting<?>[] {delay, randomise, tpsSync, missChance};
    }

    // True once the attack cooldown has filled. A hit before then lands weaker.
    public static boolean charged() {
        return OfflineClient.MC.player.getAttackStrengthScale(CHARGE_READ) >= 1;
    }

    // A bare hit with no swing and no miss. The server restarts its attack cooldown for every
    // hit it takes and the client's own restarts the same way.
    public static void packetHit(Entity target) {
        LocalPlayer player = OfflineClient.MC.player;
        player.connection.send(new ServerboundAttackPacket(target.getId()));
        player.resetOnlyAttackStrengthTicker();
    }

    public boolean ready() {
        return System.currentTimeMillis() >= readyAt;
    }

    // One swing at the target that starts the next wait.
    public void strike(Entity target, SwingMode swing) {
        strike(List.of(target), swing);
    }

    // One swing that lands on every target and starts the next wait. The miss chance may
    // swing at thin air in its place.
    public void strike(List<Entity> targets, SwingMode swing) {
        if (misses(swing)) {
            return;
        }
        Minecraft mc = OfflineClient.MC;
        for (Entity target : targets) {
            mc.gameMode.attack(mc.player, target);
        }
        swing.swing(InteractionHand.MAIN_HAND);
        spent();
    }

    // Lets the next hit through straight away.
    public void clear() {
        readyAt = 0;
    }

    // Asked just before a hit goes out. True swings at thin air the way the game does for a
    // click on nothing and starts the next wait. The hit is then left out. The arm only moves
    // on your screen where the module's own hits move it there.
    private boolean misses(SwingMode swing) {
        if (random.nextDouble() * 100 >= missChance.getValue()) {
            return false;
        }
        LocalPlayer player = OfflineClient.MC.player;
        player.resetAttackStrengthTicker();
        if (swing == SwingMode.BOTH || swing == SwingMode.CLIENT) {
            SwingMode.swingArm(InteractionHand.MAIN_HAND);
        }
        player.connection.send(ServerboundPunchPacket.INSTANCE);
        spent();
        return true;
    }

    // Starts the next wait. Called once a hit has landed.
    private void spent() {
        int spread = randomise.getInt();
        double ticks = delay.getInt() + (spread > 0 ? random.nextInt(spread + 1) : 0);
        if (tpsSync.isOn()) {
            ticks *= SharedConstants.TICKS_PER_SECOND / Math.max(1, TickRate.INSTANCE.tps());
        }
        readyAt = System.currentTimeMillis() + Math.round(ticks * SharedConstants.MILLIS_PER_TICK);
    }
}
