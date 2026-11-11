package com.jellypudding.offlineclient.util;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// One request to a pack host on a socket connected here. The host is looked up once and the gate is
// shown the address that came back. The socket then connects to that very address. The request goes
// out byte for byte the way Java's own connection writes it and the answer is read by the rules
// Java reads one by. A server cannot tell the two apart. Redirects are left to whoever asked.
final class WireRequest extends HttpURLConnection {

    private static final String VERSION = "HTTP/1.1";
    private static final String LINE_END = "\r\n";

    // Java's own connection adds these after the headers it was given unless they were among them.
    private static final String JAVA_AGENT = javaAgent();
    private static final String ACCEPT = String.join("/", "*", "*");
    private static final String CONNECTION = Boolean.parseBoolean(System.getProperty("http.keepAlive", "true"))
        ? "keep-alive" : "close";

    // An answer has to start with this for Java to read a head at all.
    private static final byte[] HTTP_ONE = "HTTP/1.".getBytes(StandardCharsets.ISO_8859_1);
    // Java looks at one byte more than the start before it decides.
    private static final int FIRST_LOOK = HTTP_ONE.length + 1;

    // Java stops reading a head once its names and values and thirty two more for each pair run past
    // this.
    private static final long MOST_HEADER_SIZE = 384 * 1024;
    private static final int PAIR_COST = 32;

    // Java reads a head again for every answer that only says the server is busy until its stack
    // runs out. This many is far past any real server.
    private static final int MOST_BUSY_ANSWERS = 1000;

    // Java gives up on a chunk size line longer than this.
    private static final int LONGEST_CHUNK_LINE = 2050;

    private static final int CONTINUE = 100;
    private static final int SWITCHING = 101;
    private static final int LAST_BUSY = 199;
    private static final int GONE = 410;
    private static final int STATUS_DIGITS = 3;

    private final Proxy route;
    private final CheckedDownload.Gate gate;
    // In the order they were set. A name set again keeps its place and its first spelling.
    private final List<String[]> headers = new ArrayList<>();
    // The head of the answer. A line without a name has a null name.
    private final List<String[]> answer = new ArrayList<>();
    private Socket socket;
    private InputStream body;
    private int status = -1;
    // Java asks once and hands back the same failure every time after.
    private IOException failure;

    // The route goes straight out or through a SOCKS proxy. Both connect to the address the gate saw.
    WireRequest(URL url, Proxy route, CheckedDownload.Gate gate) {
        super(url);
        this.route = route;
        this.gate = gate;
    }

    private static String javaAgent() {
        String agent = System.getProperty("http.agent");
        String java = "Java/" + System.getProperty("java.version");
        return agent == null ? java : agent + " " + java;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        if (key.indexOf('\n') >= 0 || key.indexOf(':') >= 0 || value != null && breaksLine(value)) {
            throw new IllegalArgumentException("A line break or a colon in the header " + key);
        }
        String[] set = find(headers, key);
        if (set == null) {
            headers.add(new String[] {key, value});
        } else {
            set[1] = value;
        }
    }

    // A line break is only allowed where the next line carries on the value.
    private static boolean breaksLine(String value) {
        for (int at = value.indexOf('\n'); at >= 0; at = value.indexOf('\n', at + 1)) {
            if (at + 1 >= value.length() || value.charAt(at + 1) != ' ' && value.charAt(at + 1) != '\t') {
                return true;
            }
        }
        return false;
    }

