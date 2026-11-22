package com.jellypudding.offlineclient.gui;

import com.jellypudding.offlineclient.config.ProxyStore;
import com.jellypudding.offlineclient.config.ProxyText;
import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProxyCheck;
import com.jellypudding.offlineclient.util.Tally;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProxiesTab implements TabView.Source {

    private static final String PICKED = "picked";
    private static final String CHECK = "check";
    private static final String PRUNE = "prune";
    private static final String IMPORT = "import";
    // An address kept for examples that belongs to nobody.
    private static final String PROXY_EXAMPLE = "203.0.113.5:1080";
    private static final String LOGIN_EXAMPLE = PROXY_EXAMPLE + ":name:password";
    private static final long SAID_MS = 6000;
    private static final TabView.Hint IMPORT_HINT = new TabView.Hint("Import a list",
        "Put a text file in the offlineclient folder with one proxy on each line.",
        List.of(IMPORT + " " + ProxyStore.DEFAULT_FILE));

    private String said = "";
    private long saidUntil;

    @Override
    public List<TabView.Entry> entries(String query) {
        ProxyStore store = ProxyStore.get();
        ProxyStore.Entry picked = store.picked();
        boolean on = Modules.active(Proxy.class) != null;
        List<TabView.Entry> rows = new ArrayList<>();
        for (ProxyStore.Entry entry : ProxyCheck.ranked(store.all())) {
            if (GuiSources.matches(entry.name(), query) || GuiSources.matches(entry.address(), query)) {
                rows.add(proxyRow(store, entry, entry.equals(picked), on));
            }
        }
        return rows;
    }

    @Override
    public String emptyText(String query) {
        if (query.isEmpty()) {
            return "no proxies saved yet";
        }
        List<ProxyStore.Entry> all = ProxyStore.get().all();
        String word = query.toLowerCase(Locale.ROOT);
        if (word.equals(CHECK)) {
            return all.isEmpty() ? "no proxies to check" : "press enter to check " + Tally.counted(all.size(), "proxy");
        }
        if (word.equals(PRUNE)) {
            int dead = ProxyCheck.dead(all).size();
            return dead == 0 ? "the newest check found no proxy dead"
                : ProxyCheck.pruneRefusal() != null ? "prune removes nothing whilst not one proxy took a connection"
                : "press enter to remove " + Tally.counted(dead, "dead proxy");
        }
        if (isImport(word)) {
            return "press enter to add the proxies in " + importRequest(query).shownFile();
        }
        ProxyText.Typed typed = ProxyText.typed(query, ProxyText.DEFAULT_KIND);
        return typed == null ? "type the address of a proxy such as " + PROXY_EXAMPLE
            : "press enter to save " + named(typed.savedName(), typed.address()) + " as " + typed.kind()
                + (typed.login() == null ? "" : " with its login");
    }

    @Override
    public String footer() {
        if (ProxyCheck.running()) {
            return ProxyCheck.progress();
        }
        return System.currentTimeMillis() < saidUntil ? said : "click a proxy to join through it";
    }

    @Override
    public String addHint() {
        return "type the address of a proxy";
    }

    @Override
    public List<TabView.Hint> hints(String query) {
        if (!query.isEmpty()) {
            return List.of();
        }
        ProxyStore store = ProxyStore.get();
        List<ProxyStore.Entry> all = store.all();
        if (all.isEmpty()) {
            return List.of(
                new TabView.Hint("What a proxy is", "Another computer that passes your connection on. A server you"
                    + " join through it sees its address in place of yours. Unlike a VPN it carries only the game's"
                    + " joins and server list pings and server packs.", List.of()),
                new TabView.Hint("Add a proxy", "Type the address of a proxy you rent or run and press enter. A name"
                    + " for the list can go before it. It is taken as a SOCKS5 proxy unless socks4 or http follows"
                    + " it. A proxy that wants a login takes the name and password after the port.",
                    List.of(PROXY_EXAMPLE, "home " + PROXY_EXAMPLE + " http", LOGIN_EXAMPLE)),
                IMPORT_HINT);
        }
        List<TabView.Hint> hints = new ArrayList<>();
        if (Modules.active(Proxy.class) == null || store.picked() == null) {
            hints.add(new TabView.Hint("Join through a proxy", "Click one in the list. That switches Proxy on and"
                + " every join and server list ping goes through it from the next one on. Click it again to stop"
                + " using a proxy.", List.of()));
        }
        hints.add(new TabView.Hint("Check them all", "Times how long each proxy takes to take a connection.",
            List.of(CHECK)));
        if (!ProxyCheck.dead(all).isEmpty() && ProxyCheck.pruneRefusal() == null) {
            hints.add(new TabView.Hint("Remove the dead ones", "Removes every proxy the newest check found dead.",
                List.of(PRUNE)));
        }
        hints.add(IMPORT_HINT);
        return hints;
    }

    @Override
    public void add(String text) {
        String word = text.toLowerCase(Locale.ROOT);
        if (word.equals(CHECK)) {
            List<ProxyStore.Entry> all = ProxyStore.get().all();
            if (!all.isEmpty()) {
                ProxyCheck.start(all, () -> say(ProxyCheck.summary()));
            }
        } else if (word.equals(PRUNE)) {
            say(ProxyCheck.prune());
        } else if (isImport(word)) {
            say(ProxyStore.get().importFrom(importRequest(text)).words());
        } else {
            ProxyText.Typed typed = ProxyText.typed(text, ProxyText.DEFAULT_KIND);
            ProxyStore.Entry entry = typed == null ? null : ProxyStore.get().save(typed);
            say(typed == null ? "That is no proxy. Type an address such as " + PROXY_EXAMPLE
                : entry == null ? "The list holds at most " + ProxyStore.MOST_SAVED + " proxies"
                : "Saved " + named(entry.name(), entry.address()));
        }
    }

    private void say(String words) {
        said = words;
        saidUntil = System.currentTimeMillis() + SAID_MS;
    }

    @Override
    public void activate(String name) {
        ProxyStore.Entry entry = ProxyStore.get().find(name);
        Proxy proxy = Modules.get(Proxy.class);
        if (entry == null || proxy == null) {
            return;
        }
        if (proxy.isEnabled() && entry.equals(ProxyStore.get().picked())) {
            proxy.setEnabled(false);
        } else {
            proxy.joinThrough(entry);
        }
    }

    @Override
    public String dropHint(String name) {
        return "Remove the proxy " + name + " and its login";
    }

    @Override
    public void drop(String name) {
        ProxyStore.get().remove(name);
    }

    private static TabView.Entry proxyRow(ProxyStore store, ProxyStore.Entry entry, boolean picked, boolean on) {
        String found = ProxyCheck.detail(entry);
        String detail = picked ? (on ? GuiSources.IN_USE : PICKED) : found == null ? entry.kind().toString() : found;
        String login = store.login(entry) == null ? "" : " with a saved login";
        String check = ProxyCheck.sentence(entry);
        String click = !picked ? "Click to join through it."
            : on ? "Joins go through it. Click to stop using a proxy."
            : "Picked for when Proxy is on. Click to join through it.";
        return new TabView.Entry(entry.name(), detail, entry.kind() + " proxy at " + entry.address() + login + ". "
            + (check.isEmpty() ? "" : check + " ") + click, ProxyCheck.isDead(entry));
    }

    private static String named(String name, String address) {
        return name.equals(address) ? address : name + " at " + address;
    }

    private static boolean isImport(String word) {
        return word.equals(IMPORT) || word.startsWith(IMPORT + " ");
    }

    private static ProxyStore.Request importRequest(String text) {
        return ProxyStore.Request.of(text.substring(IMPORT.length()));
    }
}
