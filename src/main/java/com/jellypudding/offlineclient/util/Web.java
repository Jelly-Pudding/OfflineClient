package com.jellypudding.offlineclient.util;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.UnaryOperator;

// Requests to the web made away from the game thread. Every caller shares one client and
// the work runs on a virtual thread of its own.
public final class Web {

    // What came back. A request that got no answer has the status minus one and a problem
    // that says why. The problem is null whenever an answer came.
    public record Page(int status, String body, String problem) {

        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private static final int NO_ANSWER = -1;

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    // Some sites turn away a request that does not look like it came from a browser.
    private static final String BROWSER = "Mozilla/5.0";

    private static final String NOT_AN_ADDRESS = "That is not a web address.";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(TIMEOUT)
        .build();

    private Web() {
    }

    // Runs the work on a virtual thread named after it.
    public static void later(String name, Runnable work) {
        Thread.ofVirtual().name("offlineclient " + name).start(work);
    }

    // Waits for the page. Only ever called from a worker thread.
    public static Page get(String address) {
        return send(address, TIMEOUT, HttpRequest.Builder::GET);
    }

    // Sends a JSON body and waits for the answer. Only ever called from a worker thread.
    public static Page postJson(String address, String json) {
        return postJson(address, json, null, TIMEOUT);
    }

    // The same with a bearer key when one is given and a wait of the caller's choosing.
    public static Page postJson(String address, String json, String key, Duration wait) {
        return send(address, wait, request -> {
            if (key != null) {
                request.header("Authorization", "Bearer " + key);
            }
            return request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        });
    }

    // The request builder turns away anything but an http or https address with a host.
    private static Page send(String address, Duration wait, UnaryOperator<HttpRequest.Builder> shape) {
        HttpRequest request;
        try {
            request = shape.apply(HttpRequest.newBuilder(URI.create(address.trim()))
                .timeout(wait).header("User-Agent", BROWSER)).build();
        } catch (IllegalArgumentException e) {
            return failed(NOT_AN_ADDRESS);
        }
        try {
            HttpResponse<String> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Page(response.statusCode(), response.body(), null);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return failed("Nothing answers at that address.");
        } catch (HttpTimeoutException e) {
            return failed("No answer came in time.");
        } catch (IOException | IllegalArgumentException e) {
            return failed("The connection failed.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("The request was stopped.");
        }
    }

    private static Page failed(String problem) {
        return new Page(NO_ANSWER, "", problem);
    }
}
