package app.twintaillauncher.mobius.gen;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;
import app.twintaillauncher.mobius.extract.Selector;
import app.twintaillauncher.mobius.extract.When;
import app.twintaillauncher.mobius.net.Api;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

public final class Provider {
    private final String id;
    private final Map<String, Object> yml;
    private final Map<String, String> vars = new LinkedHashMap<>();
    private final Map<String, Map<String, JsonElement>> sources = new LinkedHashMap<>();
    private final List<Game> games = new ArrayList<>();
    private final Map<String, Object> schema;
    private Map<String, Map<String, Object>> gameConfigs = Map.of();
    private String instanceId = "";
    private String baseId = "";
    private Api api;

    private Provider(String id, Map<String, Object> yml, Map<String, Object> schema) {
        this.id = id;
        this.yml = yml;
        this.schema = schema;
    }

    public static Provider load(Config config, Map<String, Object> yml, Api api) {
        return load(config, yml, api, Map.of(), "");
    }

    public static Provider load(Config config, Map<String, Object> yml, Api api, Map<String, Object> instanceVars, String instanceId) {
        String id = Yml.required(yml, "id", "provider " + Yml.string(yml, "__file", "?"));
        if (!instanceId.isEmpty()) id = id + "/" + instanceId;
        String schemaFile = Yml.required(yml, "schema", "provider " + id);
        Map<String, Object> schema = config.read(schemaFile);
        SchemaCheck.validate(schemaFile, schema);
        Provider provider = new Provider(id, yml, schema);
        provider.instanceId = instanceId;
        provider.api = api;
        provider.baseId = Yml.required(yml, "id", "provider");

        for (Map.Entry<String, Object> e : Yml.map(yml, "vars").entrySet()) {
            provider.vars.put(e.getKey(), String.valueOf(e.getValue()));
        }
        for (Map.Entry<String, Object> e : instanceVars.entrySet()) {
            provider.vars.put(e.getKey(), String.valueOf(e.getValue()));
        }
        for (Map.Entry<String, String> e : provider.vars.entrySet()) {
            e.setValue(Api.substitute(e.getValue(), provider.vars));
        }

        provider.gameConfigs = provider.gameConfigs(config);
        provider.index(api, false);
        provider.discoverGames();
        provider.index(api, true);
        return provider;
    }

    private void index(Api api, boolean dependent) {
        Map<String, Object> endpoints = Yml.map(yml, "endpoints");
        for (Map.Entry<String, Object> entry : Yml.map(yml, "sources").entrySet()) {
            String sourceName = entry.getKey();
            Map<String, Object> source = Yml.asMap(entry.getValue());
            boolean perGame = source.containsKey("per");
            if (perGame != dependent) continue;

            String endpointName = Yml.required(source, "endpoint", "source " + sourceName);
            Map<String, Object> endpoint = Yml.asMap(endpoints.get(endpointName));
            if (endpoint.isEmpty()) {
                throw new Config.ConfigException("source " + sourceName + ": unknown endpoint '" + endpointName + "'");
            }
            sources.put(sourceName, perGame ? indexPerGame(api, sourceName, source, endpointName, endpoint) : indexShared(api, sourceName, source, endpointName, endpoint));
        }
    }

    private Map<String, JsonElement> indexShared(Api api, String sourceName, Map<String, Object> source, String endpointName, Map<String, Object> endpoint) {
        JsonObject body = api.fetch(endpointName, endpoint, vars);
        Selector root = Selector.parse(Yml.required(source, "root", "source " + sourceName));
        Selector key = Selector.parse(Yml.required(source, "key", "source " + sourceName));

        Map<String, JsonElement> byId = new LinkedHashMap<>();
        for (JsonElement element : root.all(body)) {
            JsonElement k = key.first(element);
            if (k == null || !k.isJsonPrimitive()) continue;
            byId.putIfAbsent(k.getAsString(), element);
        }
        return byId;
    }

