package com.jellypudding.offlineclient.config;

import com.google.common.net.InetAddresses;
import com.jellypudding.offlineclient.util.ProxyTunnel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// Reads a proxy out of the words a player types or a line of a proxy list.
public final class ProxyText {

    // A proxy as typed or read from a list. The name is null when none was given and the login is
    // null when it carries none.
    public record Typed(String name, ProxyTunnel.Kind kind, String host, int port, ProxyTunnel.Login login) {

        public String address() {
            return ProxyText.address(host, port);
        }

        // The name it is saved under. Without one given it is saved under its address.
        public String savedName() {
            return name == null ? address() : name;
        }
    }

    public static final ProxyTunnel.Kind DEFAULT_KIND = ProxyTunnel.Kind.SOCKS_5;

    // Words for each kind the way proxy lists and links write them. A list that says https means
    // a web proxy that allows tunnels.
    static final Map<String, ProxyTunnel.Kind> KIND_WORDS = Map.of(
        "socks5", ProxyTunnel.Kind.SOCKS_5, "socks5h", ProxyTunnel.Kind.SOCKS_5,
        "socks4", ProxyTunnel.Kind.SOCKS_4, "socks4a", ProxyTunnel.Kind.SOCKS_4,
        "http", ProxyTunnel.Kind.HTTP_TUNNEL, "https", ProxyTunnel.Kind.HTTP_TUNNEL);
    static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int LAST_PORT = 65535;
    private static final int LONGEST_PORT = 5;
    // No host name is longer.
    private static final int LONGEST_HOST = 253;
    // Room for any host and port with a number after them.
    private static final int LONGEST_NAME = 280;
    private static final String SCHEME = "://";
    private static final Pattern HOST_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private ProxyText() {
    }

    // A proxy in words. A name may come first and a kind word may stand before or after the one
    // address. The address is a host and port in any of the forms proxy lists use with a login
    // before the host or after the port and a kind in front. Null when the words hold no proxy.
    public static Typed typed(String text, ProxyTunnel.Kind kind) {
        List<String> words = new ArrayList<>(List.of(WHITESPACE.split(text.strip())));
        for (int i = 0; i < words.size() && words.size() > 1; i++) {
            ProxyTunnel.Kind named = KIND_WORDS.get(words.get(i).toLowerCase(Locale.ROOT));
            if (named != null) {
                kind = named;
                words.remove(i);
                break;
            }
        }
        if (words.size() > 2 || words.getLast().isEmpty()) {
            return null;
        }
        String name = words.size() == 2 ? words.getFirst() : null;
        return name == null || validName(name) ? address(words.getLast(), kind, name) : null;
    }

    private static Typed address(String text, ProxyTunnel.Kind kind, String name) {
        String rest = text;
        int scheme = rest.indexOf(SCHEME);
        if (scheme >= 0) {
            kind = KIND_WORDS.get(rest.substring(0, scheme).toLowerCase(Locale.ROOT));
            rest = rest.substring(scheme + SCHEME.length());
        }
        if (kind == null) {
            return null;
        }
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        ProxyTunnel.Login login = null;
        int at = rest.lastIndexOf('@');
        if (at >= 0) {
            login = login(rest.substring(0, at));
            rest = rest.substring(at + 1);
            if (login == null) {
                return null;
            }
        }
        String host;
        String after;
        // Only an IPv6 address goes in brackets.
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0 || !rest.startsWith(":", close + 1) || rest.substring(1, close).indexOf(':') < 0) {
                return null;
            }
            host = rest.substring(1, close);
            after = rest.substring(close + 2);
        } else {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                return null;
            }
            host = rest.substring(0, colon);
            after = rest.substring(colon + 1);
        }
        int colon = after.indexOf(':');
        int port = port(colon < 0 ? after : after.substring(0, colon));
        if (colon >= 0) {
            if (login != null) {
                return null;
            }
            login = login(after.substring(colon + 1));
        }
        if (port < 0 || !validHost(host) || colon >= 0 && login == null) {
            return null;
        }
        return new Typed(name, kind, host, port, login);
    }

    // A name and password with a colon between. The password may hold colons of its own.
    private static ProxyTunnel.Login login(String text) {
        int colon = text.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String name = text.substring(0, colon);
        String password = text.substring(colon + 1);
        return fitsLogin(name) && fitsLogin(password) ? new ProxyTunnel.Login(name, password) : null;
    }

    private static boolean fitsLogin(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length <= ProxyTunnel.LONGEST_LOGIN;
    }

    // Minus one for anything but a port number.
    private static int port(String text) {
        if (text.isEmpty() || text.length() > LONGEST_PORT || !text.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        int port = Integer.parseInt(text);
        return validPort(port) ? port : -1;
    }

    static boolean validPort(int port) {
        return port >= 1 && port <= LAST_PORT;
    }

    static boolean validHost(String host) {
        if (host.isEmpty() || host.length() > LONGEST_HOST) {
            return false;
        }
        return host.indexOf(':') >= 0 ? InetAddresses.isInetAddress(host) : HOST_NAME.matcher(host).matches();
    }

    static String address(String host, int port) {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    static boolean validName(String name) {
        return !name.isEmpty() && name.length() <= LONGEST_NAME && name.codePoints().noneMatch(Character::isWhitespace);
    }
}
