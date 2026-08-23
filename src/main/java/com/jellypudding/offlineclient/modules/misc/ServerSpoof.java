package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;

public final class ServerSpoof extends Module {

    // Channels a server watches to work out which loader is running.
    private static final String[] BLOCKED = {"register", "unregister", "version"};

    private final TextSetting brand = new TextSetting("Brand",
        "Client name the server is told. Leave it empty to send the real one.", "vanilla");
    private final BoolSetting blockChannels = new BoolSetting("Block channels",
        "Drop the loader plugin messages that give the mod list away.", true);

    public ServerSpoof() {
        super("ServerSpoof", "Reports a plain client to the server.", Category.MISC);
        addSettings(brand, blockChannels);
        searchTags("server spoof", "brand", "vanilla spoof", "anti fabric");
    }

    // Joining a server tells it you are on a loader unless this is already running.
    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public String getSuffix() {
        return brand.isBlank() ? null : brand.getValue();
    }

    /**
     * The brand goes out during the configuration phase before a world
     * exists. Fired on the netty thread.
     */
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        if (!(event.getPacket() instanceof ServerboundCustomPayloadPacket packet)) {
            return;
        }
        Identifier id = packet.payload().type().id();

        if (id.equals(BrandPayload.TYPE.id())) {
            if (!brand.isBlank()) {
                event.setPacket(new ServerboundCustomPayloadPacket(
                    new BrandPayload(brand.getValue())));
            }
            return;
        }
        if (blockChannels.isOn() && isFingerprint(id)) {
            event.cancel();
        }
    }

    private boolean isFingerprint(Identifier id) {
        if (id.getNamespace().startsWith("fabric")) {
            return true;
        }
        for (String path : BLOCKED) {
            if (id.getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }
}
