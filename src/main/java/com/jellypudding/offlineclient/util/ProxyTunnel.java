package com.jellypudding.offlineclient.util;

import com.jellypudding.offlineclient.OfflineClient;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingWriteQueue;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A tunnel to a server through a SOCKS or HTTP proxy. It sits first on a connection and dials the
// proxy in place of the server. The game sees the connection open at once and whatever it writes
// waits here until the proxy has reached the server. The tunnel then steps out of the way.
public final class ProxyTunnel extends ChannelDuplexHandler {

    // The labels are written the way proxy services write them.
    public enum Kind {
        SOCKS_5("SOCKS5"), SOCKS_4("SOCKS4"), HTTP_TUNNEL("HTTP");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // What the proxy asks for before it lets you through. It is saved as the name and the password
    // on two lines.
    public record Login(String name, String password) {

        public static Login parse(String text) {
            int split = text.indexOf('\n');
            return split < 0 ? new Login(text, "") : new Login(text.substring(0, split), text.substring(split + 1));
        }

        public String text() {
            return name + "\n" + password;
        }
    }

    private enum Step { DIALLING, GREETING, LOGIN, CONNECT, OPEN, FAILED }

    public static final String NAME = "offlineclient_proxy";

    // The longest name or password a SOCKS5 login can carry.
    public static final int LONGEST_LOGIN = 255;

    private static final int SOCKS5 = 5;
    private static final int SOCKS4 = 4;
    private static final int CONNECT = 1;
    private static final int RESERVED = 0;
    private static final int NO_LOGIN = 0;
    private static final int NAME_AND_PASSWORD = 2;
    private static final int LOGIN_VERSION = 1;
    private static final int SUCCESS = 0;
    private static final int IPV4 = 1;
    private static final int IPV6 = 4;
    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int PORT_BYTES = 2;
    // A SOCKS4 name ends with a zero byte.
    private static final int NAME_END = 0;
    // The greeting and the login are each answered with a version byte and a status byte.
    private static final int SHORT_ANSWER = 2;
    // Where the status sits in every answer.
    private static final int STATUS_AT = 1;
    // A SOCKS5 answer holds the version and the status and a spare byte and the address kind ahead
    // of the address itself.
    private static final int SOCKS5_HEAD = 4;
    private static final int ADDRESS_KIND_AT = 3;
    private static final int SOCKS5_NOT_ALLOWED = 2;
    private static final int SOCKS5_REFUSED = 5;
    private static final int SOCKS4_ANSWER = 8;
    private static final int SOCKS4_GRANTED = 0x5A;
    private static final int HTTP_OK = 200;
    private static final int HTTP_LOGIN = 407;
    private static final String HEADER_END = "\r\n\r\n";
    private static final Pattern HTTP_STATUS = Pattern.compile("HTTP/\\S+ +(\\d{3})");
    // An HTTP answer this long without an end is no proxy answer.
    private static final int LONGEST_ANSWER = 8192;

    private final Kind kind;
    private final String host;
    private final int port;
    private final Login login;
    private final CompletableFuture<Void> done = new CompletableFuture<>();

    private InetSocketAddress target;
    private PendingWriteQueue held;
    private ByteBuf answer;
    private Step step = Step.DIALLING;

    public ProxyTunnel(Kind kind, String host, int port, Login login) {
        this.kind = kind;
        this.host = host;
        this.port = port;
        this.login = login;
    }

    // Finishes once the proxy has reached the server or fails with what went wrong.
    public CompletableFuture<Void> done() {
        return done;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        held = new PendingWriteQueue(ctx);
        answer = ctx.alloc().buffer();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        held.removeAndFailAll(new IOException("The proxy tunnel closed."));
        if (answer != null) {
            answer.release();
            answer = null;
        }
    }

    // The proxy is dialled in place of the server once its address is found. A connection that
    // cannot go through it fails before anything is sent.
    @Override
    public void connect(ChannelHandlerContext ctx, SocketAddress remote, SocketAddress local, ChannelPromise promise) {
        target = (InetSocketAddress) remote;
        promise.addListener(dialled -> {
            if (!dialled.isSuccess()) {
                done.completeExceptionally(dialled.cause());
            }
        });
        String refusal = refusal();
        if (refusal != null) {
            promise.tryFailure(new IOException(refusal));
            return;
        }
        // A name lookup can take seconds and this thread carries other connections meanwhile.
        Thread.ofVirtual().name("OfflineClient proxy lookup").start(() -> {
            InetSocketAddress proxy = new InetSocketAddress(host, port);
            ctx.executor().execute(() -> {
                if (proxy.isUnresolved()) {
                    promise.tryFailure(new UnknownHostException("Cannot find the proxy " + host + "."));
                } else {
                    ctx.connect(proxy, local, promise);
                }
            });
        });
    }

