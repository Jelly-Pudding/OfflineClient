package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.config.ProxyStore;
import com.jellypudding.offlineclient.config.ProxyText;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.setting.ActionSetting;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProxyCheck;
import com.jellypudding.offlineclient.util.ProxyTunnel;
import com.jellypudding.offlineclient.util.ServerInfo;
import com.jellypudding.offlineclient.util.Tally;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// The list of proxies and the login of the one picked. The test and the check work whether or
// not Proxy is switched on.
public final class ProxyCommand extends Command {

    private static final String ADD = "add";
    private static final String USE = "use";
    private static final String REMOVE = "remove";
    private static final String LIST = "list";
    private static final String CHECK = "check";
    private static final String PRUNE = "prune";
    private static final String IMPORT = "import";
    private static final String LOGIN = "login";
    private static final String LOGOUT = "logout";
    private static final String TEST = "test";
    private static final List<String> WORDS = List.of(ADD, USE, REMOVE, LIST, CHECK, PRUNE, IMPORT, LOGIN, LOGOUT,
        TEST);
    private static final String SEPARATOR = " · ";
    // How long the test waits for the proxy to reach the server.
    private static final int TEST_SECONDS = 10;
    // Chat shows no more rows than this. The Proxies tab lists every one.
    private static final int MOST_LISTED = 20;

    private final ActionSetting prune = new ActionSetting("Remove dead proxies",
        "Removes every saved proxy the newest check found dead.", ProxyCheck::prune).confirm();

    public ProxyCommand() {
        super("proxy", "Keeps the list of proxies and saves the login of the one picked.",
            "proxy <add|use|remove|list|check|prune|import|login|logout|test>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case ADD -> add(args);
            case USE -> use(args);
            case REMOVE -> remove(args);
            case LIST -> list(args);
            case CHECK -> check(args);
            case PRUNE -> prune(args);
            case IMPORT -> importList(args);
            case LOGIN -> login(args);
            case LOGOUT -> logout(args);
            case TEST -> test(args);
            default -> usage();
        }
    }

    // A login typed with the proxy is taken out of the chat history again.
    private void add(String[] args) {
        ProxyText.Typed typed = args.length < 2 ? null : ProxyText.typed(words(args, 1), ProxyText.DEFAULT_KIND);
        if (typed == null) {
            usage("proxy add [name] <host:port> [socks5|socks4|http]");
            return;
        }
        if (typed.login() != null) {
            forgetTypedLine(typed.login().password());
        }
        ProxyStore.Entry entry = ProxyStore.get().save(typed);
        if (entry == null) {
            ChatUtil.error("The proxy list holds at most " + ProxyStore.MOST_SAVED + " proxies.");
            return;
        }
        ChatUtil.message("§7Saved the proxy §b" + entry.name() + "§7 at §f" + entry.address() + "§7 as §f"
            + entry.kind() + "§7.");
    }

    // Switching Proxy on says where joins go by itself.
    private void use(String[] args) {
        if (args.length != 2) {
            usage("proxy use <name>");
            return;
        }
        ProxyStore.Entry entry = saved(args[1]);
        Proxy proxy = Modules.get(Proxy.class);
        if (entry == null || proxy == null) {
            return;
        }
        boolean wasOn = proxy.isEnabled();
        proxy.joinThrough(entry);
        if (wasOn) {
            ChatUtil.message("§7Joins go through §b" + entry.name() + "§7 from the next one on.");
        }
    }

    private void remove(String[] args) {
        if (args.length != 2) {
            usage("proxy remove <name>");
            return;
        }
        ProxyStore.Entry entry = saved(args[1]);
        if (entry != null && ProxyStore.get().remove(entry.name())) {
            ChatUtil.message("§7Removed the proxy §b" + entry.name() + "§7 and its login.");
        }
    }

