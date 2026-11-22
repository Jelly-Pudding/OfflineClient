package com.jellypudding.offlineclient.rig.duo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// A SOCKS5 proxy on this machine alone. It takes no login and carries a connection to wherever
// the client asks which is the rig's own server. It counts what it carried.
public final class LocalSocks implements AutoCloseable {

    private static final int VERSION = 5;
    private static final int CONNECT = 1;
    private static final int NO_LOGIN = 0;
    private static final int IPV4 = 1;
    private static final int NAME = 3;
    private static final int IPV6 = 4;
    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int BUFFER = 8192;
    private static final byte[] GRANTED = {VERSION, 0, 0, IPV4, 0, 0, 0, 0, 0, 0};

    private final ServerSocket door;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private final AtomicInteger carried = new AtomicInteger();

    private LocalSocks(ServerSocket door) {
        this.door = door;
    }

    public static LocalSocks open() throws IOException {
        LocalSocks socks = new LocalSocks(new ServerSocket(0, 0, InetAddress.getLoopbackAddress()));
        daemon(socks::accept, "rig socks door");
        return socks;
    }

    public int port() {
        return door.getLocalPort();
    }

    public int carried() {
        return carried.get();
    }

    private void accept() {
        while (!door.isClosed()) {
            try {
                Socket client = door.accept();
                open.add(client);
                daemon(() -> serve(client), "rig socks client");
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try {
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            in.readUnsignedByte();
            in.skipNBytes(in.readUnsignedByte());
            out.write(new byte[] {VERSION, NO_LOGIN});
            in.readUnsignedByte();
            int command = in.readUnsignedByte();
            in.readUnsignedByte();
            InetAddress host = address(in);
            int port = in.readUnsignedShort();
            if (command != CONNECT) {
                client.close();
                return;
            }
            Socket target = new Socket(host, port);
            open.add(target);
            out.write(GRANTED);
            carried.incrementAndGet();
            daemon(() -> pump(target, client), "rig socks back");
            pump(client, target);
        } catch (IOException gone) {
            shut(client);
        }
    }

    private static InetAddress address(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        if (kind == NAME) {
            return InetAddress.getByName(new String(in.readNBytes(in.readUnsignedByte())));
        }
        if (kind != IPV4 && kind != IPV6) {
            throw new IOException("An address of kind " + kind);
        }
        return InetAddress.getByAddress(in.readNBytes(kind == IPV4 ? IPV4_BYTES : IPV6_BYTES));
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[BUFFER];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException gone) {
            // One side hung up. The other is shut with it.
        }
        shut(from);
        shut(to);
    }

    private static void shut(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closed already.
        }
    }

    private static void daemon(Runnable work, String name) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void close() throws IOException {
        door.close();
        open.forEach(LocalSocks::shut);
    }
}
