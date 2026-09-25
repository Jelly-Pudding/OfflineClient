package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.event.events.PreMotionEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.util.HeldPacket;
import com.jellypudding.offlineclient.util.MoveGate;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// The server hands a fired arrow the speed it thinks you moved at. A step back
// and a step home over two ticks make that speed a lunge. Patched on some servers.
public final class ArrowDamage extends Module {

    // The furthest the server lets one packet move you. Strength ten is the full jump.
    private static final double FULL_STEP = Math.sqrt(500);

    private static final int WARM_UP_PACKETS = 4;

    private static final int GIVE_UP_TICKS = 10;

    private enum Stage { IDLE, BACK, HOME, READY }

    private final NumberSetting strength = new NumberSetting("Strength",
        "How hard the arrow is pushed. Ten is the most a server accepts.", 10, 0.1, 10, 0.1)
        .min(0.1).max(10);
    private final BoolSetting tridents = new BoolSetting("Tridents",
        "Also boosts a thrown trident. One that flies too far is easily lost.", false);

    private final HeldPacket<ServerboundPlayerActionPacket> held = new HeldPacket<>();
    private Stage stage = Stage.IDLE;
    private int waited;

    public ArrowDamage() {
        super("ArrowDamage", "Makes your arrows fly faster and hit harder.", Category.COMBAT);
        addSettings(strength, tridents);
        searchTags("arrow dmg", "bow damage", "arrow speed");
    }

    // A shot caught mid step still goes out.
    @Override
    protected void onDisable() {
        fire();
    }

    private boolean boosts(ItemStack stack) {
        return stack.is(Items.BOW) || (tridents.isOn() && stack.is(Items.TRIDENT));
    }

    // The release packet is what fires the arrow. The step back and the step
    // home each need a tick of their own and the release waits for both.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (held.releasing() || mc.player == null
            || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)
            || packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
            || !boosts(mc.player.getMainHandItem())) {
            return;
        }
        if (stage != Stage.IDLE) {
            return;
        }
        held.hold(event, packet);
        stage = Stage.BACK;
        waited = 0;
        mc.player.connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_SPRINTING));
    }

    // The step back and the step home each ride the one position packet a tick
    // allows. PreMotion runs just before that packet leaves.
    @Subscribe
    private void onPreMotion(PreMotionEvent event) {
        if (stage == Stage.IDLE) {
            return;
        }
        if (!inGame() || ++waited > GIVE_UP_TICKS) {
            stage = Stage.READY;
            return;
        }
        MoveGate.fillers(WARM_UP_PACKETS);
        if (stage == Stage.BACK) {
            Vec3 push = mc.player.getViewVector(1).scale(strength.getValue() / 10 * FULL_STEP);
            if (stepBack(push)) {
                stage = Stage.HOME;
                waited = 0;
            }
            return;
        }
        // The tick's own packet carries the player home from here.
        stage = Stage.READY;
    }

    // The move home next tick is the speed the server hands the arrow.
    private boolean stepBack(Vec3 push) {
        return MoveGate.send(mc.player.getX() - push.x, mc.player.getY(),
            mc.player.getZ() - push.z, true);
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (stage == Stage.READY) {
            fire();
        }
    }

    private void fire() {
        stage = Stage.IDLE;
        waited = 0;
        held.release();
    }
}
