package app.twintaillauncher.mobius.gen;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;
import app.twintaillauncher.mobius.extract.Selector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Generator {


    private static final Set<String> MAP_KEYWORDS = Set.of("fields", "translate", "when", "values");
    private static final Set<String> NUMBER_TYPES = Set.of("int32", "uint32", "sint32", "fixed32", "sfixed32", "float", "double");
    private static final Set<String> LONG_TYPES = Set.of("int64", "uint64", "sint64", "fixed64", "sfixed64");

    private final Provider provider;
    private final Map<String, Object> gameConfig;

    public Generator(Provider provider, Map<String, Object> gameConfig) {
        this.provider = provider;
        this.gameConfig = gameConfig == null ? Map.of() : gameConfig;
    }

    public JsonElement render(Provider.Game game) {
        Map<String, Object> fields = Yml.map(provider.schema(), "fields");
        if (fields.isEmpty()) throw new Config.ConfigException("schema has no fields: block");
        JsonElement rendered = node(fields, new Ctx(game, game.primaryId(), null, null, null));
        return rendered == null ? JsonNull.INSTANCE : rendered;
    }

    private JsonElement node(Map<String, Object> node, Ctx ctx) {
        if (!condition(node, ctx)) {
            return node.containsKey("default") ? coerce(literal(node.get("default")), node) : null;
        }
        if (keyword(node, "const") != null) return coerce(literal(node.get("const")), node);
        if (keyword(node, "static") != null) return coerce(fromStatic(Objects.requireNonNull(keyword(node, "static"))), node);
        if (keyword(node, "raw") != null) return raw(node, ctx);
        if (node.get("entries") instanceof List) return entries(node, ctx);
        if (node.get("concat") instanceof List) return concat(node, ctx);
        if (keyword(node, "sum") != null) return sum(node, ctx);
        if (keyword(node, "each") != null) return each(node, ctx);
        if (keyword(node, "from") != null) return scalar(node, ctx);
        return object(node, ctx);
    }

    private JsonElement entries(Map<String, Object> node, Ctx ctx) {
        JsonArray out = new JsonArray();
        for (Object entry : Yml.list(node, "entries")) {
            JsonElement rendered = object(Yml.asMap(entry), ctx);
            if (rendered != null) out.add(rendered);
        }
        if (out.isEmpty() && Yml.bool(node, "omit_when_empty", false)) return null;
        return out;
    }

    private JsonElement concat(Map<String, Object> node, Ctx ctx) {
        StringBuilder text = new StringBuilder();
        for (Object part : Yml.list(node, "concat")) {
            JsonElement rendered = node(Yml.asMap(part), ctx);
            if (rendered == null || !rendered.isJsonPrimitive() || rendered.getAsString().isEmpty()) {
                return node.containsKey("default") ? coerce(literal(node.get("default")), node) : null;
            }
            text.append(rendered.getAsString());
        }
        return coerce(extract(new JsonPrimitive(text.toString()), node, ctx), node);
    }

    private JsonElement sum(Map<String, Object> node, Ctx ctx) {
        List<JsonElement> matches = resolve(Objects.requireNonNull(keyword(node, "sum")), ctx);
        if (matches.size() == 1 && matches.getFirst().isJsonArray()) {
            List<JsonElement> all = new java.util.ArrayList<>();
            for (JsonElement e : matches.getFirst().getAsJsonArray()) all.add(e);
            matches = all;
        }
        if (matches.isEmpty()) return node.containsKey("default") ? coerce(literal(node.get("default")), node) : null;
        long total = 0;
        for (JsonElement e : matches) {
            if (e.isJsonPrimitive()) total += Long.parseLong(e.getAsString().trim());
        }
        return coerce(new JsonPrimitive(Long.toString(total)), node);
    }

    private boolean condition(Map<String, Object> node, Ctx ctx) {
        if (node.get("when") instanceof List) {
            for (Object clause : Yml.list(node, "when")) {
                if (!clause(Yml.asMap(clause), ctx)) return false;
            }
            return true;
        }
        Map<String, Object> when = Yml.map(node, "when");
        return when.isEmpty() || clause(when, ctx);
    }

    private boolean clause(Map<String, Object> when, Ctx ctx) {
        if (!when.containsKey("from")) throw new Config.ConfigException("when: needs a from");
        List<JsonElement> matches = resolveFrom(when, ctx);
        if (when.containsKey("exists")) {
            return !matches.isEmpty() == Yml.bool(when, "exists", true);
        }
        if (when.containsKey("matches") || when.containsKey("matches_not")) {
            boolean negated = when.containsKey("matches_not");
            String regex = String.valueOf(when.get(negated ? "matches_not" : "matches"));
            for (JsonElement match : matches) {
                if (match.isJsonPrimitive() && match.getAsString().matches(regex)) return !negated;
            }
            return negated;
        }

        String expected = String.valueOf(when.get("equals"));
        for (JsonElement match : matches) {
            if (match.isJsonPrimitive() && match.getAsString().equals(expected)) return true;
        }
        return false;
    }

    private static String keyword(Map<String, Object> node, String key) {
        Object v = node.get(key);
        return v == null || v instanceof Map ? null : String.valueOf(v);
    }

    private JsonElement object(Map<String, Object> node, Ctx ctx) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            if (MAP_KEYWORDS.contains(entry.getKey())) continue;
            Map<String, Object> child = Yml.asMap(entry.getValue());

            if (keyword(child, "oneof") != null) {
                oneof(child, ctx, out);
                continue;
            }
            JsonElement rendered = node(child, ctx);
            if (rendered != null) out.add(entry.getKey(), rendered);
        }
        if (out.isEmpty() && Yml.bool(node, "omit_when_empty", false)) return null;
        return out;
    }

    private void oneof(Map<String, Object> node, Ctx ctx, JsonObject out) {
        for (Map.Entry<String, Object> member : Yml.map(node, "fields").entrySet()) {
            if (!(member.getValue() instanceof Map)) continue;
            JsonElement rendered = node(Yml.asMap(member.getValue()), ctx);
            if (rendered == null) continue;
            if (rendered.isJsonPrimitive() && rendered.getAsString().isEmpty()) continue;
            out.add(member.getKey(), rendered);
            return;
        }
    }

    private JsonElement each(Map<String, Object> node, Ctx ctx) {
        String each = keyword(node, "each");
        Map<String, Object> fields = Yml.map(node, "fields");
        JsonArray out = new JsonArray();

        assert each != null;
        each = app.twintaillauncher.mobius.net.Api.substitute(each, ctx.game().vars());
        if (each.equals("regions")) {
            for (Provider.Region region : ctx.game().regions()) {
                JsonElement rendered = element(fields, new Ctx(ctx.game(), region.id(), region, null, null));
                if (rendered != null) out.add(rendered);
            }
        } else if (keyword(node, "expand") != null) {
            expand(each, Objects.requireNonNull(keyword(node, "expand")), fields, ctx, out);
        } else if (eachObject(each, fields, ctx, out)) {
            // keys bound to @key
        } else {
            List<JsonElement> matches = resolve(each, ctx);
            if (matches.size() == 1 && matches.getFirst().isJsonArray()) {
                matches = new java.util.ArrayList<>();
                for (JsonElement e : resolve(each, ctx).getFirst().getAsJsonArray()) matches.add(e);
            }
            for (JsonElement element : matches) {
                JsonElement rendered = element(fields, new Ctx(ctx.game(), ctx.gameId(), ctx.region(), element, null));
                if (rendered != null) out.add(rendered);
            }
        }

        if (out.isEmpty() && Yml.bool(node, "omit_when_empty", false)) return null;
        return out;
    }

    private void expand(String each, String inner, Map<String, Object> fields, Ctx ctx, JsonArray out) {
        for (JsonElement element : flat(resolve(each, ctx))) {
            Ctx scope = new Ctx(ctx.game(), ctx.gameId(), ctx.region(), element, null, element);
            if (inner.endsWith("[*]")) {
                List<JsonElement> parents = resolve(inner.substring(0, inner.length() - 3), scope);
                if (!parents.isEmpty() && parents.getFirst().isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : sorted(parents.getFirst().getAsJsonObject())) {
                        JsonElement rendered = element(fields, new Ctx(ctx.game(), ctx.gameId(), ctx.region(), entry.getValue(), entry.getKey(), element));
                        if (rendered != null) out.add(rendered);
                    }
                    continue;
                }
            }
            for (JsonElement entry : flat(resolve(inner, scope))) {
                JsonElement rendered = element(fields, new Ctx(ctx.game(), ctx.gameId(), ctx.region(), entry, null, element));
                if (rendered != null) out.add(rendered);
            }
        }
    }

    private static List<Map.Entry<String, JsonElement>> sorted(JsonObject object) {
        List<Map.Entry<String, JsonElement>> out = new java.util.ArrayList<>(object.entrySet());
        out.sort(Map.Entry.comparingByKey());
        return out;
    }

    private static List<JsonElement> flat(List<JsonElement> matches) {
        if (matches.size() != 1 || !matches.getFirst().isJsonArray()) return matches;
        List<JsonElement> out = new java.util.ArrayList<>();
        for (JsonElement e : matches.getFirst().getAsJsonArray()) out.add(e);
        return out;
    }

    private JsonElement element(Map<String, Object> fields, Ctx ctx) {
        return condition(fields, ctx) ? object(fields, ctx) : null;
    }

    private boolean eachObject(String each, Map<String, Object> fields, Ctx ctx, JsonArray out) {
        if (!each.endsWith("[*]")) return false;
        List<JsonElement> parents = resolve(each.substring(0, each.length() - 3), ctx);
        if (parents.isEmpty() || !parents.getFirst().isJsonObject()) return false;

        for (Map.Entry<String, JsonElement> entry : sorted(parents.getFirst().getAsJsonObject())) {
            JsonElement rendered = element(fields, new Ctx(ctx.game(), ctx.gameId(), ctx.region(), entry.getValue(), entry.getKey()));
            if (rendered != null) out.add(rendered);
        }
        return true;
    }

    private JsonElement raw(Map<String, Object> node, Ctx ctx) {
        List<JsonElement> matches = resolve(Objects.requireNonNull(keyword(node, "raw")), ctx);
        if (matches.isEmpty()) {
            return node.containsKey("default") ? literal(node.get("default")) : null;
        }
        JsonElement value = matches.getFirst();
        if (value.isJsonObject() && value.getAsJsonObject().isEmpty() && Yml.bool(node, "omit_when_empty", false)) {
            return null;
        }
        return coerce(value, node);
    }

    private JsonElement scalar(Map<String, Object> node, Ctx ctx) {
        List<JsonElement> matches = resolveFrom(node, ctx);

        if (matches.isEmpty()) {
            if (!node.containsKey("default")) return null;
            return coerce(literal(node.get("default")), node);
        }
        if (Yml.bool(node, "repeated", false)) {
            JsonArray array = new JsonArray();
            for (JsonElement e : matches) array.add(coerce(extract(translate(e, node), node, ctx), node));
            return array;
        }
        return coerce(extract(translate(matches.getFirst(), node), node, ctx), node);
    }

    private List<JsonElement> resolveFrom(Map<String, Object> node, Ctx ctx) {
        if (!(node.get("from") instanceof List)) {
            return resolve(Objects.requireNonNull(keyword(node, "from")), ctx);
        }
        for (Object candidate : Yml.list(node, "from")) {
            List<JsonElement> matches = resolve(String.valueOf(candidate), ctx);
            if (!matches.isEmpty() && !(matches.size() == 1 && matches.getFirst().isJsonPrimitive() && matches.getFirst().getAsString().isEmpty())) {
                return matches;
            }
        }
        return List.of();
    }

    private List<JsonElement> resolve(String rawPath, Ctx ctx) {
        String path = app.twintaillauncher.mobius.net.Api.substitute(rawPath, ctx.game().vars());
        if (path.indexOf('{') >= 0) path = placeholders(path, ctx);
        if (path.startsWith("@static.")) {
            JsonElement root = literal(Yml.map(gameConfig, "static"));
            return root == null ? List.of() : Selector.parse(path.substring("@static.".length())).all(root);
        }
        if (path.startsWith("@parent.")) {
            if (ctx.parent() == null) return List.of();
            return Selector.parse(path.substring("@parent.".length())).all(ctx.parent());
        }
        if (path.startsWith("@")) {
            JsonElement derived = derived(path, ctx);
            return derived == null ? List.of() : List.of(derived);
        }

        Selector selector = Selector.parse(path);
        String head = selector.head();
        if (head != null && provider.hasSource(head)) {
            JsonElement root = provider.source(head).get(ctx.gameId());
            return root == null ? List.of() : selector.tail().all(root);
        }
        if (ctx.current() != null) return selector.all(ctx.current());
        throw new Config.ConfigException("selector '" + path + "': '" + head + "' is not a source, and there is no element in scope");
    }

    private String placeholders(String path, Ctx ctx) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^{}]+)}").matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            List<JsonElement> value = name.startsWith("@") ? resolve(name, ctx) : ctx.current() == null ? List.of() : Selector.parse(name).all(ctx.current());
            String text = value.isEmpty() || !value.getFirst().isJsonPrimitive() ? "" : value.getFirst().getAsString();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(text));
        }
        m.appendTail(out);
        return out.toString();
    }

    private JsonElement derived(String path, Ctx ctx) {
        Provider.Region region = ctx.region();
        return switch (path) {
            case "@status" -> new JsonPrimitive(ctx.game().status());
            case "@key" -> ctx.key() == null ? null : new JsonPrimitive(ctx.key());
            case "@biz" -> new JsonPrimitive(ctx.game().biz());
            case "@game_id" -> new JsonPrimitive(ctx.gameId());
            case "@primary_game_id" -> new JsonPrimitive(ctx.game().primaryId());
            case "@region.id" -> region == null ? null : new JsonPrimitive(region.id());
            case "@region.code" -> region == null ? null : new JsonPrimitive(region.code());
            case "@region.name" -> region == null || region.name().isEmpty() ? null : new JsonPrimitive(region.name());
            case "@region.description" -> region == null || region.description().isEmpty() ? null : new JsonPrimitive(region.description());
            default -> {
                if (path.startsWith("@static.")) {
                    yield fromStatic(path.substring("@static.".length()));
                }
                if (region != null && path.startsWith("@region.")) {
                    String value = region.fields().get(path.substring("@region.".length()));
                    yield value == null ? null : new JsonPrimitive(value);
                }
                throw new Config.ConfigException("unknown derived value '" + path + "'");
            }
        };
    }

    private JsonElement fromStatic(String key) {
        Object current = Yml.map(gameConfig, "static");
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = Yml.asMap(current).get(part);
            if (current == null) return null;
        }
        return literal(current);
    }

    private static JsonElement extract(JsonElement value, Map<String, Object> node, Ctx ctx) {
        value = rewrite(value, node);
        String pattern = Yml.string(node, "pattern", null);
        if (pattern == null || !value.isJsonPrimitive()) return value;
        pattern = app.twintaillauncher.mobius.net.Api.substitute(pattern, ctx.game().vars());

        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(value.getAsString());
        if (!m.find()) return new JsonPrimitive("");

        String template = Yml.string(node, "template", null);
        if (template != null) template = app.twintaillauncher.mobius.net.Api.substitute(template, ctx.game().vars());
        if (template == null) return new JsonPrimitive(m.groupCount() >= 1 ? m.group(1) : m.group());

        String out = template;
        for (int i = m.groupCount(); i >= 0; i--) {
            out = out.replace("{" + i + "}", m.group(i) == null ? "" : m.group(i));
        }
        return new JsonPrimitive(out);
    }


    private static JsonElement rewrite(JsonElement value, Map<String, Object> node) {
        List<Object> rules = Yml.list(node, "rewrite");
        if (rules.isEmpty() || value == null || !value.isJsonPrimitive()) return value;
        String text = value.getAsString();
        for (Object rule : rules) {
            Map<String, Object> r = Yml.asMap(rule);
            String match = Yml.required(r, "match", "rewrite");
            text = java.util.regex.Pattern.compile(match).matcher(text).replaceAll(Yml.string(r, "with", ""));
        }
        return new JsonPrimitive(text);
    }

    private static JsonElement translate(JsonElement value, Map<String, Object> node) {
        Map<String, Object> mapping = Yml.map(node, "translate");
        if (mapping.isEmpty() || !value.isJsonPrimitive()) return value;
        Object mapped = mapping.get(value.getAsString());
        return mapped == null ? value : literal(mapped);
    }

    private static JsonElement coerce(JsonElement value, Map<String, Object> node) {
        if (value == null) return node.containsKey("default") ? literal(node.get("default")) : null;
        String type = Yml.string(node, "type", null);
        if (type == null) return value;

        try {
            switch (type) {
                case "map" -> {
                    return protoMap(value, node);
                }
                case "enum" -> {
                    return protoEnum(value, node);
                }
                case "message", "struct" -> {
                    return value;
                }
            }
            if (!value.isJsonPrimitive()) return value;

            String text = value.getAsString();
            if (NUMBER_TYPES.contains(type)) {
                return new JsonPrimitive(type.equals("float") || type.equals("double") ? (Number) Double.valueOf(text.trim()) : (Number) Long.valueOf(text.trim()));
            }
            if (LONG_TYPES.contains(type)) {
                return new JsonPrimitive(Long.toString(Long.parseLong(text.trim())));
            }
            return switch (type) {
                case "string" -> new JsonPrimitive(text);
                case "bool" -> new JsonPrimitive(toBool(text));
                case "bytes" -> new JsonPrimitive(toBase64(text, Yml.string(node, "encoding", "base64")));
                case "timestamp" -> new JsonPrimitive(toTimestamp(text, Yml.string(node, "unit", "millis")));
                case "duration" -> new JsonPrimitive(toDuration(text, Yml.string(node, "unit", "seconds")));
                default -> throw new Config.ConfigException("unknown proto type '" + type + "'");
            };
        } catch (IllegalArgumentException e) {
            throw new Config.ConfigException("cannot read '" + value + "' as " + type + " (from " + Yml.string(node, "from", Yml.string(node, "raw", "?")) + "): " + e.getMessage());
        }
    }

    private static boolean toBool(String text) {
        String t = text.trim();
        if (t.equals("1") || t.equalsIgnoreCase("true")) return true;
        if (t.equals("0") || t.equalsIgnoreCase("false") || t.isEmpty()) return false;
        throw new IllegalArgumentException("not a boolean");
    }

    private static String toBase64(String text, String encoding) {
        return switch (encoding) {
            case "base64" -> text;
            case "hex" -> Base64.getEncoder().encodeToString(HexFormat.of().parseHex(text.trim()));
            case "utf8" -> Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            default -> throw new Config.ConfigException("unknown bytes encoding '" + encoding + "'");
        };
    }

    private static String toTimestamp(String text, String unit) {
        String t = text.trim();
        return switch (unit) {
            case "millis" -> Instant.ofEpochMilli(Long.parseLong(t)).toString();
            case "seconds" -> Instant.ofEpochSecond(Long.parseLong(t)).toString();
            case "rfc3339" -> Instant.parse(t).toString();
            default -> throw new Config.ConfigException("unknown timestamp unit '" + unit + "'");
        };
    }

    private static String toDuration(String text, String unit) {
        long raw = Long.parseLong(text.trim());
        long seconds = unit.equals("millis") ? raw / 1000 : raw;
        long fraction = unit.equals("millis") ? Math.abs(raw % 1000) : 0;
        if (fraction == 0) return seconds + "s";
        return seconds + "." + String.format("%03d", fraction).replaceAll("0+$", "") + "s";
    }

    private static JsonElement protoMap(JsonElement value, Map<String, Object> node) {
        if (!value.isJsonObject()) {
            throw new Config.ConfigException("map field expects an object, got " + value);
        }
        String valueType = Yml.string(node, "value_type", null);
        if (valueType == null) return value;

        Map<String, Object> element = new java.util.LinkedHashMap<>(node);
        element.put("type", valueType);
        element.remove("key_type");
        element.remove("value_type");

        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
            out.add(e.getKey(), coerce(e.getValue(), element));
        }
        return out;
    }

    private static JsonElement protoEnum(JsonElement value, Map<String, Object> node) {
        Map<String, Object> values = Yml.map(node, "values");
        String unknown = Yml.string(node, "unknown", null);
        if (!value.isJsonPrimitive()) {
            return unknown == null ? value : new JsonPrimitive(unknown);
        }
        Object mapped = values.get(value.getAsString());
        if (mapped != null) return new JsonPrimitive(String.valueOf(mapped));
        return new JsonPrimitive(unknown == null ? value.getAsString() : unknown);
    }

    @SuppressWarnings("unchecked")
    private static JsonElement literal(Object value) {
        switch (value) {
            case null -> {
                return JsonNull.INSTANCE;
            }
            case JsonElement e -> {
                return e;
            }
            case Boolean b -> {
                return new JsonPrimitive(b);
            }
            case Number n -> {
                return new JsonPrimitive(n);
            }
            case List<?> list -> {
                JsonArray array = new JsonArray();
                for (Object o : list) array.add(literal(o));
                return array;
            }
            case Map<?, ?> map -> {
                JsonObject object = new JsonObject();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                    object.add(e.getKey(), literal(e.getValue()));
                }
                return object;
            }
            default -> {
                return new JsonPrimitive(String.valueOf(value));
            }
        }
    }

    private record Ctx(Provider.Game game, String gameId, Provider.Region region, JsonElement current, String key, JsonElement parent) {
        Ctx(Provider.Game game, String gameId, Provider.Region region, JsonElement current, String key) {
            this(game, gameId, region, current, key, null);
        }
    }
}
