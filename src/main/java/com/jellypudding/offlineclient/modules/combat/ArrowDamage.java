package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

// A burst of position packets just before the arrow leaves. The server hands
// the arrow the speed it thinks you moved at. Patched on some servers.
public final class ArrowDamage extends Module {

    // The furthest the server lets one packet move you. Strength ten is the full jump.
    private static final double FULL_STEP = Math.sqrt(500);
    private static final int WARM_UP_PACKETS = 4;

    private final NumberSetting strength = new NumberSetting("Strength",
        "How hard the arrow is pushed. Ten is the most a server accepts.", 10, 0.1, 10, 0.1)
        .min(0.1).max(10);
    private final BoolSetting tridents = new BoolSetting("Tridents",
        "Also boosts a thrown trident. One that flies too far is easily lost.", false);

    public ArrowDamage() {
        super("ArrowDamage", "Makes your arrows fly faster and hit harder.", Category.COMBAT);
        addSettings(strength, tridents);
        searchTags("arrow dmg", "bow damage", "arrow speed");
    }

    private boolean boosts(ItemStack stack) {
        return stack.is(Items.BOW) || (tridents.isOn() && stack.is(Items.TRIDENT));
    }

    // The release packet is what fires the arrow. The burst goes out in front of it.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (mc.player == null
            || !(event.getPacket() instanceof ServerboundPlayerActionPacket packet)
            || packet.getAction() != ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM
            || !boosts(mc.player.getMainHandItem())) {
            return;
        }
        ClientPacketListener connection = mc.player.connection;
        connection.send(new ServerboundPlayerCommandPacket(mc.player,
            ServerboundPlayerCommandPacket.Action.START_SPRINTING));
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        Vec3 push = mc.player.getViewVector(1).scale(strength.getValue() / 10 * FULL_STEP);
        for (int i = 0; i < WARM_UP_PACKETS; i++) {
            sendPosition(x, y, z, true);
        }
        sendPosition(x - push.x, y, z - push.z, true);
        sendPosition(x, y, z, false);
    }

    private void sendPosition(double x, double y, double z, boolean onGround) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.Pos(x, y, z, onGround,
            mc.player.horizontalCollision));
    }
}
