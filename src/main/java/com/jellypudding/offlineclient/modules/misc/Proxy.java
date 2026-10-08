package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.ServerStore;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.setting.EnumSetting;
import com.jellypudding.offlineclient.setting.NumberSetting;
import com.jellypudding.offlineclient.setting.TextSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ProxyTunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;

// Every join and server list ping goes through the proxy from the next one on. Server packs are
// downloaded through it too. The address of the server is looked up here before it is handed to the
// proxy.
public final class Proxy extends Module {

    // A skipped server pack is said in chat once each time the module is switched on. The skip
    // happens on a download thread and the line waits until you are in a world.
    private enum PackNotice { NONE, DUE, SAID }

    // The highest port number there is.
    private static final int LAST_PORT = 65535;

    private final EnumSetting<ProxyTunnel.Kind> type = new EnumSetting<>("Type", "The kind of proxy.",
        ProxyTunnel.Kind.SOCKS_5)
        .describe(ProxyTunnel.Kind.SOCKS_5, "The kind most proxy services sell.")
        .describe(ProxyTunnel.Kind.SOCKS_4, "An older kind with no password.")
        .describe(ProxyTunnel.Kind.HTTP_TUNNEL, "A web proxy that allows tunnels.");
    private final TextSetting host = new TextSetting("Host", "The address of the proxy. Click to type it.", "");
    private final NumberSetting port = new NumberSetting("Port", "The port of the proxy.", 1080, 1, LAST_PORT, 1)
        .min(1).max(LAST_PORT);

    private volatile PackNotice packNotice = PackNotice.NONE;

    public Proxy() {
        super("Proxy", "Joins servers through a SOCKS or HTTP proxy.", Category.MISC);
        addSettings(type, host, port);
        searchTags("socks", "socks5", "http proxy", "hide ip", "ip ban");
    }

    @Override
    public String getSuffix() {
        return type.getValueString();
    }

    @Override
    protected void onEnable() {
        packNotice = PackNotice.NONE;
        if (!hasHost()) {
            ChatUtil.error("Type the proxy host in the Proxy settings.");
        } else if (inGame()) {
            ChatUtil.message("§bProxy §7carries your joins from the next one on.");
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (packNotice == PackNotice.DUE) {
            packNotice = PackNotice.SAID;
            ChatUtil.message("§bProxy §7skips server packs whilst this proxy has a saved login."
                + " A download cannot log in and never goes around the proxy.");
        }
    }

    public boolean hasHost() {
        return !host.isBlank();
    }

    public String host() {
        return host.getValue().trim();
    }

    // A fresh tunnel for one connection. Built on the network thread as each connection opens.
    public ProxyTunnel tunnel() {
        return new ProxyTunnel(type.getValue(), host(), port.getInt(), login());
    }

    // How a server pack download goes out. It runs on a download thread. Java takes a proxy login
    // only for the whole game and a launcher proxy can hold that already. A download the proxy cannot
    // carry never goes out straight.
    public java.net.Proxy downloadRoute() throws IOException {
        if (!hasHost()) {
            throw new IOException("The proxy has no host");
        }
        if (login() != null) {
            if (packNotice == PackNotice.NONE) {
                packNotice = PackNotice.DUE;
            }
            throw new IOException("A download cannot log in to the proxy");
        }
        java.net.Proxy.Type kind = type.getValue() == ProxyTunnel.Kind.HTTP_TUNNEL
            ? java.net.Proxy.Type.HTTP : java.net.Proxy.Type.SOCKS;
        return new java.net.Proxy(kind, new InetSocketAddress(host(), port.getInt()));
    }

    // The saved logins are read on the network threads and written on the game thread.
    private synchronized ProxyTunnel.Login login() {
        return ServerStore.proxyLogins().get(hostKey());
    }

    public synchronized void saveLogin(String name, String password) {
        ServerStore.proxyLogins().set(hostKey(), new ProxyTunnel.Login(name, password));
    }

    public synchronized boolean forgetLogin() {
        return ServerStore.proxyLogins().remove(hostKey());
    }

    private String hostKey() {
        return host().toLowerCase(Locale.ROOT);
    }
}
