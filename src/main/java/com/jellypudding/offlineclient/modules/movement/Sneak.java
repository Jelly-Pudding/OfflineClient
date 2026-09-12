package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import java.lang.ref.WeakReference;

// Legit mode holds the sneak key. Packet mode only tells the server.
// Whilst flying the sneak key is the way down. Legit mode then tells the server instead.
public final class Sneak extends Module {

    public enum Mode { LEGIT, PACKET }

    public enum WhilstFlying { STOP, TELL_SERVER }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "How the sneak is done.", Mode.LEGIT)
        .describe(Mode.LEGIT, "Really crouches. You move at sneaking speed.")
        .describe(Mode.PACKET, "Only tells the server. You keep your full speed.");
    private final EnumSetting<WhilstFlying> whilstFlying = new EnumSetting<>("Whilst flying",
        "What happens whilst you fly. Holding the key down would only make you sink.", WhilstFlying.STOP)
        .describe(WhilstFlying.STOP, "Stops sneaking until you land.")
        .describe(WhilstFlying.TELL_SERVER, "Tells the server you sneak and leaves the fly keys alone.")
        .under(mode, Mode.LEGIT);

    // Read from the packet thread.
    private volatile Mode applied;
    private volatile boolean forcing;
    // The player the flag was sent for. Weak to avoid pinning a dead world.
    private WeakReference<LocalPlayer> told = new WeakReference<>(null);

    public Sneak() {
        super("Sneak", "Keeps you sneaking.", Category.MOVEMENT);
        addSettings(mode, whilstFlying);
        searchTags("crouch", "shift");
    }

    @Override
    public String getSuffix() {
        return mode.getValueString();
    }

    @Override
    protected void onEnable() {
        applied = null;
        forcing = false;
        told = new WeakReference<>(null);
    }

    @Override
    protected void onDisable() {
        undo(applied);
        applied = null;
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame()) {
            return;
        }
        if (applied != mode.getValue()) {
            Mode old = applied;
            applied = mode.getValue();
            undo(old);
        }
        if (applied == Mode.PACKET) {
            tellServer(!mc.player.isPassenger());
            return;
        }
        if (!flying()) {
            tellServer(false);
            setShift(true);
            return;
        }
        setShift(InputUtil.physicallyHeld(mc.options.keyShift));
        tellServer(whilstFlying.is(WhilstFlying.TELL_SERVER) && !mc.player.isPassenger());
    }

    // Keeps the server told the flag the mode wants.
    // A shift flag whilst riding dismounts the player. The caller leaves it off then.
    private void tellServer(boolean want) {
        LocalPlayer player = mc.player;
        // A respawn or a dimension change wipes what the server was told.
        if (want == forcing && (!want || player == told.get())) {
            return;
        }
        forcing = want;
        told = new WeakReference<>(want ? player : null);
        InputUtil.sendShift(want);
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!forcing || !(event.getPacket() instanceof ServerboundPlayerInputPacket packet)) {
            return;
        }
        if (!packet.input().shift()) {
            event.setPacket(new ServerboundPlayerInputPacket(InputUtil.withShift(packet.input(), true)));
        }
    }

    private void undo(Mode old) {
        if (old == null || !inGame()) {
            return;
        }
        if (old == Mode.LEGIT) {
            setShift(InputUtil.physicallyHeld(mc.options.keyShift));
        }
        tellServer(false);
    }

    // With toggle sneak turned on the game flips the key on every press.
    private void setShift(boolean down) {
        if (mc.options.keyShift.isDown() != down) {
            mc.options.keyShift.setDown(down);
        }
    }

    private boolean flying() {
        return mc.player.getAbilities().flying || Modules.enabled(Flight.class);
    }
}
