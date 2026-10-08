package com.jellypudding.offlineclient.command.commands;

import com.jellypudding.offlineclient.OfflineClient;
import com.jellypudding.offlineclient.command.Command;
import com.jellypudding.offlineclient.command.CommandManager;
import com.jellypudding.offlineclient.modules.misc.AutoReconnect;
import com.jellypudding.offlineclient.modules.misc.Proxy;
import com.jellypudding.offlineclient.util.ChatUtil;
import com.jellypudding.offlineclient.util.ClientLines;
import com.jellypudding.offlineclient.util.Modules;
import com.jellypudding.offlineclient.util.ProxyTunnel;
import com.jellypudding.offlineclient.util.ServerInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.server.network.EventLoopGroupHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// The login belongs to the proxy host typed in the Proxy settings. The test works whether or not
// Proxy is switched on.
public final class ProxyCommand extends Command {

    private static final String LOGIN = "login";
    private static final String LOGOUT = "logout";
    private static final String TEST = "test";
    // How long the test waits for the proxy to reach the server.
    private static final int TEST_SECONDS = 10;

    public ProxyCommand() {
        super("proxy", "Saves the proxy login or tests the proxy.",
            "proxy <login name password|logout|test [server]>");
    }

    @Override
    public void execute(String[] args) {
        Proxy proxy = Modules.get(Proxy.class);
        if (args.length == 0 || proxy == null) {
            usage();
            return;
        }
        if (!proxy.hasHost()) {
            ChatUtil.error("Type the proxy host in the Proxy settings first.");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case LOGIN -> login(proxy, args);
            case LOGOUT -> logout(proxy, args);
            case TEST -> test(proxy, args);
            default -> usage();
        }
    }

    private void login(Proxy proxy, String[] args) {
        if (args.length != 3) {
            usage("proxy login <name> <password>");
            return;
        }
        if (tooLong(args[1]) || tooLong(args[2])) {
            ChatUtil.error("A proxy name or password holds at most " + ProxyTunnel.LONGEST_LOGIN + " letters.");
            return;
        }
        proxy.saveLogin(args[1], args[2]);
        forgetTypedLine(args[2]);
        ChatUtil.message("§7Saved the login for §b" + proxy.host() + "§7.");
    }

    private static boolean tooLong(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length > ProxyTunnel.LONGEST_LOGIN;
    }

    private void logout(Proxy proxy, String[] args) {
        if (args.length != 1) {
            usage("proxy logout");
            return;
        }
        if (proxy.forgetLogin()) {
            ChatUtil.message("§7Forgot the login for §b" + proxy.host() + "§7.");
        } else {
            ChatUtil.error("No login is saved for " + proxy.host() + ".");
        }
    }

    // With no server named it tries the one you are on or the last one you joined.
    private void test(Proxy proxy, String[] args) {
        if (args.length > 2) {
            usage("proxy test [server]");
            return;
        }
        String server = args.length == 2 ? args[1]
            : ServerInfo.address() != null ? ServerInfo.address() : AutoReconnect.lastAddress();
        if (server == null) {
            usage("proxy test <server>");
            return;
        }
        ProxyTunnel tunnel = proxy.tunnel();
        ChatUtil.message("§7Testing §b" + proxy.host() + "§7 against §b" + server + "§7.");
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
            return "§bProxy §7reached §f" + server + "§7 in §f" + millis + " ms§7.";
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

    @Override
    public List<String> complete(String[] tokens, int index, String current) {
        return index == 1 ? CommandManager.filter(current, List.of(LOGIN, LOGOUT, TEST)) : List.of();
    }
}
