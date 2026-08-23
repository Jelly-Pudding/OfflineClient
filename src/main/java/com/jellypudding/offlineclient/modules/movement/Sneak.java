package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.InputUtil;
import com.jellypudding.offlineclient.util.Modules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

import java.lang.ref.WeakReference;

// Legit mode holds the sneak key. Packet mode only tells the server.
public final class Sneak extends Module {

    public enum Mode { LEGIT, PACKET }

    private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
        "Legit really crouches. Packet keeps your full speed.",
        Mode.LEGIT);
    private final BoolSetting skipWhileFlying = new BoolSetting("Skip whilst flying",
        "Do not hold sneak whilst flying.", true)
        .visibleWhen(() -> mode.is(Mode.LEGIT));

    // Read from the packet thread.
    private volatile Mode applied;
    private volatile boolean forcing;
    // The player the flag was sent for. Weak to avoid pinning a dead world.
    private WeakReference<LocalPlayer> told = new WeakReference<>(null);

    public Sneak() {
        super("Sneak", "Keeps you sneaking.", Category.MOVEMENT);
        addSettings(mode, skipWhileFlying);
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
            packetTick();
            return;
        }
        if (skipWhileFlying.isOn() && flying()) {
            setShift(InputUtil.physicallyHeld(mc.options.keyShift));
            return;
        }
        setShift(true);
    }

    // A shift flag whilst riding makes the server dismount the player.
    private void packetTick() {
        LocalPlayer player = mc.player;
        boolean want = !player.isPassenger();
        // A respawn or a dimension change wipes what the server was told.
        if (want != forcing || (want && player != told.get())) {
            forcing = want;
            told = new WeakReference<>(want ? player : null);
            tellServer(want);
        }
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (applied != Mode.PACKET || !forcing
            || !(event.getPacket() instanceof ServerboundPlayerInputPacket packet)) {
            return;
        }
        if (!packet.input().shift()) {
            event.setPacket(new ServerboundPlayerInputPacket(withShift(packet.input(), true)));
        }
    }

    private void undo(Mode old) {
        if (old == null || !inGame()) {
            return;
        }
        if (old == Mode.LEGIT) {
            setShift(InputUtil.physicallyHeld(mc.options.keyShift));
        } else if (forcing) {
            forcing = false;
            tellServer(false);
        }
    }

    // The client's own record still holds the real key state.
    private void tellServer(boolean shift) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        Input last = player.getLastSentInput();
        if (last.shift() == shift) {
            return;
        }
        player.connection.send(new ServerboundPlayerInputPacket(withShift(last, shift)));
    }

    private static Input withShift(Input input, boolean shift) {
        return new Input(input.forward(), input.backward(), input.left(), input.right(),
            input.jump(), shift, input.sprint());
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
