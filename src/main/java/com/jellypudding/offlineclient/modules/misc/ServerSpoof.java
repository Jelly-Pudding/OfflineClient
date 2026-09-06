package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.TextLines;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.util.Locale;

public final class ServerSpoof extends Module {

    private final TextSetting brand = new TextSetting("Brand",
        "Client name the server is told. Leave it empty to send the real one.", "vanilla");
    private final BoolSetting blockChannels = new BoolSetting("Block channels",
        "Drop the loader plugin messages that give the mod list away.", true);
    private final TextLines channels = new TextLines("Channels",
        "How many channel names to drop.", "fabric", "minecraft:register", "minecraft:unregister",
        "minecraft:version").plain().under(blockChannels);
    private final BoolSetting resourcePack = new BoolSetting("Block resource packs",
        "Tells the server a required pack loaded without ever downloading it.", false);

    public ServerSpoof() {
        super("ServerSpoof", "Reports a plain client to the server.", Category.MISC);
        addSettings(brand, blockChannels);
        addSettings(channels.settings());
        addSettings(resourcePack);
        searchTags("server spoof", "brand", "vanilla spoof", "anti fabric", "resource pack");
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

    // The brand goes out during the configuration phase before a world exists.
    // Fired on the netty thread.
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

    // The push is swallowed and answered as though the pack had loaded.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!resourcePack.isOn()
            || !(event.getPacket() instanceof ClientboundResourcePackPushPacket packet)) {
            return;
        }
        event.cancel();
        answer(packet, ServerboundResourcePackPacket.Action.ACCEPTED);
        answer(packet, ServerboundResourcePackPacket.Action.DOWNLOADED);
        answer(packet, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        announce(packet);
    }

    private static void answer(ClientboundResourcePackPushPacket packet,
                               ServerboundResourcePackPacket.Action action) {
        PacketUtil.send(new ServerboundResourcePackPacket(packet.id(), action));
    }

    private static void announce(ClientboundResourcePackPushPacket packet) {
        MutableComponent line = Component.literal("§7Blocked a "
            + (packet.required() ? "required" : "optional") + " resource pack. ");
        MutableComponent link = Component.literal("[Open URL]")
            .withStyle(style -> style.applyFormat(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(packet.url()))));
        ChatUtil.component(line.append(link));
    }

    // Names are matched anywhere in the channel with case ignored.
    private boolean isFingerprint(Identifier id) {
        String full = id.toString().toLowerCase(Locale.ROOT);
        for (String name : channels.all()) {
            if (full.contains(name.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