    private Map<String, JsonElement> indexPerGame(Api api, String sourceName, Map<String, Object> source, String endpointName, Map<String, Object> endpoint) {
        if (!Yml.map(source, "probe").isEmpty()) {
            return indexProbe(api, sourceName, source, endpointName, endpoint);
        }
        Map<String, Object> params = Yml.map(source, "params");
        Selector root = Selector.parse(Yml.string(source, "root", "data"));
        boolean optional = !Yml.bool(source, "required", false);
        List<String> only = Yml.stringList(source, "games");

        List<Game> owners = new ArrayList<>();
        List<Region> targets = new ArrayList<>();
        List<Map<String, String>> binds = new ArrayList<>();
        for (Game game : games) {
            if (!only.isEmpty() && !only.contains(gameKey(game))) continue;
            for (Region region : game.regions()) {
                Map<String, String> bound = new LinkedHashMap<>(game.vars());
                boolean complete = true;
                for (Map.Entry<String, Object> p : params.entrySet()) {
                    String value = param(String.valueOf(p.getValue()), game, region);
                    if (value == null || value.isEmpty()) {
                        complete = false;
                        break;
                    }
                    bound.put(p.getKey(), value);
                }
                if (!complete) continue;
                owners.add(game);
                targets.add(region);
                binds.add(bound);
            }
        }

        Map<String, JsonElement> byId = new LinkedHashMap<>();
        Semaphore limit = new Semaphore(Math.max(1, Yml.integer(source, "concurrency", 8)));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Game game = owners.get(i);
                Region region = targets.get(i);
                Map<String, String> bound = binds.get(i);
                futures.add(pool.submit(() -> {
                    limit.acquire();
                    try {
                        return api.fetch(sourceName + "_" + region.id(), endpointName, endpoint, game.vars(), bound);
                    } finally {
                        limit.release();
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    JsonElement element = root.first(futures.get(i).get());
                    if (element != null) byId.put(targets.get(i).id(), element);
                } catch (ExecutionException e) {
                    if (!optional) throw e.getCause() instanceof Api.ApiException api1 ? api1 : new Api.ApiException(sourceName + ": " + e.getCause().getMessage());
                    System.err.println("warn: " + sourceName + " unavailable for " + owners.get(i).biz() + "/" + targets.get(i).id() + ": " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return byId;
    }

    private Map<String, JsonElement> indexProbe(Api api, String sourceName, Map<String, Object> source, String endpointName, Map<String, Object> endpoint) {
        Map<String, Object> probe = Yml.map(source, "probe");
        Map<String, Object> params = Yml.map(source, "params");
        String param = Yml.required(probe, "vary", "source " + sourceName + " probe");
        Selector from = Selector.parse(Yml.required(probe, "from", "source " + sourceName + " probe"));
        int attempts = Yml.integer(probe, "attempts", 12);
        int wrap = Yml.integer(probe, "wrap", 9);
        String walk = Yml.string(probe, "walk", "down");
        int lines = Yml.integer(probe, "lines", 1);
        int stopAfter = Yml.integer(probe, "stop_after", 0);
        int patch = Yml.integer(probe, "patch", -1);
        int concurrency = Math.max(1, Yml.integer(probe, "concurrency", 8));
        Map<String, Object> keepWhen = Yml.map(probe, "when");
        List<String> only = Yml.stringList(source, "games");

        Map<String, JsonElement> byId = new LinkedHashMap<>();
        for (Game game : games) {
            if (!only.isEmpty() && !only.contains(gameKey(game))) continue;
            for (Region region : game.regions()) {
                String start = param(from.toString(), game, region);
                if (start == null || start.isEmpty()) continue;

                Map<String, String> fixed = new LinkedHashMap<>(game.vars());
                boolean complete = true;
                for (Map.Entry<String, Object> p : params.entrySet()) {
                    String value = param(String.valueOf(p.getValue()), game, region);
                    if (value == null || value.isEmpty()) { complete = false; break; }
                    fixed.put(p.getKey(), value);
                }
                if (!complete) continue;

                JsonArray kept = new JsonArray();
                for (List<String> line : candidates(walk, start, attempts, wrap, lines, patch)) {
                    for (JsonObject body : fetchParallel(api, sourceName, endpointName, endpoint, game, region, fixed, param, line, concurrency)) {
                        if (!When.test(keepWhen, body)) continue;
                        kept.add(body);
                        if (stopAfter > 0 && kept.size() >= stopAfter) break;
                    }
                    if (stopAfter > 0 && kept.size() >= stopAfter) break;
                }
                if (!kept.isEmpty()) byId.put(region.id(), kept);
            }
        }
        return byId;
    }

    private List<JsonObject> fetchParallel(Api api, String sourceName, String endpointName, Map<String, Object> endpoint, Game game, Region region, Map<String, String> fixed, String param, List<String> line, int concurrency) {
        List<JsonObject> answers = new ArrayList<>();
        Semaphore limit = new Semaphore(concurrency);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (String candidate : line) {
                Map<String, String> bound = new LinkedHashMap<>(fixed);
                bound.put(param, candidate);
                futures.add(pool.submit(() -> {
                    limit.acquire();
                    try {
                        return api.fetch(sourceName + "_" + region.id() + "_" + candidate, endpointName, endpoint, game.vars(), bound);
                    } finally {
                        limit.release();
                    }
                }));
            }
            for (Future<JsonObject> future : futures) {
                try {
                    answers.add(future.get());
                } catch (ExecutionException e) {
                    // a candidate does not exist, is a miss
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return answers;
    }

    public static List<List<String>> candidates(String walk, String version, int attempts, int wrap, int lines) {
        return candidates(walk, version, attempts, wrap, lines, -1);
    }

    public static List<List<String>> candidates(String walk, String version, int attempts, int wrap, int lines, int patch) {
        int[] n = parseVersion(version);
        if (n == null || n.length < 2) return List.of();

        List<List<String>> groups = new ArrayList<>();
        List<String> out = new ArrayList<>();
        if (walk.equals("minor_lines")) {
            int minor = n.length - 2;
            for (int line = 0; line < lines && out.size() < attempts; line++) {
                List<String> group = new ArrayList<>();
                n[minor]--;
                if (n[minor] < 0) {
                    if (minor == 0) break;
                    n[minor] = wrap;
                    n[minor - 1]--;
                    if (n[minor - 1] < 0) break;
                }
                for (int last = patch < 0 ? wrap : patch; last >= 0 && out.size() < attempts; last--) {
                    n[n.length - 1] = last;
                    group.add(join(n));
                    out.add(join(n));
                }
                if (!group.isEmpty()) groups.add(group);
            }
            return groups;
        }

        for (int step = 0; step < attempts; step++) {
            int i = n.length - 1;
            while (i >= 0 && n[i] == 0) {
                n[i] = wrap;
                i--;
            }
            if (i < 0) break;
            n[i]--;
            out.add(join(n));
        }
        return out.isEmpty() ? List.of() : List.of(out);
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] n = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                n[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return n;
    }

    private static String join(int[] n) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < n.length; j++) {
            if (j > 0) sb.append('.');
            sb.append(n[j]);
        }
        return sb.toString();
    }

    public String gameKey(Game game) {
        String region = vars.getOrDefault("region", instanceId);
        String suffix = "_" + region;
        return !region.isEmpty() && game.biz().endsWith(suffix) ? game.biz().substring(0, game.biz().length() - suffix.length()) : game.biz();
    }


    private String param(String rawPath, Game game, Region region) {
        String path = Api.substitute(rawPath, game.vars());
        switch (path) {
            case "@game_id" -> {
                return region.id();
            }
            case "@region.code" -> {
                return region.code();
            }
            case "@biz" -> {
                return game.biz();
            }
            default -> {
                if (path.startsWith("@static.")) return staticValue(game, path.substring("@static.".length()));
                Selector selector = Selector.parse(path);
                String head = selector.head();
                if (head == null || !sources.containsKey(head)) return null;
                JsonElement root = sources.get(head).get(region.id());
                if (root == null) return null;
                JsonElement value = selector.tail().first(root);
                return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
            }
        }
    }

    private String staticValue(Game game, String key) {
        Map<String, Object> config = gameConfigs.get(gameKey(game));
        if (config == null) return null;
        Object current = Yml.map(config, "static");
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = Yml.asMap(current).get(part);
            if (current == null) return null;
        }
        return String.valueOf(current);
    }

    private void discoverGames() {
        if (Yml.string(yml, "discovery", "api").equals("static")) {
            discoverStaticGames();
        } else {
            discoverApiGames();
        }
    }

    private void discoverStaticGames() {
        for (Object entry : Yml.list(yml, "catalog")) {
            Map<String, Object> game = Yml.asMap(entry);
            List<String> only = Yml.stringList(game, "instances");
            if (!only.isEmpty() && !only.contains(instanceId)) continue;
            Map<String, String> gameVars = new LinkedHashMap<>(vars);
            for (Map.Entry<String, Object> v : Yml.map(game, "vars").entrySet()) {
                gameVars.put(v.getKey(), Api.substitute(String.valueOf(v.getValue()), vars));
            }

            String biz = Api.substitute(Yml.required(game, "biz", "catalog entry"), gameVars);
            String id = Api.substitute(Yml.string(game, "id", biz), gameVars);
            String repository = Yml.string(game, "repository", Yml.string(yml, "repository", "games"));
            games.add(new Game(biz, id, Yml.string(game, "status", "available"), repository, List.of(new Region(id, Yml.string(game, "region_code", ""), Yml.string(game, "name", ""), Yml.string(game, "description", ""), Map.of())), gameVars));
        }
        if (games.isEmpty()) throw new Config.ConfigException("provider " + id + ": discovery is static but catalog is empty");
    }

    private void discoverApiGames() {
        Map<String, Object> regionCfg = Yml.map(yml, "regions");
        Map<String, Object> statusCfg = Yml.map(yml, "status");

        Selector regionsFrom = Selector.parse(Yml.required(regionCfg, "from", "regions"));
        Selector regionId = Selector.parse(Yml.required(regionCfg, "id", "regions"));
        Selector regionCode = Selector.parse(Yml.required(regionCfg, "code", "regions"));
        Selector regionName = Selector.parse(Yml.string(regionCfg, "name", "i18n_name"));
        Selector regionDesc = Selector.parse(Yml.string(regionCfg, "description", "i18n_description"));
        String fallbackCode = Yml.string(regionCfg, "fallback_code", "");
        Map<String, Object> extraFields = Yml.map(regionCfg, "fields");

        Selector statusFrom = Selector.parse(Yml.required(statusCfg, "from", "status"));
        Map<String, Object> statusValues = Yml.map(statusCfg, "values");
        String statusDefault = Yml.string(statusCfg, "default", "unknown");

        String gameSource = regionsFrom.head();
        for (Map.Entry<String, JsonElement> entry : source(gameSource).entrySet()) {
            String primaryId = entry.getKey();
            JsonElement game = entry.getValue();

            JsonElement bizElement = Selector.parse("biz").first(game);
            String biz = bizElement == null ? primaryId : bizElement.getAsString();

            JsonElement raw = statusFrom.tail().first(game);
            String status = raw == null ? statusDefault : String.valueOf(statusValues.getOrDefault(raw.getAsString(), statusDefault));

            List<Region> regions = new ArrayList<>();
            for (JsonElement server : regionsFrom.tail().all(game)) {
                JsonElement rid = regionId.first(server);
                if (rid == null) continue;
                Map<String, String> fields = new LinkedHashMap<>();
                for (Map.Entry<String, Object> f : extraFields.entrySet()) {
                    String value = text(Selector.parse(String.valueOf(f.getValue())), server, "");
                    if (!value.isEmpty()) fields.put(f.getKey(), value);
                }
                regions.add(new Region(rid.getAsString(), text(regionCode, server, fallbackCode), text(regionName, server, ""), text(regionDesc, server, ""), fields));
            }
            if (regions.isEmpty()) regions.add(new Region(primaryId, fallbackCode, "", "", Map.of()));

            games.add(new Game(biz, primaryId, status, Yml.string(yml, "repository", "games"), regions, vars));
        }
    }

    private static String text(Selector selector, JsonElement element, String fallback) {
        JsonElement v = selector.first(element);
        return v == null || !v.isJsonPrimitive() ? fallback : v.getAsString().trim();
    }

    public void putSource(String name, String gameId, JsonElement value) {
        sources.computeIfAbsent(name, k -> new LinkedHashMap<>()).put(gameId, value);
    }

    public Map<String, JsonElement> source(String name) {
        Map<String, JsonElement> s = sources.get(name);
        if (s == null) throw new Config.ConfigException("unknown source '" + name + "' in provider " + id);
        return s;
    }

    public boolean hasSource(String name) {
        return sources.containsKey(name);
    }

    public String publisher() {
        return baseId;
    }

    public String var(String name, String fallback) {
        return vars.getOrDefault(name, fallback);
    }

    public String instanceId() {
        return instanceId;
    }

    public String id() {
        return id;
    }

    public List<Game> games() {
        return games;
    }

    public Api api() {
        return api;
    }

    public Map<String, Object> yml() {
        return yml;
    }

    public Map<String, Object> schema() {
        return schema;
    }

    private static Map<String, Object> deepMerge(Map<String, Object> base, Map<String, Object> over) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : over.entrySet()) {
            Object existing = out.get(e.getKey());
            if (existing instanceof Map && e.getValue() instanceof Map) {
                out.put(e.getKey(), deepMerge(Yml.asMap(existing), Yml.asMap(e.getValue())));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public Map<String, Map<String, Object>> gameConfigs(Config config) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String rel : Yml.stringList(yml, "static_configs")) {
            if (!config.exists(rel)) continue;
            Map<String, Object> game = config.read(rel);
            Map<String, Object> declared = Yml.map(game, "instances");
            Map<String, Object> overrides = Yml.map(declared, instanceId);
            if (!overrides.isEmpty()) {
                game = new LinkedHashMap<>(game);
                game.put("static", deepMerge(Yml.map(game, "static"), overrides));
            }
            String key = Yml.required(game, "id", rel);
            out.put(key, game);
            sources.put(key, rel);
        }
        for (Game game : games) {
            Map<String, Object> cfg = out.get(gameKey(game));
            if (cfg == null) continue;
            Map<String, Object> declared = Yml.map(cfg, "instances");
            if (declared.isEmpty()) continue;
            String where = sources.get(gameKey(game));
            if (!declared.containsKey(instanceId)) {
                throw new Config.ConfigException(where + ": " + game.biz() + " runs on instance '" + instanceId + "' but instances: only declares " + declared.keySet() + (instanceId.isEmpty() ? " (provider " + id + " declares no instances:, so its instance id is empty)" : ""));
            }
            Map<String, Object> merged = Yml.map(cfg, "static");
            boolean named = declared.values().stream().anyMatch(v -> Yml.asMap(v).containsKey("display_name"));
            if (named && Yml.string(merged, "display_name", "").isEmpty()) {
                throw new Config.ConfigException(where + ": instances." + instanceId + " sets no display_name for " + game.biz() + ", but other instances in that block do");
            }
        }
        return out;
    }

    public record Region(String id, String code, String name, String description, Map<String, String> fields) { }

    public record Game(String biz, String primaryId, String status, String repository, List<Region> regions, Map<String, String> vars) { }
}