    // Why this connection cannot go through the proxy. Null when it can.
    private String refusal() {
        if (host.isEmpty()) {
            return "The proxy has no host. Type it in the Proxy settings.";
        }
        if (kind == Kind.SOCKS_4 && !(target.getAddress() instanceof Inet4Address)) {
            return "A SOCKS4 proxy only reaches IPv4 addresses.";
        }
        return null;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (kind == Kind.SOCKS_5) {
            send(ctx, Step.GREETING, this::writeGreeting);
        } else {
            send(ctx, Step.CONNECT, this::writeConnect);
        }
        ctx.fireChannelActive();
    }

    // Writes held after a failure are dropped with the tunnel as the connection closes.
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (step == Step.OPEN) {
            ctx.write(msg, promise);
        } else {
            held.add(msg, promise);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        if (step == Step.OPEN) {
            ctx.flush();
        }
    }

    // The proxy answers one request at a time. Bytes behind its last answer belong to the server.
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf bytes = (ByteBuf) msg;
        answer.writeBytes(bytes);
        bytes.release();
        switch (step) {
            case GREETING -> readGreeting(ctx);
            case LOGIN -> readLogin(ctx);
            case CONNECT -> readConnect(ctx);
            default -> answer.clear();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        done.completeExceptionally(new IOException("The proxy closed the connection."));
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        done.completeExceptionally(cause);
        ctx.fireExceptionCaught(cause);
    }

    // SOCKS5 opens by offering ways to log in. A login is only offered once one is saved.
    private void writeGreeting(ByteBuf out) {
        byte[] ways = login == null ? new byte[] {NO_LOGIN} : new byte[] {NO_LOGIN, NAME_AND_PASSWORD};
        out.writeByte(SOCKS5);
        out.writeByte(ways.length);
        out.writeBytes(ways);
    }

    private void readGreeting(ChannelHandlerContext ctx) {
        if (answer.readableBytes() < SHORT_ANSWER) {
            return;
        }
        int version = byteAt(0);
        int way = byteAt(STATUS_AT);
        answer.skipBytes(SHORT_ANSWER);
        if (version != SOCKS5) {
            fail(ctx, "The proxy did not answer as a SOCKS5 proxy.");
        } else if (way == NO_LOGIN) {
            send(ctx, Step.CONNECT, this::writeConnect);
        } else if (way == NAME_AND_PASSWORD && login != null) {
            send(ctx, Step.LOGIN, this::writeLogin);
        } else {
            fail(ctx, login == null ? wantsLogin() : "The proxy takes none of the logins on offer.");
        }
    }

    private void writeLogin(ByteBuf out) {
        out.writeByte(LOGIN_VERSION);
        writeShortText(out, login.name());
        writeShortText(out, login.password());
    }

    private void readLogin(ChannelHandlerContext ctx) {
        if (answer.readableBytes() < SHORT_ANSWER) {
            return;
        }
        int status = byteAt(STATUS_AT);
        answer.skipBytes(SHORT_ANSWER);
        if (status == SUCCESS) {
            send(ctx, Step.CONNECT, this::writeConnect);
        } else {
            fail(ctx, "The proxy turned the login down.");
        }
    }

    // The request that asks the proxy to reach the server.
    private void writeConnect(ByteBuf out) {
        switch (kind) {
            case SOCKS_5 -> writeSocks5(out);
            case SOCKS_4 -> writeSocks4(out);
            case HTTP_TUNNEL -> writeHttp(out);
        }
    }

    private void readConnect(ChannelHandlerContext ctx) {
        switch (kind) {
            case SOCKS_5 -> readSocks5(ctx);
            case SOCKS_4 -> readSocks4(ctx);
            case HTTP_TUNNEL -> readHttp(ctx);
        }
    }

    private void writeSocks5(ByteBuf out) {
        byte[] address = target.getAddress().getAddress();
        out.writeByte(SOCKS5);
        out.writeByte(CONNECT);
        out.writeByte(RESERVED);
        out.writeByte(address.length == IPV4_BYTES ? IPV4 : IPV6);
        out.writeBytes(address);
        out.writeShort(target.getPort());
    }

