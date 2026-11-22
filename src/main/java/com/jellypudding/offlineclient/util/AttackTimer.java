package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.Setting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Random;

// The spread keeps the gap between hits from landing on the same number every time.
public final class AttackTimer {

    // The game reads the charge of a hit half a tick on.
    private static final float CHARGE_READ = 0.5f;

    private static final int NEVER = Integer.MIN_VALUE / 2;
    private static int askedAt = NEVER;
    private static int restartedAt = NEVER;

    private final NumberSetting delay = new NumberSetting("Hit delay",
        "Ticks to wait between hits.", 0, 0, 20, 1, " ticks");
    private final NumberSetting randomise = new NumberSetting("Randomise",
        "Extra random ticks added to the wait.", 2, 0, 10, 1, " ticks");
    private final BoolSetting tpsSync = new BoolSetting("TPS sync",
        "Waits longer whilst the server lags to avoid wasted hits.", true);
    private final NumberSetting missChance = new NumberSetting("Miss chance",
        "Share of swings that hit thin air on purpose. Each one spends the cooldown like a real miss.",
        0, 0, 50, 1, "%").max(100);

    private final Random random = new Random();

    // Wall clock milliseconds.
    private long readyAt;

    public Setting<?>[] settings() {
        return new Setting<?>[] {delay, randomise, tpsSync, missChance};
    }

    // The server begins its cooldown again on every punch of mining and after every block placed or
    // thing used. The game's own count misses all of those.
    public static boolean charged() {
        LocalPlayer player = OfflineClient.MC.player;
        askedAt = player.tickCount;
        float sinceRestart = (ticksSince(restartedAt) + CHARGE_READ) / player.getCurrentItemAttackStrengthDelay();
        return Math.min(player.getAttackStrengthScale(CHARGE_READ), sinceRestart) >= 1;
    }

    // Called when something the game's own count does not hear of makes the server begin its
    // attack cooldown again.
    public static void restarted() {
        LocalPlayer player = OfflineClient.MC.player;
        if (player != null) {
            restartedAt = player.tickCount;
        }
    }

    // False whilst a module waits for the cooldown to fill for a hit. The punch would start the
    // cooldown again and the hit would land weak.
    public static boolean letsMiningPunch() {
        if (ticksSince(askedAt) <= 1) {
            return false;
        }
        restarted();
        return true;
    }

    // A count from before the player was born again reads as long ago.
    private static int ticksSince(int tick) {
        LocalPlayer player = OfflineClient.MC.player;
        int since = player == null ? -1 : player.tickCount - tick;
        return since < 0 ? Integer.MAX_VALUE / 2 : since;
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

    public void strike(Entity target, SwingMode swing) {
        strike(List.of(target), swing);
    }

    public void strike(List<Entity> targets, SwingMode swing) {
        if (misses(swing)) {
            return;
        }
        Minecraft mc = OfflineClient.MC;
        for (Entity target : targets) {
            mc.gameMode.attack(mc.player, target);
        }
        swing.hit();
        spent();
    }

    public void clear() {
        readyAt = 0;
    }

    // True swings at thin air the way the game does for a click on nothing and starts the next
    // wait.
    private boolean misses(SwingMode swing) {
        if (random.nextDouble() * 100 >= missChance.getValue()) {
            return false;
        }
        OfflineClient.MC.player.resetAttackStrengthTicker();
        swing.hit();
        spent();
        return true;
    }

    private void spent() {
        int spread = randomise.getInt();
        double ticks = delay.getInt() + (spread > 0 ? random.nextInt(spread + 1) : 0);
        if (tpsSync.isOn()) {
            ticks *= SharedConstants.TICKS_PER_SECOND / Math.max(1, TickRate.INSTANCE.tps());
        }
        readyAt = System.currentTimeMillis() + Math.round(ticks * SharedConstants.MILLIS_PER_TICK);
    }
}
