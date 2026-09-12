package com.jellypudding.offlineclient.modules.combat;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.util.BlockUtil;
import com.jellypudding.offlineclient.util.InventoryUtil.SlotSwap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.AnvilBlock;

// Drops an anvil onto your own head. Nobody can walk into a hole with an anvil in it.
public final class SelfAnvil extends Module {

    private final BoolSetting rotate = new BoolSetting("Rotate",
        "Send a look packet towards the spot.", true);

    private final SlotSwap slots = new SlotSwap();

    public SelfAnvil() {
        super("SelfAnvil", "Places an anvil above you to keep others out of your hole.",
            Category.COMBAT);
        addSettings(rotate);
        searchTags("anvil", "hole");
    }

    @Override
    protected void onEnable() {
        slots.forget();
    }

    @Override
    protected void onDisable() {
        slots.restore();
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (!inGame() || mc.player.isSpectator()) {
            return;
        }
        BlockPos target = mc.player.blockPosition().above(2);
        if (!BlockUtil.isReplaceable(target)) {
            slots.restore();
            setEnabled(false);
            return;
        }
        int slot = BlockUtil.findBlockSlot(block -> block instanceof AnvilBlock);
        if (slot == -1) {
            slots.restore();
            return;
        }
        slots.select(slot);
        boolean placed = BlockUtil.placeAny(target, rotate.isOn(), true);
        slots.restore();
        if (placed) {
            setEnabled(false);
        }
    }

    // The server opens the repair menu for a click that lands on an anvil already down.
    // The menu is closed on the server too. The next click is then not swallowed.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacket() instanceof ClientboundOpenScreenPacket packet
            && packet.getType() == MenuType.ANVIL && mc.player != null) {
            event.cancel();
            mc.player.connection.send(new ServerboundContainerClosePacket(packet.getContainerId()));
        }
    }
}
