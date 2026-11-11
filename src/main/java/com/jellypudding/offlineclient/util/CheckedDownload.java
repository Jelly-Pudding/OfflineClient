package com.jellypudding.offlineclient.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

// A download that never connects where the gate refuses. Java follows a redirect wherever it leads
// without a word. This one follows each hop by hand under the rules Java keeps and shows the gate
// where the hop goes first. Straight out or through a SOCKS proxy the gate sees the very address the
// hop connects to. An HTTP proxy looks names up itself and only an address written out in numbers
// or a name for the machine itself can be judged then.
public final class CheckedDownload extends HttpURLConnection {

    // Throws to keep the download away from an address.
    public interface Gate {
        void pass(String host, InetAddress address) throws IOException;
    }

    // As many requests as Java makes before it gives up on a chain of redirects.
    private static final int MOST_REQUESTS = 20;

    private static final int FIRST_REDIRECT = HTTP_MULT_CHOICE;
    private static final int LAST_REDIRECT = 307;
    private static final int UNUSED_STATUS = 306;
    private static final String LOCATION = "Location";

    private final Proxy route;
    private final Gate gate;
    private final Map<String, String> headers = new LinkedHashMap<>();
    // The connection of the hop that answered. Null until a hop has been opened.
    private HttpURLConnection hop;
    // A download that failed fails the same way every time it is asked again.
    private IOException failure;

    public CheckedDownload(URL url, Proxy route, Gate gate) {
        super(url);
        this.route = route;
        this.gate = gate;
    }

    @Override
    public void setRequestProperty(String key, String value) {
        headers.put(key, value);
    }

    @Override
    public void connect() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (hop != null) {
            return;
        }
        try {
            follow();
        } catch (IOException e) {
            failure = e;
            throw e;
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return hop.getInputStream();
    }

    private void follow() throws IOException {
        URL at = url;
        for (int request = 0; request < MOST_REQUESTS; request++) {
            hop = open(at);
            hop.setConnectTimeout(getConnectTimeout());
            hop.setReadTimeout(getReadTimeout());
            hop.setInstanceFollowRedirects(false);
            headers.forEach(hop::setRequestProperty);
            URL next = redirect(at);
            if (next == null) {
                return;
            }
            hop.disconnect();
            at = next;
        }
        throw new ProtocolException("Server redirected too many times (" + MOST_REQUESTS + ")");
    }

    // A name for the machine itself is judged before any route. A proxy would look it up on its own.
    private HttpURLConnection open(URL at) throws IOException {
        String host = at.getHost();
        if (HomeNetwork.namesHome(host)) {
            gate.pass(host, InetAddress.getLoopbackAddress());
        }
        if (route.type() != Proxy.Type.HTTP) {
            return new WireRequest(at, route, gate);
        }
        for (InetAddress literal : HomeNetwork.literals(host)) {
            gate.pass(host, literal);
        }
        return (HttpURLConnection) at.openConnection(route);
    }

    // Where the answer sends the download on. Null for an answer to keep. Java stays with an
    // answer that changes between http and https. Being told to go through a proxy the server
    // names is never obeyed here.
    @SuppressWarnings("deprecation")
    private URL redirect(URL from) throws IOException {
        int status = hop.getResponseCode();
        if (status < FIRST_REDIRECT || status > LAST_REDIRECT || status == UNUSED_STATUS
            || status == HTTP_NOT_MODIFIED || status == HTTP_USE_PROXY) {
            return null;
        }
        String location = hop.getHeaderField(LOCATION);
        if (location == null) {
            return null;
        }
        try {
            URL next = new URL(location);
            return from.getProtocol().equalsIgnoreCase(next.getProtocol()) ? next : null;
        } catch (MalformedURLException relative) {
            return new URL(from, location);
        }
    }

    @Override
    public int getResponseCode() throws IOException {
        connect();
        return hop.getResponseCode();
    }

    @Override
    public String getHeaderField(String name) {
        return hop == null ? null : hop.getHeaderField(name);
    }

    @Override
    public InputStream getErrorStream() {
        return hop == null ? null : hop.getErrorStream();
    }

    @Override
    public void disconnect() {
        if (hop != null) {
            hop.disconnect();
        }
    }

    @Override
    public boolean usingProxy() {
        return route.type() != Proxy.Type.DIRECT;
    }
}
