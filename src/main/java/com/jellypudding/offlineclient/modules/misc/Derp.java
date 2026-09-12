package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.modules.combat.CrystalAura;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.RotationManager;
import com.jellypudding.offlineclient.util.RotationPriority;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.concurrent.ThreadLocalRandom;

// Sends silly rotations and swings and crouches to the server whilst the local view is left alone.
public final class Derp extends Module {

    public enum Mode { SPIN, SHAKE, HEADBANG, FLAIL, TWERK, RANDOM }

    // Ticks a full crouch and stand takes at the slowest twerk speed. At the
    // fastest it flips every tick which is as quick as the server will show.
    private static final int TWERK_SLOWEST = 11;
    private static final int TWERK_FASTEST = 10;

    // Ticks between arm swings whilst flailing. A swing takes about six to play out.
    private static final int FLAIL_GAP = 3;

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How you look to everyone else.", Mode.SPIN)
        .describe(Mode.SPIN, "Turns your head round and round.")
        .describe(Mode.SHAKE, "Shakes your head from side to side.")
        .describe(Mode.HEADBANG, "Nods your head up and down.")
        .describe(Mode.FLAIL, "Waves both arms about.")
        .describe(Mode.TWERK, "Crouches up and down on the spot.")
        .describe(Mode.RANDOM, "Points your head somewhere new every tick and flails now and then.");
    private final NumberSetting speed = new NumberSetting("Speed",
        "How far the head moves each tick.", 30, 1, 90, 1, " degrees").min(1).max(180)
        .under(mode, Mode.SPIN, Mode.SHAKE, Mode.HEADBANG);
    private final NumberSetting twerkSpeed = new NumberSetting("Twerk speed",
        "How fast you crouch and stand. Ten flips every tick which is as fast as it gets.",
        5, 1, TWERK_FASTEST, 1).min(1).max(TWERK_FASTEST)
        .under(mode, Mode.TWERK);
    private final BoolSetting pauseInCombat = new BoolSetting("Pause in combat",
        "Stops whilst your hands are busy or CrystalAura has a target.", true);

    private float yaw;
    private float pitch;
    private boolean rising = true;
    private boolean left;
    private int flailTimer;
    private int twerkTimer;
    private boolean crouched;

    public Derp() {
        super("Derp", "Makes you look ridiculous to everyone else.", Category.MISC);
        addSettings(mode, speed, twerkSpeed, pauseInCombat);
        searchTags("derp", "silly", "troll", "spin", "flail");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onDisable() {
        standUp();
    }

    @Override
    protected void onEnable() {
        flailTimer = 0;
        twerkTimer = 0;
        if (inGame()) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
        }
    }

    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        if (pauseInCombat.isOn() && aimingModuleActive()) {
            return;
        }
        if (flailTimer > 0) {
            flailTimer--;
        }
        if (!mode.is(Mode.TWERK)) {
            standUp();
        }

        float step = speed.getFloat();
        switch (mode.getValue()) {
            case SPIN -> {
                yaw += step;
                pitch = 0;
            }
            case SHAKE -> {
                // Alternates either side of where the player really looks.
                left = !left;
                yaw = mc.player.getYRot() + (left ? step : -step);
                pitch = mc.player.getXRot();
            }
            case HEADBANG -> {
                yaw = mc.player.getYRot();
                pitch += rising ? step : -step;
                if (pitch >= 90) {
                    rising = false;
                } else if (pitch <= -90) {
                    rising = true;
                }
            }
            case FLAIL -> {
                flail();
                return;
            }
            case TWERK -> {
                twerk();
                return;
            }
            case RANDOM -> {
                yaw = ThreadLocalRandom.current().nextFloat(-180f, 180f);
                pitch = ThreadLocalRandom.current().nextFloat(-90f, 90f);
                if (ThreadLocalRandom.current().nextInt(4) == 0) {
                    flail();
                }
            }
        }
        yaw = Mth.wrapDegrees(yaw);
        pitch = Math.clamp(pitch, -90f, 90f);

        RotationManager.requestExact(yaw, pitch, RotationPriority.IDLE);
    }

    // Only the server is told about the crouch. Your own view and speed stay as they are.
    private void twerk() {
        if (twerkTimer > 0) {
            twerkTimer--;
            return;
        }
        twerkTimer = TWERK_SLOWEST - twerkSpeed.getInt();
        crouched = !crouched;
        InputUtil.sendShift(crouched);
    }

    // Puts the server back on the real key state.
    private void standUp() {
        if (crouched && mc.player != null) {
            InputUtil.sendShift(mc.player.getLastSentInput().shift());
        }
        crouched = false;
    }

    // Swings the arms in turn. Only the packet goes out.
    // The server plays the animation but never echoes it back. The view stays still.
    private void flail() {
        if (flailTimer > 0) {
            return;
        }
        flailTimer = FLAIL_GAP;
        left = !left;
        mc.player.connection.send(new ServerboundSwingPacket(
            left ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND));
    }

    private boolean aimingModuleActive() {
        if (mc.player.swinging && !mode.is(Mode.FLAIL) && !mode.is(Mode.RANDOM)
            || mc.options.keyAttack.isDown() || mc.player.isUsingItem()) {
            return true;
        }
        CrystalAura crystalAura = Modules.get(CrystalAura.class);
        return crystalAura != null && crystalAura.isEnabled() && crystalAura.hasTarget();
    }
}
