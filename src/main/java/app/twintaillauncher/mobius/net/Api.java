package app.twintaillauncher.mobius.net;

import app.twintaillauncher.mobius.config.Yml;
import app.twintaillauncher.mobius.extract.Selector;
import app.twintaillauncher.mobius.extract.When;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class Api {
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(403, 408, 425, 429);
    private static final long MAX_RETRY_WAIT_MS = 30000;

    private final HttpClient client;
    private final int retries;
    private final long retryDelayMs;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final Path offlineDir;
    private final Map<String, Object> auth;
    private final Duration requestTimeout;
    private final Map<String, JsonObject> cache = new ConcurrentHashMap<>();
    private final Map<String, JsonElement> elements = new ConcurrentHashMap<>();

    public Api(Map<String, Object> httpConfig, Map<String, Object> auth, Path offlineDir) {
        this.auth = auth;
        this.retries = Math.max(1, Yml.integer(httpConfig, "retries", 3));
        this.retryDelayMs = Yml.integer(httpConfig, "retry_delay_ms", 750);
        this.offlineDir = offlineDir;
        this.headers.put("User-Agent", Yml.string(httpConfig, "user_agent", "mobius/1.0"));
        this.headers.put("Accept", "application/json");
        for (Map.Entry<String, Object> e : Yml.map(httpConfig, "headers").entrySet()) {
            this.headers.put(e.getKey(), String.valueOf(e.getValue()));
        }
        this.requestTimeout = Duration.ofMillis(Yml.integer(httpConfig, "request_timeout_ms", 60000));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Yml.integer(httpConfig, "connect_timeout_ms", 30000))).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public static Map<String, Object> mergeHttp(Map<String, Object> global, Map<String, Object> provider) {
        Map<String, Object> merged = new LinkedHashMap<>(global);
        merged.putAll(provider);
        Map<String, Object> headers = new LinkedHashMap<>(Yml.map(global, "headers"));
        headers.putAll(Yml.map(provider, "headers"));
        if (!headers.isEmpty()) merged.put("headers", headers);
        return merged;
    }

    public JsonObject fetch(String name, Map<String, Object> endpoint, Map<String, String> vars) {
        return fetch(name, name, endpoint, vars, Map.of());
    }

    public JsonElement fetchElement(String cacheKey, String name, Map<String, Object> endpoint, Map<String, String> vars, Map<String, String> params) {
        JsonElement cached = elements.get(cacheKey);
        if (cached != null) return cached;

        Map<String, String> merged = new HashMap<>(vars);
        merged.putAll(params);
        String body = offlineDir != null ? readOfflineText(cacheKey) : requestText(name, endpoint, merged);
        JsonElement parsed = JsonParser.parseString(body);
        elements.put(cacheKey, parsed);
        return parsed;
    }

    public JsonObject fetch(String cacheKey, String name, Map<String, Object> endpoint, Map<String, String> vars, Map<String, String> params) {
        JsonObject cached = cache.get(cacheKey);
        if (cached != null) return cached;

        Map<String, String> merged = new HashMap<>(vars);
        merged.putAll(params);

        JsonObject body = offlineDir != null ? readOffline(cacheKey) : request(name, endpoint, merged);
        checkOk(name, endpoint, body);
        cache.put(cacheKey, body);
        return body;
    }

    private JsonObject request(String name, Map<String, Object> endpoint, Map<String, String> vars) {
        return parse(name, requestText(name, endpoint, vars));
    }

    private JsonObject readOffline(String name) {
        return parse(name, readOfflineText(name));
    }

    private String readOfflineText(String name) {
        Path file = offlineDir.resolve(name + ".json");
        if (!Files.isRegularFile(file)) throw new ApiException("offline mode: " + file + " not found");
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String requestText(String name, Map<String, Object> endpoint, Map<String, String> vars) {
        String url = substitute(Yml.required(endpoint, "url", "endpoint " + name), vars);
        String method = Yml.string(endpoint, "method", "GET").toUpperCase();
        String body = substitute(Yml.string(endpoint, "body", ""), vars);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        Map<String, String> merged = new LinkedHashMap<>(headers);
        for (Map.Entry<String, Object> e : Yml.map(endpoint, "headers").entrySet()) {
            merged.put(e.getKey(), substitute(String.valueOf(e.getValue()), vars));
        }
        if (!body.isEmpty()) merged.putIfAbsent("Content-Type", Yml.string(endpoint, "content_type", "application/json"));
        Signer.apply(Yml.map(endpoint, "auth").isEmpty() ? auth : Yml.map(endpoint, "auth"), vars, body, merged);
        merged.forEach(builder::header);

        HttpRequest request = builder.timeout(requestTimeout).method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            boolean retryable = true;
            long wait = retryDelayMs << (attempt - 1);
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status / 100 == 2) return decode(response.body());
                last = new ApiException(name + ": http " + status + " from " + url);
                retryable = status / 100 == 5 || TRANSIENT_STATUSES.contains(status);
                wait = retryAfter(response, wait);
            } catch (IOException e) {
                last = new ApiException(name + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(name + ": interrupted", e);
            } catch (ApiException e) {
                last = e;
            }
            if (!retryable) throw last;
            if (attempt < retries) sleep(wait);
        }
        assert last != null;
        throw last;
    }

    private static long retryAfter(HttpResponse<byte[]> response, long fallback) {
        String header = response.headers().firstValue("Retry-After").orElse("").trim();
        if (header.isEmpty()) return fallback;
        try {
            return Math.min(Long.parseLong(header) * 1000L, MAX_RETRY_WAIT_MS);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String decode(byte[] body) {
        if (body.length > 1 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ApiException("gzip response could not be read: " + e.getMessage(), e);
            }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static JsonObject parse(String name, String body) {
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) throw new ApiException(name + ": response is not a json object");
        return parsed.getAsJsonObject();
    }

    private static void checkOk(String name, Map<String, Object> endpoint, JsonObject body) {
        Map<String, Object> when = Yml.map(endpoint, "when");
        if (when.isEmpty() || When.test(when, body)) return;
        String from = String.valueOf(when.get("from"));
        String got = When.text(Selector.parse(from).first(body));
        JsonElement message = body.get("message");
        throw new ApiException(name + ": " + from + "=" + got + " (expected " + when.get("equals") + ")" + (message == null || message.isJsonNull() ? "" : ", message: " + message.getAsString()));
    }

    public static String substitute(String template, Map<String, String> vars) {
        String out = template;
        for (int pass = 0; pass < 3 && out.indexOf('{') >= 0; pass++) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return out;
    }

    public void dumpTo(Path dir) {
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, JsonObject> e : cache.entrySet()) {
                Files.writeString(dir.resolve(e.getKey() + ".json"), e.getValue().toString(), StandardCharsets.UTF_8);
            }
            for (Map.Entry<String, JsonElement> e : elements.entrySet()) {
                Files.writeString(dir.resolve(e.getKey() + ".json"), e.getValue().toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class ApiException extends RuntimeException {
        public ApiException(String message) {
            super(message);
        }

        public ApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
