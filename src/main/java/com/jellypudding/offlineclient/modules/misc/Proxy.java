package com.jellypudding.offlineclient.modules.misc;

import com.jellypudding.offlineclient.config.ProxyStore;
import com.jellypudding.offlineclient.config.ProxyText;
import com.jellypudding.offlineclient.event.Subscribe;
import com.jellypudding.offlineclient.event.events.TickEvent;
import com.jellypudding.offlineclient.module.Category;
import com.jellypudding.offlineclient.module.Module;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ProxyTunnel;

import java.io.IOException;
import java.net.InetSocketAddress;

// Every join and server list ping goes through the proxy picked in the Proxies tab from the next
// one on. Server packs are downloaded through it too. The address of the server is looked up here
// before it is handed to the proxy.
public final class Proxy extends Module {

    // A skipped server pack is said in chat once each time the module is switched on. The skip
    // happens on a download thread and the line waits until you are in a world.
    private enum PackNotice { NONE, DUE, SAID }

    private volatile PackNotice packNotice = PackNotice.NONE;

    public Proxy() {
        super("Proxy", "Joins servers through the proxy picked in the Proxies tab and they see its address in"
            + " place of yours.", Category.MISC);
        searchTags("socks", "socks5", "http proxy", "hide ip", "ip ban", "proxy list");
    }

    @Override
    public String getSuffix() {
        ProxyStore.Entry picked = ProxyStore.get().picked();
        return picked == null ? null : picked.name();
    }

    // With nothing picked the module stays on and turns every join down rather than let one go
    // out without the proxy.
    @Override
    protected void onEnable() {
        packNotice = PackNotice.NONE;
        ProxyStore.Entry picked = ProxyStore.get().picked();
        if (picked == null) {
            ChatUtil.error("Pick a proxy in the Proxies tab. Joins fail until one is picked.");
        } else if (inGame()) {
            tell("carries your joins through §b" + picked.name() + "§7 from the next one on.");
        }
    }

    @Subscribe
    private void onTick(TickEvent event) {
        if (packNotice == PackNotice.DUE) {
            packNotice = PackNotice.SAID;
            tell("skips server packs whilst this proxy has a saved login."
                + " A download cannot log in and never goes around the proxy.");
        }
    }

    // Joins go through this proxy from the next one on.
    public void joinThrough(ProxyStore.Entry entry) {
        ProxyStore.get().pick(entry);
        setEnabled(true);
    }

    // A fresh tunnel for one connection. Built on the network thread as each connection opens. A
    // tunnel with no host turns the connection down.
    public ProxyTunnel tunnel() {
        ProxyStore store = ProxyStore.get();
        ProxyStore.Entry picked = store.picked();
        return picked == null ? new ProxyTunnel(ProxyText.DEFAULT_KIND, "", 0, null)
            : new ProxyTunnel(picked.kind(), picked.host(), picked.port(), store.login(picked));
    }

    // How a server pack download goes out. It runs on a download thread. Java takes a proxy login
    // only for the whole game and a launcher proxy can hold that already. A download the proxy cannot
    // carry never goes out straight.
    public java.net.Proxy downloadRoute() throws IOException {
        ProxyStore store = ProxyStore.get();
        ProxyStore.Entry picked = store.picked();
        if (picked == null) {
            throw new IOException("No proxy is picked");
        }
        if (store.login(picked) != null) {
            if (packNotice == PackNotice.NONE) {
                packNotice = PackNotice.DUE;
            }
            throw new IOException("A download cannot log in to the proxy");
        }
        java.net.Proxy.Type kind = picked.kind() == ProxyTunnel.Kind.HTTP_TUNNEL
            ? java.net.Proxy.Type.HTTP : java.net.Proxy.Type.SOCKS;
        return new java.net.Proxy(kind, new InetSocketAddress(picked.host(), picked.port()));
    }
}