    private void list(String[] args) {
        if (args.length != 1) {
            usage("proxy list");
            return;
        }
        List<ProxyStore.Entry> all = ProxyCheck.ranked(ProxyStore.get().all());
        if (all.isEmpty()) {
            ChatUtil.message("§7No proxy is saved. Type §f" + prefix() + "proxy add§7 or §f" + prefix()
                + "proxy import§7.");
            return;
        }
        ProxyStore.Entry picked = ProxyStore.get().picked();
        ChatUtil.message("§7Saved §f" + Tally.counted(all.size(), "proxy") + "§7.");
        for (ProxyStore.Entry entry : all.subList(0, Math.min(all.size(), MOST_LISTED))) {
            List<String> parts = new ArrayList<>(List.of(entry.address(), entry.kind().toString()));
            if (entry.equals(picked)) {
                parts.add("picked");
            }
            String found = ProxyCheck.detail(entry);
            if (found != null) {
                parts.add(found);
            }
            ChatUtil.row(entry.name(), String.join(SEPARATOR, parts));
        }
        if (all.size() > MOST_LISTED) {
            ChatUtil.message("§7The Proxies tab lists the other §f" + (all.size() - MOST_LISTED) + "§7.");
        }
    }

    private void check(String[] args) {
        if (args.length != 1) {
            usage("proxy check");
            return;
        }
        List<ProxyStore.Entry> all = ProxyStore.get().all();
        if (all.isEmpty()) {
            ChatUtil.error("No proxy is saved.");
            return;
        }
        ProxyCheck.start(all, () -> ClientLines.inChat(() -> ChatUtil.message("§7" + ProxyCheck.summary() + ".")));
        ChatUtil.message("§7Checking §f" + Tally.counted(all.size(), "proxy") + "§7.");
    }

    // The first time names what would go and the second within a few seconds removes them.
    private void prune(String[] args) {
        if (args.length != 1) {
            usage("proxy prune");
            return;
        }
        List<ProxyStore.Entry> dead = ProxyCheck.dead(ProxyStore.get().all());
        if (dead.isEmpty()) {
            ChatUtil.error(ProxyCheck.running() ? "No proxy is found dead yet. The check is still running."
                : "No saved proxy was found dead. Type " + prefix() + "proxy check first.");
            return;
        }
        String refused = ProxyCheck.pruneRefusal();
        if (refused != null) {
            ChatUtil.error(refused + ".");
            return;
        }
        if (!prune.isArmed()) {
            List<String> names = dead.stream().map(ProxyStore.Entry::name).limit(MOST_LISTED).toList();
            String more = dead.size() > MOST_LISTED ? "§7 and §f" + (dead.size() - MOST_LISTED) + " more" : "";
            ChatUtil.message((dead.size() == 1 ? "§7This one was" : "§7These were") + " found dead §f"
                + String.join(" ", names) + more + "§7.");
        }
        press(prune);
    }

    private void importList(String[] args) {
        ProxyStore.Imported imported = ProxyStore.get().importFrom(ProxyStore.Request.of(words(args, 1)));
        if (imported.addedAny()) {
            ChatUtil.message("§7" + imported.words());
        } else {
            ChatUtil.error(imported.words());
        }
    }

    private void login(String[] args) {
        if (args.length != 3) {
            usage("proxy login <name> <password>");
            return;
        }
        forgetTypedLine(args[2]);
        ProxyStore.Entry picked = picked();
        if (picked == null) {
            return;
        }
        if (tooLong(args[1]) || tooLong(args[2])) {
            ChatUtil.error("A proxy name or password holds at most " + ProxyTunnel.LONGEST_LOGIN + " letters.");
            return;
        }
        ProxyStore.get().saveLogin(picked, new ProxyTunnel.Login(args[1], args[2]));
        ChatUtil.message("§7Saved the login for §b" + picked.name() + "§7.");
    }

    private static boolean tooLong(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length > ProxyTunnel.LONGEST_LOGIN;
    }

