package app.twintaillauncher.mobius.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Yml {
    private Yml() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> node, String key) {
        Object v = node.get(key);
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new Config.ConfigException(key + ": expected a mapping");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object v) {
        if (v == null) return new LinkedHashMap<>();
        if (!(v instanceof Map)) throw new Config.ConfigException("expected a mapping, got " + v.getClass().getSimpleName());
        return (Map<String, Object>) v;
    }

    public static List<Object> list(Map<String, Object> node, String key) {
        Object v = node.get(key);
        if (v == null) return List.of();
        if (v instanceof List<?> l) return new ArrayList<>(l);
        throw new Config.ConfigException(key + ": expected a list");
    }

    public static List<String> stringList(Map<String, Object> node, String key) {
        List<String> out = new ArrayList<>();
        for (Object o : list(node, key)) out.add(String.valueOf(o));
        return out;
    }

    public static String string(Map<String, Object> node, String key, String fallback) {
        Object v = node.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static String required(Map<String, Object> node, String key, String what) {
        Object v = node.get(key);
        if (v == null) throw new Config.ConfigException(what + ": missing required key '" + key + "'");
        return String.valueOf(v);
    }

    public static boolean bool(Map<String, Object> node, String key, boolean fallback) {
        Object v = node.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    public static int integer(Map<String, Object> node, String key, int fallback) {
        Object v = node.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new Config.ConfigException(key + ": expected an integer, got '" + v + "'");
        }
    }
}