    // The last pair with that name.
    private static String[] find(List<String[]> list, String key) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (key.equalsIgnoreCase(list.get(i)[0])) {
                return list.get(i);
            }
        }
        return null;
    }

    @Override
    public void connect() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (connected) {
            return;
        }
        try {
            exchange();
        } catch (IOException e) {
            failure = e;
            disconnect();
            throw e;
        }
    }

    private void exchange() throws IOException {
        String host = url.getHost();
        int port = url.getPort() == -1 ? url.getDefaultPort() : url.getPort();
        // The same lookup Java makes. A SOCKS proxy is handed the name only when it cannot be found here.
        InetSocketAddress target = new InetSocketAddress(host, port);
        if (!target.isUnresolved()) {
            gate.pass(host, target.getAddress());
        }
        byte[] request = request();
        BufferedInputStream in = send(target, request);
        try {
            readHead(in);
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException e) {
            // Java asks once more on a fresh connection when an answer has no head it can read.
            disconnect();
            in = send(target, request);
            readHead(in);
        }
        status = code(answer.isEmpty() ? null : answer.getFirst()[1]);
        if (status == -1) {
            throw new IOException("Invalid Http response");
        }
        frame(in);
        connected = true;
    }

    private BufferedInputStream send(InetSocketAddress target, byte[] request) throws IOException {
        socket = new Socket(route);
        socket.connect(target, getConnectTimeout());
        socket.setSoTimeout(getReadTimeout());
        if (url.getProtocol().equalsIgnoreCase("https")) {
            socket = secure(socket, url.getHost(), target.getPort());
        }
        OutputStream out = socket.getOutputStream();
        out.write(request);
        out.flush();
        return new BufferedInputStream(socket.getInputStream());
    }

    // The way Java's own connection sets up TLS. The certificate has to name the host.
    private static Socket secure(Socket plain, String host, int port) throws IOException {
        SSLSocket tls = (SSLSocket) HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(plain, host, port,
            true);
        String protocols = System.getProperty("https.protocols");
        if (protocols != null && !protocols.isEmpty()) {
            tls.setEnabledProtocols(protocols.split(","));
        }
        String ciphers = System.getProperty("https.cipherSuites");
        if (ciphers != null && !ciphers.isEmpty()) {
            tls.setEnabledCipherSuites(ciphers.split(","));
        }
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        tls.startHandshake();
        return tls;
    }

    // Header names and values go out as Latin 1 the way Java writes them.
    private byte[] request() throws IOException {
        List<String[]> all = new ArrayList<>(headers);
        addIfMissing(all, "User-Agent", JAVA_AGENT);
        addIfMissing(all, "Host", hostField());
        addIfMissing(all, "Accept", ACCEPT);
        addIfMissing(all, "Connection", CONNECTION);
        StringBuilder text = new StringBuilder("GET ").append(target()).append(' ').append(VERSION)
            .append(LINE_END);
        for (String[] header : all) {
            text.append(header[0]).append(header[1] == null ? ":" : ": " + header[1]).append(LINE_END);
        }
        return text.append(LINE_END).toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void addIfMissing(List<String[]> all, String key, String value) {
        if (find(all, key) == null) {
            all.add(new String[] {key, value});
        }
    }

    // The path and query. An empty path asks for the root.
    private String target() throws MalformedURLException {
        String file = url.getFile();
        if (file.isEmpty() || file.charAt(0) == '?') {
            file = "/" + file;
        }
        if (file.indexOf('\n') >= 0) {
            throw new MalformedURLException("Illegal character in URL");
        }
        return file;
    }

    // The host with its port when the port is not the usual one. A zone on an IPv6 address stays here.
    private String hostField() {
        String host = url.getHost();
        int zone = host.lastIndexOf('%');
        if (host.startsWith("[") && zone >= 0) {
            host = host.substring(0, zone) + "]";
        }
        int port = url.getPort();
        return port != -1 && port != url.getDefaultPort() ? host + ":" + port : host;
    }

    // Answers that only say the server is still busy are read past. An answer that does not start
    // the way Java expects has no head and Java refuses it once the code turns out unreadable.
    private void readHead(BufferedInputStream in) throws IOException {
        for (int busy = 0; busy <= MOST_BUSY_ANSWERS; busy++) {
            answer.clear();
            in.mark(FIRST_LOOK);
            byte[] start = in.readNBytes(FIRST_LOOK);
            in.reset();
            if (!startsLikeHttp(start)) {
                if (start.length != FIRST_LOOK) {
                    throw new SocketException("Unexpected end of file from server");
                }
                return;
            }
            readPairs(in);
            int early = busyCode(answer.getFirst()[1]);
            if (early == SWITCHING) {
                throw new ProtocolException("Unexpected 101 response from server");
            }
            if (early < CONTINUE || early > LAST_BUSY) {
                return;
            }
        }
        throw new ProtocolException("The server only ever said it was busy");
    }

    private static boolean startsLikeHttp(byte[] start) {
        if (start.length < HTTP_ONE.length) {
            return false;
        }
        for (int i = 0; i < HTTP_ONE.length; i++) {
            if (start[i] != HTTP_ONE[i]) {
                return false;
            }
        }
        return true;
    }

    // The lines of a head split the way Java splits them. A line break followed by a space or any
    // other control character carries the value on and a tab reads as a space. A name ends at the
    // first colon unless a space comes first. Then the whole line is a value without a name. A head
    // cut short by the end of the answer still counts.
    private void readPairs(InputStream in) throws IOException {
        long size = 0;
        int first = in.read();
        while (first != '\n' && first != '\r' && first >= 0) {
            StringBuilder line = new StringBuilder();
            line.append((char) first);
            checkSize(size + line.length() + PAIR_COST);
            boolean inName = first > ' ';
            int nameEnd = -1;
            first = -1;
            for (int c = in.read(); c >= 0; c = in.read()) {
                if (c == ':') {
                    if (inName && !line.isEmpty()) {
                        nameEnd = line.length();
                    }
                    inName = false;
                } else if (c == ' ' || c == '\t') {
                    c = ' ';
                    inName = false;
                } else if (c == '\r' || c == '\n') {
                    first = in.read();
                    if (c == '\r' && first == '\n') {
                        first = in.read();
                        if (first == '\r') {
                            first = in.read();
                        }
                    }
                    if (first == '\n' || first == '\r' || first > ' ') {
                        break;
                    }
                    first = -1;
                    c = ' ';
                }
                line.append((char) c);
                checkSize(size + line.length() + PAIR_COST);
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) <= ' ') {
                end--;
            }
            String text = line.substring(0, end);
            String name = nameEnd > 0 ? text.substring(0, nameEnd) : null;
            String value = name == null ? text : text.substring(nameEnd + 1).trim();
            size += (name == null ? 0 : name.length()) + value.length() + PAIR_COST;
            checkSize(size);
            answer.add(new String[] {name, value});
        }
    }

    private static void checkSize(long size) throws ProtocolException {
        if (size > MOST_HEADER_SIZE) {
            throw new ProtocolException("Header size too big");
        }
    }

    // Java tells a busy answer by the three digits after the spaces that follow the version.
    private static int busyCode(String line) {
        int at = line.indexOf(' ');
        if (at < 0) {
            return -1;
        }
        while (at < line.length() && line.charAt(at) == ' ') {
            at++;
        }
        try {
            return Integer.parseInt(line, at, at + STATUS_DIGITS, 10);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // The code Java hands its caller. It reads from the first space to the next one. Minus one for a
    // line it cannot read.
    private static int code(String line) {
        if (line == null || !line.startsWith("HTTP/1.")) {
            return -1;
        }
        int at = line.indexOf(' ');
        if (at <= 0) {
            return -1;
        }
        int end = line.indexOf(' ', at + 1);
        try {
            return Integer.parseInt(line, at + 1, end < 0 ? line.length() : end, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // A chunked body goes by its pieces. Otherwise a length above nought is held to and anything else
    // runs to the end of the answer. Java hands back nothing at all for a length of nought and for an
    // answer that has no body.
    private void frame(InputStream in) {
        long length = getHeaderFieldLong("Content-Length", -1);
        String encoding = getHeaderField("Transfer-Encoding");
        boolean empty = length == 0 || status == HTTP_NO_CONTENT || status == HTTP_NOT_MODIFIED;
        if (empty) {
            body = InputStream.nullInputStream();
            disconnect();
        } else if (encoding != null && encoding.equalsIgnoreCase("chunked")) {
            body = new Chunked(in);
        } else {
            body = length > 0 ? new Measured(in, length) : in;
        }
    }

    @Override
    public int getResponseCode() throws IOException {
        connect();
        return status;
    }

    @Override
    public String getHeaderField(String name) {
        String[] header = find(answer, name);
        return header == null ? null : header[1];
    }

    // Java throws for an answer that failed and leaves its words to the error stream.
    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        if (status == HTTP_NOT_FOUND || status == GONE) {
            throw new FileNotFoundException(url.toString());
        }
        if (status >= HTTP_BAD_REQUEST) {
            throw new IOException("The server answered " + status + " for " + url);
        }
        return closing(body);
    }

    // An empty answer has already let its connection go and leaves no error stream.
    @Override
    public InputStream getErrorStream() {
        return connected && status >= HTTP_BAD_REQUEST && !socket.isClosed() ? closing(body) : null;
    }

    // Closing what was read closes the socket. Reading it to the end does too.
    private InputStream closing(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                return ended(super.read());
            }

            @Override
            public int read(byte[] into, int from, int length) throws IOException {
                return ended(super.read(into, from, length));
            }

            private int ended(int read) {
                if (read < 0) {
                    disconnect();
                }
                return read;
            }

            @Override
            public void close() {
                disconnect();
            }
        };
    }

    @Override
    public void disconnect() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // A socket that will not close has nothing left to send.
        }
    }

    @Override
    public boolean usingProxy() {
        return false;
    }

    // A body of a known length. A read is not cut at the length but nothing is read after it. An
    // answer that stops short of the length fails the way Java fails it.
    private static final class Measured extends FilterInputStream {

        private final long expected;
        private long count;
        private boolean done;

        private Measured(InputStream in, long expected) {
            super(in);
            this.expected = expected;
        }

        @Override
        public int read() throws IOException {
            if (done) {
                return -1;
            }
            int read = in.read();
            counted(read < 0 ? -1 : 1);
            return read;
        }

        @Override
        public int read(byte[] into, int from, int length) throws IOException {
            if (done) {
                return -1;
            }
            int read = in.read(into, from, length);
            counted(read);
            return read;
        }

        private void counted(int read) throws IOException {
            if (read < 0) {
                if (count < expected) {
                    throw new IOException("Premature EOF");
                }
                done = true;
                return;
            }
            count += read;
            done = count >= expected;
        }

        @Override
        public int available() throws IOException {
            return done ? 0 : in.available();
        }
    }

    // A body sent in pieces. Each piece is led by its size in hex and the last has size nought.
    private static final class Chunked extends FilterInputStream {

        private long left;
        private boolean ended;

        private Chunked(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] into, int from, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (left == 0 && !ended) {
                nextPiece();
            }
            if (ended) {
                return -1;
            }
            int read = in.read(into, from, (int) Math.min(length, left));
            if (read < 0) {
                throw new IOException("Premature EOF");
            }
            left -= read;
            if (left == 0 && !line(in).isEmpty()) {
                throw new IOException("Missing chunk end");
            }
            return read;
        }

        // The trailer lines after the last piece are read and thrown away.
        private void nextPiece() throws IOException {
            String line = line(in);
            int extension = line.indexOf(';');
            String size = (extension < 0 ? line : line.substring(0, extension)).trim();
            try {
                left = Long.parseLong(size, 16);
            } catch (NumberFormatException e) {
                throw new IOException("Bogus chunk size");
            }
            if (left < 0) {
                throw new IOException("Bogus chunk size");
            }
            if (left == 0) {
                ended = true;
                long trailers = 0;
                for (String trailer = line(in); !trailer.isEmpty(); trailer = line(in)) {
                    trailers += trailer.length() + PAIR_COST;
                    if (trailers > MOST_HEADER_SIZE) {
                        throw new ProtocolException("Trailer size too big");
                    }
                }
            }
        }

        // A line of a chunked body without its ending.
        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            for (int read = in.read(); read != '\n'; read = in.read()) {
                if (read < 0) {
                    throw new IOException("Premature EOF");
                }
                if (line.size() >= LONGEST_CHUNK_LINE) {
                    throw new IOException("Chunk header too long");
                }
                line.write(read);
            }
            String text = line.toString(StandardCharsets.ISO_8859_1);
            return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
        }

        @Override
        public int available() throws IOException {
            return ended ? 0 : (int) Math.min(left, in.available());
        }
    }
}
