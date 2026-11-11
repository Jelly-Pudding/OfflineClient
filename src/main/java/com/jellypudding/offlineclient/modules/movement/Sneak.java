package com.jellypudding.offlineclient.modules.movement;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.util.HeldKey;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ServerSneak;

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

    // Let go of once as a flight starts. A toggle the player then sets by hand stays as they set it.
    private final HeldKey shift = new HeldKey(options -> options.keyShift);
    private final ServerSneak serverSneak = new ServerSneak();
    // The mode the last tick ran under.
    private Mode applied;

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
    }

    @Override
    protected void onDisable() {
        undo(applied);
        applied = null;
    }

    // A shift flag whilst riding dismounts the player. The server is never told one then.
    @Subscribe
    private void onTick(TickEvent event) {
        if (applied != mode.getValue()) {
            Mode old = applied;
            applied = mode.getValue();
            undo(old);
        }
        if (applied == Mode.PACKET) {
            serverSneak.want(!mc.player.isPassenger());
            return;
        }
        if (!flying()) {
            serverSneak.want(false);
            shift.hold();
            return;
        }
        shift.letGo();
        serverSneak.want(whilstFlying.is(WhilstFlying.TELL_SERVER) && !mc.player.isPassenger());
    }

    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        serverSneak.keep(event);
    }

    private void undo(Mode old) {
        if (old == null) {
            return;
        }
        shift.letGo();
        serverSneak.release();
    }

    private boolean flying() {
        return mc.player.getAbilities().flying || Modules.enabled(Flight.class);
    }
}
