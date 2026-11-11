package com.jellypudding.offlineclient.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

// Tells an address on your own machine or on the network around it from one out on the internet.
public final class HomeNetwork {

    // Addresses that name this network. Some systems take them to this machine.
    private static final int THIS_NETWORK = 0;

    // The block providers hand out behind their own routers.
    private static final int SHARED_FIRST = 100;
    private static final int SHARED_SECOND = 64;
    private static final int SHARED_MASK = 0xC0;

    // Addresses a site gives out for itself in the newer numbering.
    private static final int UNIQUE_LOCAL = 0xFC;
    private static final int UNIQUE_LOCAL_MASK = 0xFE;

    // Newer addresses that carry an older one right after these first bytes. In order the compatible
    // and mapped and translated forms and the prefix a translating router uses and the six to four
    // form. Such an address leads to the older one inside it.
    private static final byte[][] CARRIERS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF},
        {0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0},
        {0, 0x64, (byte) 0xFF, (byte) 0x9B, 0, 0, 0, 0, 0, 0, 0, 0},
        {0x20, 0x02}};
    private static final int OLDER_BYTES = 4;

    // The name that stands for the machine it is looked up on. Every name under it does too.
    private static final String LOCALHOST = "localhost";

    private HomeNetwork() {
    }

    public static boolean holds(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            return first == THIS_NETWORK || first == SHARED_FIRST && (bytes[1] & SHARED_MASK) == SHARED_SECOND;
        }
        InetAddress older = carried(bytes);
        return older == null ? (bytes[0] & UNIQUE_LOCAL_MASK) == UNIQUE_LOCAL : holds(older);
    }

    // The older address a newer one carries. Null for one that carries none.
    private static InetAddress carried(byte[] bytes) {
        for (byte[] start : CARRIERS) {
            if (Arrays.equals(bytes, 0, start.length, start, 0, start.length)) {
                try {
                    return InetAddress.getByAddress(Arrays.copyOfRange(bytes, start.length,
                        start.length + OLDER_BYTES));
                } catch (UnknownHostException fourBytesAlwaysMakeOne) {
                    return null;
                }
            }
        }
        return null;
    }

    // True for a name that always stands for the machine it is looked up on. Through a proxy that
    // is the machine of the proxy.
    public static boolean namesHome(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.equals(LOCALHOST) || name.endsWith("." + LOCALHOST);
    }

    // Every address a host written out in numbers can stand for. Java reads a part with a leading
    // nought as decimal and refuses one with a leading 0x. A proxy may read them as octal and hex.
    // Empty for a name. Nothing is looked up.
    public static List<InetAddress> literals(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return Stream.of(read(bare, InetAddress::ofLiteral), read(bare, Inet4Address::ofPosixLiteral))
            .filter(Objects::nonNull).distinct().toList();
    }

    private static InetAddress read(String host, Function<String, InetAddress> reading) {
        try {
            return reading.apply(host);
        } catch (IllegalArgumentException named) {
            return null;
        }
    }
}
