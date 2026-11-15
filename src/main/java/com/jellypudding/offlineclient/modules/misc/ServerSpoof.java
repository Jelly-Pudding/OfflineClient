package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.ClientTickEvent;
import com.jellypudding.offlineclient.event.events.PacketReceiveEvent;
import com.jellypudding.offlineclient.event.events.PacketSendEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.BoolSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.BoundedMap;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.CheckedDownload;
import com.jellypudding.offlineclient.util.HomeNetwork;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.PacketUtil;
import com.jellypudding.offlineclient.util.TextLines;
import com.jellypudding.offlineclient.util.UnmoddedText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.resolver.AddressCheck;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

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

    // A pack the server was told had loaded. It waits until the player loads it after all or the
    // server takes it back.
    private record HeldPack(ClientboundResourcePackPushPacket push, boolean waiting) {
    }

    // Far more packs than a server stacks. A flood of pushes drops the eldest.
    private static final int MOST_HELD = 32;

    // Lines that wait for a world to be said in. A flood of pushes fills no more than this.
    private static final int MOST_NOTICES = 8;

    // The longest name the DNS holds.
    private static final int LONGEST_HOST = 253;

    // One label of a host name. Letters and digits and inner hyphens carry nothing a proxy in front
    // of the server could read as the address and identity of another player.
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    // A host shown in chat is cut to this.
    private static final int SHOWN_HOST = 60;

    private final TextSetting brand = new TextSetting("Brand",
        "Client name the server is told. Leave it empty to send the real one.", "vanilla");
    private final BoolSetting blockChannels = new BoolSetting("Block channels",
        "Drops the loader plugin messages that give the mod list away.", true);
    private final TextLines channels = new TextLines("Channels",
        "How many channel names to drop.",
        "Any channel whose name holds this text is dropped. Click to type it.", "fabric",
        "minecraft:register", "minecraft:unregister", "minecraft:version").plain().under(blockChannels);
    private final BoolSetting plainText = new BoolSetting("Hide mod text",
        "Sign and anvil text from the server reads the way it would without mods. A server can show you words"
            + " only a mod knows and see what comes back.", true);
    private final BoolSetting probeAlerts = new BoolSetting("Probe alerts",
        "Says in chat when the server shows you a sign or an anvil whose text asks for words only a mod knows.",
        true).under(plainText);
    private final TextSetting hostName = new TextSetting("Host name",
        "The address the server is told you typed when you join. Leave it empty to send the real one.", "");
    private final BoolSetting resourcePack = new BoolSetting("Block resource packs",
        "Tells the server a required pack loaded without ever downloading it. The chat line about it can load"
            + " the pack on your side alone.", false);
    private final BoolSetting freshPacks = new BoolSetting("Fresh packs",
        "Downloads every server pack anew and never reuses a saved copy that could link your accounts.", false);
    private final BoolSetting homeGuard = new BoolSetting("Refuse local packs",
        "Refuses a server pack that would download from your own machine or home network. A server on that"
            + " network may still send one.", true);
    private final BoolSetting ignoreBlocked = new BoolSetting("Ignore blocked list",
        "Lets you join servers that Mojang has put on its blocked list.", true);

    // Every pack answered for on this connection. Written on the netty thread and read on others.
    private final Map<UUID, HeldPack> held = Collections.synchronizedMap(new BoundedMap<>(MOST_HELD));
    // Filled from any thread and said on the game thread once there is a world to say them in.
    private final Queue<Component> notices = new ConcurrentLinkedQueue<>();
    // True once the player has heard of a probe. A new connection or switching off forgets it.
    private boolean probeTold;
    // Set as each join starts. True whilst the game's own connection went straight to an address on
    // your own network. Read on a download thread.
    private volatile boolean joinedAtHome;

    public ServerSpoof() {
        super("ServerSpoof", "Reports a plain client to the server.", Category.MISC);
        addSettings(brand, blockChannels);
        addSettings(channels.settings());
        addSettings(plainText, probeAlerts, hostName, resourcePack, freshPacks, homeGuard, ignoreBlocked);
        searchTags("server spoof", "brand", "vanilla spoof", "anti fabric", "resource pack", "blocked servers",
            "translation keys", "key binds", "mod detection", "host override", "handshake", "localhost");
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

    @Override
    protected void onDisable() {
        held.clear();
        notices.clear();
        probeTold = false;
    }

    // Read by ServerNameResolverMixin. Only the game itself checks Mojang's blocked list.
    public AddressCheck addressCheck(AddressCheck original) {
        return isEnabled() && ignoreBlocked.isOn() ? ANY_ADDRESS : original;
    }

    // Read by HttpUtilMixin.
    public boolean freshPacks() {
        return isEnabled() && freshPacks.isOn();
    }

    // True whilst the words of a text the server sent go back to it the way a game without mods
    // reads them. Read by the sign and anvil mixins. A world of your own has nobody to hide from.
    // The words a text reads as in a game with no mods. Null whilst mod text is not hidden. A
    // text that asks for what only a mod knows is the server looking for mods. The player hears
    // of the first one on each connection.
    public static String plainWords(Component text) {
        ServerSpoof spoof = Modules.active(ServerSpoof.class);
        if (spoof == null || !spoof.plainText.isOn() || mc.isLocalServer()) {
            return null;
        }
        if (spoof.probeAlerts.isOn() && !spoof.probeTold && UnmoddedText.asksForMods(text)) {
            spoof.probeTold = true;
            spoof.say(Component.literal("§eThe server showed you text that asks for the words of a mod. It was"
                + " sent what a game with no mods reads."));
        }
        return UnmoddedText.flatten(text);
    }

    // Called by ConnectionMixin on the thread that opens a join to a server. It runs whether the
    // module is on or not. Whatever an earlier join left waiting goes first. Hands back the host the
    // handshake names.
    public String joining(Connection connection, String typed) {
        held.clear();
        notices.clear();
        probeTold = false;
        joinedAtHome = Modules.active(Proxy.class) == null
            && connection.getRemoteAddress() instanceof InetSocketAddress server
            && server.getAddress() != null && HomeNetwork.holds(server.getAddress());
        return isEnabled() ? handshakeHost(typed) : typed;
    }

    private String handshakeHost(String typed) {
        String chosen = hostName.getValue().trim();
        if (chosen.isEmpty()) {
            return typed;
        }
        String plain = plainHost(chosen);
        if (plain == null) {
            say(Component.literal("§cServerSpoof sent the address you typed. A host name holds nothing but"
                + " letters and digits and dots and hyphens."));
            return typed;
        }
        say(Component.literal("§7ServerSpoof told the server you joined through §f" + plain + "§7."));
        return plain;
    }

    // The name the way the game sends one with a dot between labels. Null for anything that is not
    // a plain host name.
    private static String plainHost(String chosen) {
        String ascii;
        try {
            ascii = IDN.toASCII(chosen, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (ascii.isEmpty() || ascii.length() > LONGEST_HOST) {
            return null;
        }
        for (String label : ascii.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) {
                return null;
            }
        }
        return ascii;
    }

    // A download of a server pack that keeps off your own network. Null whilst the switch is off.
    // Read by HttpUtilMixin on a download thread.
    public CheckedDownload guardedDownload(URL pack, java.net.Proxy route) {
        return homeGuard.isOn() ? new CheckedDownload(pack, route, this::refuseHome) : null;
    }

    // A server on your own network has every reason to keep its pack there too.
    private void refuseHome(String host, InetAddress address) throws IOException {
        if (!HomeNetwork.holds(address) || joinedAtHome) {
            return;
        }
        say(Component.literal(ChatUtil.from(getName(), "refused a server pack from §f"
            + StringUtil.truncateStringIfNecessary(host, SHOWN_HOST, true)
            + "§7. That address is on your own network.")));
        throw new IOException("The pack address is on the home network");
    }

    private void say(Component line) {
        if (notices.size() < MOST_NOTICES) {
            notices.add(line);
        }
    }

    // Chat is only safe on the game thread and a pack can come before there is a world.
    @Subscribe
    private void onClientTick(ClientTickEvent event) {
        while (mc.player != null && !notices.isEmpty()) {
            ChatUtil.component(notices.poll());
        }
    }

    // The brand goes out during the configuration phase before a world exists.
    // Fired on the netty thread.
    @Subscribe
    private void onPacketSend(PacketSendEvent event) {
        switch (event.getPacket()) {
            case ServerboundCustomPayloadPacket packet -> onPayload(event, packet.payload().type().id());
            // The server already has its answer about a pack that was held back.
            case ServerboundResourcePackPacket status when held.containsKey(status.id()) -> event.cancel();
            default -> {
            }
        }
    }

    private void onPayload(PacketSendEvent event, Identifier id) {
        if (id.equals(BrandPayload.TYPE.id())) {
            if (!brand.isBlank()) {
                event.setPacket(new ServerboundCustomPayloadPacket(
                    new BrandPayload(brand.getValue())));
            }
        } else if (blockChannels.isOn() && isFingerprint(id)) {
            event.cancel();
        }
    }

    // The push is swallowed and answered as though the pack had loaded. The game answers an
    // address it would never download from itself. A faked load there would give the block away.
    @Subscribe
    private void onPacketReceive(PacketReceiveEvent event) {
        switch (event.getPacket()) {
            case ClientboundResourcePackPushPacket push
                when resourcePack.isOn() && webAddress(push.url()) != null -> {
                event.cancel();
                hold(push);
            }
            case ClientboundResourcePackPopPacket pop -> withdraw(pop.id().orElse(null));
            default -> {
            }
        }
    }

    // A pack pushed again under the same id is answered again. Its answers must get out.
    private void hold(ClientboundResourcePackPushPacket push) {
        held.remove(push.id());
        answer(push, ServerboundResourcePackPacket.Action.ACCEPTED);
        answer(push, ServerboundResourcePackPacket.Action.DOWNLOADED);
        answer(push, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        held.put(push.id(), new HeldPack(push, true));
        say(heldLine(push));
    }

    private static void answer(ClientboundResourcePackPushPacket packet,
                               ServerboundResourcePackPacket.Action action) {
        PacketUtil.send(new ServerboundResourcePackPacket(packet.id(), action));
    }

    // A pack the server took back cannot be loaded after that. The game takes it off by itself where
    // the player loaded it first. Null takes them all back.
    private void withdraw(UUID id) {
        held.replaceAll((heldId, pack) ->
            id == null || id.equals(heldId) ? new HeldPack(pack.push(), false) : pack);
    }

    // The same test the game makes before it downloads a pack. A stricter parse would answer
    // some addresses differently from the game. Null for an address the game refuses.
    @SuppressWarnings("deprecation")
    private static URL webAddress(String url) {
        try {
            URL address = new URL(url);
            String protocol = address.getProtocol();
            return protocol.equals("http") || protocol.equals("https") ? address : null;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private static Component heldLine(ClientboundResourcePackPushPacket push) {
        MutableComponent line = Component.literal("§7Blocked a "
            + (push.required() ? "required" : "optional") + " resource pack. ");
        Component open = openLink(push.url());
        if (open != null) {
            line.append(open).append(" ");
        }
        return line.append(ChatUtil.commandLink("§b[Load pack]", "pack " + push.id(),
            "Click to load this pack on your side. The server is told nothing more."));
    }

    // An address the game takes can still be one a link cannot open. Null for such an address.
    private static Component openLink(String url) {
        try {
            URI address = new URI(url);
            return Component.literal("[Open URL]").withStyle(style -> style.applyFormat(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.OpenUrl(address)));
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // Loads a pack held back on this connection through the game's own pack source. Null loads
    // every one in the order the server sent them. What the game then reports about them never
    // leaves. Hands back how many it started on.
    public int loadHeld(UUID only) {
        List<ClientboundResourcePackPushPacket> waiting;
        synchronized (held) {
            waiting = held.values().stream().filter(HeldPack::waiting).map(HeldPack::push)
                .filter(push -> only == null || only.equals(push.id())).toList();
            waiting.forEach(push -> held.put(push.id(), new HeldPack(push, false)));
        }
        if (waiting.isEmpty()) {
            return 0;
        }
        DownloadedPackSource packs = mc.getDownloadedPackSource();
        // A server entry set to refuse packs or to ask first would leave these unloaded.
        packs.allowServerPacks();
        for (ClientboundResourcePackPushPacket push : waiting) {
            packs.pushPack(push.id(), webAddress(push.url()), push.hash());
        }
        return waiting.size();
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