    private void logout(String[] args) {
        if (args.length != 1) {
            usage("proxy logout");
            return;
        }
        ProxyStore.Entry picked = picked();
        if (picked == null) {
            return;
        }
        if (ProxyStore.get().forgetLogin(picked)) {
            ChatUtil.message("§7Forgot the login for §b" + picked.name() + "§7.");
        } else {
            ChatUtil.error("No login is saved for " + picked.name() + ".");
        }
    }

    // With no server named it tries the one you are on or the last one you joined.
    private void test(String[] args) {
        if (args.length > 2) {
            usage("proxy test [server]");
            return;
        }
        ProxyStore.Entry picked = picked();
        Proxy proxy = Modules.get(Proxy.class);
        if (picked == null || proxy == null) {
            return;
        }
        String server = args.length == 2 ? args[1]
            : ServerInfo.address() != null ? ServerInfo.address() : AutoReconnect.lastAddress();
        if (server == null) {
            usage("proxy test <server>");
            return;
        }
        ProxyTunnel tunnel = proxy.tunnel();
        ChatUtil.message("§7Testing §b" + picked.name() + "§7 against §b" + server + "§7.");
        Thread.ofVirtual().name("OfflineClient proxy test").start(() -> {
            String result = reach(tunnel, server);
            ClientLines.inChatLater(() -> ChatUtil.message(result));
        });
    }

    // Opens a connection to the server through the proxy and closes it once the proxy has reached
    // it. The game never speaks on it. Runs on its own thread as the lookup and the wait would
    // stall the game.
    private static String reach(ProxyTunnel tunnel, String server) {
        Optional<ResolvedServerAddress> resolved =
            ServerNameResolver.DEFAULT.resolveAddress(ServerAddress.parseString(server));
        if (resolved.isEmpty()) {
            return "§cCannot find the server " + server + ".";
        }
        EventLoopGroupHolder group = EventLoopGroupHolder.remote(OfflineClient.MC.options.useNativeTransport());
        long start = System.nanoTime();
        ChannelFuture connect = new Bootstrap()
            .group(group.eventLoopGroup())
            .channel(group.channelCls())
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(TEST_SECONDS))
            .handler(tunnel)
            .connect(resolved.get().asInetSocketAddress());
        try {
            tunnel.done().get(TEST_SECONDS, TimeUnit.SECONDS);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return ChatUtil.from("Proxy", "reached §f" + server + "§7 in §f" + millis + " ms§7.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return "§cThe proxy test failed. " + why;
        } catch (TimeoutException e) {
            return "§cThe proxy gave no answer within " + TEST_SECONDS + " seconds.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "§cThe proxy test was stopped.";
        } finally {
            connect.channel().close();
        }
    }

    // Null after telling the player there is no proxy by that name.
    private static ProxyStore.Entry saved(String name) {
        ProxyStore.Entry entry = ProxyStore.get().find(name);
        if (entry == null) {
            ChatUtil.error("There is no proxy called " + name + ". Type " + prefix() + "proxy list to see them.");
        }
        return entry;
    }

    // Null after telling the player to pick one first.
    private static ProxyStore.Entry picked() {
        ProxyStore.Entry picked = ProxyStore.get().picked();
        if (picked == null) {
            ChatUtil.error("Pick a proxy first with " + prefix() + "proxy use <name>.");
        }
        return picked;
    }

    private static String prefix() {
        return OfflineClient.INSTANCE.getCommandManager().getPrefix();
    }

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        if (index == 1) {
            return CommandManager.filter(current, WORDS);
        }
        String word = tokens[1].toLowerCase(Locale.ROOT);
        if (index == 2 && (word.equals(USE) || word.equals(REMOVE))) {
            return CommandManager.filter(current, ProxyStore.get().all().stream().map(ProxyStore.Entry::name).toList());
        }
        if (index >= 3 && (word.equals(ADD) || word.equals(IMPORT))) {
            return CommandManager.filter(current, List.of("socks5", "socks4", "http"));
        }
        return List.of();
    }
}