    // The proxy answers with the address it reached the server from. That address can be a name.
    private void readSocks5(ChannelHandlerContext ctx) {
        if (answer.readableBytes() <= SOCKS5_HEAD) {
            return;
        }
        int addressLength = switch (byteAt(ADDRESS_KIND_AT)) {
            case IPV4 -> IPV4_BYTES;
            case IPV6 -> IPV6_BYTES;
            default -> 1 + byteAt(SOCKS5_HEAD);
        };
        int length = SOCKS5_HEAD + addressLength + PORT_BYTES;
        if (answer.readableBytes() < length) {
            return;
        }
        int status = byteAt(STATUS_AT);
        answer.skipBytes(length);
        if (status == SUCCESS) {
            open(ctx);
        } else {
            fail(ctx, switch (status) {
                case SOCKS5_NOT_ALLOWED -> "The proxy does not allow that server.";
                case SOCKS5_REFUSED -> "The server turned the proxy away.";
                default -> "The proxy could not reach the server.";
            });
        }
    }

    // Connect has made sure the server has an IPv4 address.
    private void writeSocks4(ByteBuf out) {
        out.writeByte(SOCKS4);
        out.writeByte(CONNECT);
        out.writeShort(target.getPort());
        out.writeBytes(target.getAddress().getAddress());
        if (login != null) {
            out.writeCharSequence(login.name(), StandardCharsets.UTF_8);
        }
        out.writeByte(NAME_END);
    }

    private void readSocks4(ChannelHandlerContext ctx) {
        if (answer.readableBytes() < SOCKS4_ANSWER) {
            return;
        }
        int status = byteAt(STATUS_AT);
        answer.skipBytes(SOCKS4_ANSWER);
        if (status == SOCKS4_GRANTED) {
            open(ctx);
        } else {
            fail(ctx, "The proxy refused the connection.");
        }
    }

    private void writeHttp(ByteBuf out) {
        InetAddress address = target.getAddress();
        String where = address instanceof Inet6Address ? "[" + address.getHostAddress() + "]" : address.getHostAddress();
        String authority = where + ":" + target.getPort();
        StringBuilder request = new StringBuilder("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            .append("Host: ").append(authority).append("\r\n");
        if (login != null) {
            String pair = login.name() + ":" + login.password();
            request.append("Proxy-Authorization: Basic ")
                .append(Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        }
        out.writeCharSequence(request.append("\r\n"), StandardCharsets.US_ASCII);
    }

    // Each byte of the answer reads as one letter. The header ends at the first blank line.
    private void readHttp(ChannelHandlerContext ctx) {
        String text = answer.toString(StandardCharsets.US_ASCII);
        int end = text.indexOf(HEADER_END);
        if (end < 0) {
            if (text.length() > LONGEST_ANSWER) {
                fail(ctx, "The proxy sent an answer too long to read.");
            }
            return;
        }
        answer.skipBytes(end + HEADER_END.length());
        String status = text.lines().findFirst().orElse("");
        Matcher matcher = HTTP_STATUS.matcher(status);
        int code = matcher.lookingAt() ? Integer.parseInt(matcher.group(1)) : -1;
        if (code == HTTP_OK) {
            open(ctx);
        } else if (code == HTTP_LOGIN) {
            fail(ctx, login == null ? wantsLogin() : "The proxy turned the login down.");
        } else {
            fail(ctx, "The proxy refused the tunnel with " + status + ".");
        }
    }

    private void send(ChannelHandlerContext ctx, Step next, Consumer<ByteBuf> request) {
        step = next;
        ByteBuf out = ctx.alloc().buffer();
        request.accept(out);
        ctx.writeAndFlush(out);
    }

    // The game takes over the connection. What it wrote meanwhile goes out first and anything the
    // server sent behind the proxy answer reaches it.
    private void open(ChannelHandlerContext ctx) {
        step = Step.OPEN;
        held.removeAndWriteAll();
        ctx.flush();
        ByteBuf rest = answer;
        answer = null;
        if (rest.isReadable()) {
            ctx.fireChannelRead(rest);
        } else {
            rest.release();
        }
        ctx.pipeline().remove(this);
        done.complete(null);
    }

    // The game shows the reason on the disconnected screen. Without the game on this connection it
    // simply closes.
    private void fail(ChannelHandlerContext ctx, String reason) {
        step = Step.FAILED;
        IOException problem = new IOException(reason);
        done.completeExceptionally(problem);
        held.removeAndFailAll(problem);
        Connection connection = ctx.pipeline().get(Connection.class);
        if (connection != null && connection.isConnected()) {
            connection.disconnect(Component.literal(reason));
        } else {
            ctx.close();
        }
    }

    private int byteAt(int offset) {
        return answer.getUnsignedByte(answer.readerIndex() + offset);
    }

    private static void writeShortText(ByteBuf out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeByte(bytes.length);
        out.writeBytes(bytes);
    }

    private static String wantsLogin() {
        return "The proxy wants a login. Type " + OfflineClient.INSTANCE.getCommandManager().getPrefix()
            + "proxy login <name> <password> to save one.";
    }
}
