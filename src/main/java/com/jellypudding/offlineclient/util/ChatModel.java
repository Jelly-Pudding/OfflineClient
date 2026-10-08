package com.jellypudding.offlineclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.StringUtil;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Asks a chat model for a reply in the common chat completions shape that model servers
// on your own computer and the hosted services all take. Only the model name and the
// messages are sent. Services disagree on what a length cap is called.
public final class ChatModel {

    // One chat line as the model reads it.
    public record Said(String name, String text) {
    }

    // A reply or the problem that stopped one. The key counts as refused when the service
    // turned away the key it got or asked for one it never got.
    public record Answer(String reply, String problem, boolean keyRefused) {
    }

    // Where the shape is posted. An address that stops short of it has it added.
    private static final String ENDPOINT = "/chat/completions";
    // Most model servers take the shape under this path. An address with nothing after the host
    // that finds nothing at its root is tried again with it.
    private static final String VERSION = "/v1";

    // A model on a home computer can take a while to load and then to write.
    private static final Duration WAIT = Duration.ofMinutes(1);

    // Some models think aloud between these tags before they answer.
    private static final Pattern THINKING = Pattern.compile("(?is)<think>.*?(</think>|$)");

    // The most of a service's own words about a failure that are worth showing.
    private static final int LONGEST_PROBLEM = 200;

    private static final int UNAUTHORISED = 401;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;

    private ChatModel() {
    }

    // The host of a web address in lower case. Null for anything else.
    public static String host(String address) {
        try {
            String host = URI.create(address.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Waits for the model. Only ever called from a worker thread. A blank model name is left
    // out for a server that answers with the one it has loaded.
    public static Answer ask(String address, String model, String key, String instructions, List<Said> chat) {
        JsonObject body = new JsonObject();
        if (!model.isBlank()) {
            body.addProperty("model", model.strip());
        }
        JsonArray messages = new JsonArray();
        messages.add(message("system", instructions));
        messages.add(message("user", chat.stream()
            .map(said -> "<" + said.name() + "> " + said.text())
            .collect(Collectors.joining("\n"))));
        body.add("messages", messages);

        String json = body.toString();
        Web.Page page = Web.postJson(endpoint(address, ""), json, key, WAIT);
        boolean retried = page.status() == NOT_FOUND && bare(address);
        if (retried) {
            page = Web.postJson(endpoint(address, VERSION), json, key, WAIT);
        }
        if (page.problem() != null) {
            return failed(page.problem());
        }
        if (!page.ok()) {
            String said = serviceWords(page.body());
            String hint = retried && page.status() == NOT_FOUND
                ? " Most model servers want the address to end in " + VERSION + "." : "";
            return new Answer(null, "It answered " + page.status() + "." + (said == null ? "" : " " + said) + hint,
                page.status() == UNAUTHORISED || page.status() == FORBIDDEN);
        }
        String reply = replyIn(page.body());
        if (reply == null) {
            return failed("Its answer held no reply.");
        }
        return reply.isEmpty() ? failed("The model wrote nothing.") : new Answer(reply, null, false);
    }

    private static Answer failed(String problem) {
        return new Answer(null, problem, false);
    }

    // The address as typed with the version and the chat path added when it stops short of
    // them. A query after a question mark stays at the end.
    private static String endpoint(String address, String version) {
        String typed = address.strip();
        int query = typed.indexOf('?');
        String path = query == -1 ? typed : typed.substring(0, query);
        String rest = query == -1 ? "" : typed.substring(query);
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return (path.endsWith(ENDPOINT) ? path : path + version + ENDPOINT) + rest;
    }

    // True for a web address with nothing after the host and port.
    private static boolean bare(String address) {
        try {
            String path = URI.create(address.strip()).getPath();
            return path != null && (path.isEmpty() || path.equals("/"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    // The first line of the first choice once any thinking is taken out. Empty when the model
    // wrote nothing and null when the body is no chat reply at all.
    private static String replyIn(String body) {
        try {
            JsonElement content = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices")
                .get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            String text = content == null || content.isJsonNull() ? "" : content.getAsString();
            return THINKING.matcher(text).replaceAll("").lines().map(String::strip)
                .filter(line -> !line.isEmpty()).findFirst().orElse("");
        } catch (RuntimeException e) {
            return null;
        }
    }

    // What the service said went wrong whether it nests the words under error or not. Null
    // when it gave no words.
    private static String serviceWords(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement error = root.has("error") ? root.get("error") : root;
            JsonElement words = error.isJsonObject() ? error.getAsJsonObject().get("message") : error;
            if (words == null || !words.isJsonPrimitive()) {
                return null;
            }
            String text = StringUtil.filterText(words.getAsString()).strip();
            return text.isEmpty() ? null : StringUtil.truncateStringIfNecessary(text, LONGEST_PROBLEM, true);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
