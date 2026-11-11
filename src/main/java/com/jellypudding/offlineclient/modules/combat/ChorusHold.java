package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.Render3DEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.render.BoxStyle;
import com.jellypudding.offlineclient.setting.BoolSetting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.consume_effects.TeleportRandomlyConsumeEffect;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

// The server moves you the moment the fruit is eaten and then waits for the client to say
// it has arrived. Until that answer it reads no move you send and takes no block you
// click. The packet that says where it put you is kept here and handed to the game once
// the fruit is out of your hand. A plain server sends the spot again every second under a
// fresh number. A Paper server never does and the newest packet is always answered in the
// end. Each number is answered once at most because a second answer is a kick.
public final class ChorusHold extends Module {

    private static final int NEVER = Integer.MIN_VALUE / 2;

    // The server can finish the fruit this many ticks before the client does.
    private static final int EARLY_TICKS = 5;

    // A teleport this long after the last bite still belongs to the fruit.
    private static final int BITE_WINDOW = 20;

    // The longest a teleport is kept back. The server reads no move whilst it waits and kicks a
    // player it last saw floating in open air after 80 ticks. The round trip and a jump that was
    // already under way come off that.
    private static final int MAX_HOLD_TICKS = 40;

    private final BoolSetting render = new BoolSetting("Show landing",
        "Draws a box where the fruit has put you.", true);
    private final BoxStyle style = new BoxStyle(BoxStyle.Shape.BOTH, 290).under(render);

    // Written on the network thread and read on the game thread.
    private final AtomicReference<ClientboundPlayerPositionPacket> held = new AtomicReference<>();
    private volatile boolean armed;
    // The number of the newest teleport the server has sent whether kept or not.
    private volatile int newestId;

    // A teleport came that this client could not keep. Nothing more is kept until the
    // fruit has been put away once.
    private volatile boolean stoodDown;
    private int biteTick = NEVER;
    // Ticks the kept teleport has been held back.
    private int heldTicks;

    public ChorusHold() {
        super("ChorusHold", "Keeps a chorus fruit teleport back until you put the fruit away or two seconds"
            + " pass whilst the server already has you at the landing spot.", Category.COMBAT);
        addSettings(render);
        addSettings(style.settings());
        searchTags("chorus fruit", "chorus predict", "teleport", "desync");
    }

    @Override
    public String getSuffix() {
        return held.get() == null ? null : "holding";
    }

    @Override
    protected void onEnable() {
        forget();
        biteTick = NEVER;
        heldTicks = 0;
    }

    @Override
    protected void onDisable() {
        armed = false;
        release();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        LocalPlayer player = mc.player;
        // The player after a respawn or a change of world counts its ticks from nought.
        if (player.tickCount < biteTick) {
            biteTick = NEVER;
        }
        if (player.isUsingItem() && teleports(player.getUseItem())
            && player.getUseItemRemainingTicks() <= EARLY_TICKS) {
            biteTick = player.tickCount;
        }
        boolean fruitInHand = player.isAlive()
            && (teleports(player.getMainHandItem()) || teleports(player.getOffhandItem()));
        if (!fruitInHand) {
            stoodDown = false;
        }
        if (held.get() == null) {
            heldTicks = 0;
        } else if (++heldTicks > MAX_HOLD_TICKS) {
            stoodDown = true;
        }
        int sinceBite = player.tickCount - biteTick;
        boolean justAte = sinceBite >= 0 && sinceBite <= BITE_WINDOW;
        armed = fruitInHand && !stoodDown && (held.get() != null || justAte);
        if (!armed) {
            release();
        }
    }

    // The network thread. A join or a respawn starts a new world where nobody waits for an answer
    // to the kept teleport.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundPlayerPositionPacket teleport -> keep(event, teleport);
            case ClientboundLoginPacket _, ClientboundRespawnPacket _ -> forget();
            default -> {
            }
        }
    }

    // Only a teleport to a fixed spot can be kept. One that moves you from where you stand builds
    // on a place this client never went to.
    private void keep(PacketReceiveEvent event, ClientboundPlayerPositionPacket teleport) {
        newestId = teleport.id();
        if (!armed) {
            return;
        }
        Set<Relative> relatives = teleport.relatives();
        if (relatives.contains(Relative.X) || relatives.contains(Relative.Y) || relatives.contains(Relative.Z)) {
            stoodDown = true;
            armed = false;
            return;
        }
        held.set(teleport);
        event.cancel();
    }

    // Drops the kept teleport unanswered.
    private void forget() {
        armed = false;
        stoodDown = false;
        held.set(null);
    }

    // Hands the kept teleport to the game with the view left where you look. One the server
    // has replaced is dropped. It only reads an answer to its newest number.
    private void release() {
        ClientboundPlayerPositionPacket teleport = held.getAndSet(null);
        ClientPacketListener connection = mc.getConnection();
        if (teleport == null || connection == null || mc.player == null || teleport.id() != newestId) {
            return;
        }
        PositionMoveRotation change = teleport.change();
        Set<Relative> relatives = teleport.relatives();
        if (!relatives.contains(Relative.Y_ROT) && !relatives.contains(Relative.X_ROT)) {
            change = change.withRotation(mc.player.getYRot(), mc.player.getXRot());
        }
        new ClientboundPlayerPositionPacket(teleport.id(), change, relatives).handle(connection);
    }

    private static boolean teleports(ItemStack stack) {
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        return consumable != null && consumable.onConsumeEffects().stream()
            .anyMatch(effect -> effect instanceof TeleportRandomlyConsumeEffect);
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        ClientboundPlayerPositionPacket teleport = held.get();
        if (!render.isOn() || teleport == null || !inGame()) {
            return;
        }
        Vec3 landing = teleport.change().position();
        style.draw(event.getBatch(), mc.player.getBoundingBox().move(landing.subtract(mc.player.position())), true);
    }
}
