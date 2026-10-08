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
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;

public final class ServerSpoof extends Module {

    private static final AddressCheck ANY_ADDRESS = new AddressCheck() {
        @Override
        public boolean isAllowed(ResolvedServerAddress address) {
            return true;
        }

        @Override
        public boolean isAllowed(ServerAddress address) {
            return true;
        }
    };

    private final TextSetting brand = new TextSetting("Brand",
        "Client name the server is told. Leave it empty to send the real one.", "vanilla");
    private final BoolSetting blockChannels = new BoolSetting("Block channels",
        "Drop the loader plugin messages that give the mod list away.", true);
    private final TextLines channels = new TextLines("Channels",
        "How many channel names to drop.",
        "Any channel whose name holds this text is dropped. Click to type it.", "fabric",
        "minecraft:register", "minecraft:unregister", "minecraft:version").plain().under(blockChannels);
    private final BoolSetting resourcePack = new BoolSetting("Block resource packs",
        "Tells the server a required pack loaded without ever downloading it.", false);
    private final BoolSetting freshPacks = new BoolSetting("Fresh packs",
        "Downloads every server pack anew and never reuses a saved copy that could link your accounts.", false)
        .unless(resourcePack);
    private final BoolSetting ignoreBlocked = new BoolSetting("Ignore blocked list",
        "Lets you join servers that Mojang has put on its blocked list.", true);

    public ServerSpoof() {
        super("ServerSpoof", "Reports a plain client to the server.", Category.MISC);
        addSettings(brand, blockChannels);
        addSettings(channels.settings());
        addSettings(resourcePack, freshPacks, ignoreBlocked);
        searchTags("server spoof", "brand", "vanilla spoof", "anti fabric", "resource pack", "blocked servers");
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

    // Read by ServerNameResolverMixin. Only the game itself checks Mojang's blocked list.
    public AddressCheck addressCheck(AddressCheck original) {
        return isEnabled() && ignoreBlocked.isOn() ? ANY_ADDRESS : original;
    }

    // Read by HttpUtilMixin.
    public boolean freshPacks() {
        return isEnabled() && freshPacks.isOn();
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

    // The push is swallowed and answered as though the pack had loaded. The game answers an
    // address it would never download from itself. A faked load there would give the block away.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        if (!resourcePack.isOn()
            || !(event.getPacket() instanceof ClientboundResourcePackPushPacket packet)
            || !webLink(packet.url())) {
            return;
        }
        event.cancel();
        answer(packet, ServerboundResourcePackPacket.Action.ACCEPTED);
        answer(packet, ServerboundResourcePackPacket.Action.DOWNLOADED);
        answer(packet, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        // Chat is only safe on the game thread.
        mc.schedule(() -> announce(packet));
    }

    private static void answer(ClientboundResourcePackPushPacket packet,
                               ServerboundResourcePackPacket.Action action) {
        PacketUtil.send(new ServerboundResourcePackPacket(packet.id(), action));
    }

    // The same test the game makes before it downloads a pack. A stricter parse would answer
    // some addresses differently from the game.
    @SuppressWarnings("deprecation")
    private static boolean webLink(String url) {
        try {
            String protocol = new URL(url).getProtocol();
            return protocol.equals("http") || protocol.equals("https");
        } catch (MalformedURLException e) {
            return false;
        }
    }

    // An address the game takes can still be one a link cannot open. The line then has no link.
    private static void announce(ClientboundResourcePackPushPacket packet) {
        MutableComponent line = Component.literal("§7Blocked a "
            + (packet.required() ? "required" : "optional") + " resource pack. ");
        URI address;
        try {
            address = new URI(packet.url());
        } catch (URISyntaxException e) {
            ChatUtil.component(line);
            return;
        }
        MutableComponent link = Component.literal("[Open URL]")
            .withStyle(style -> style.applyFormat(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.OpenUrl(address)));
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
