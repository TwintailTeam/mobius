package app.twintaillauncher.mobius.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Selector {
    private final List<Step> steps;
    private final String raw;

    private Selector(String raw, List<Step> steps) {
        this.raw = raw;
        this.steps = steps;
    }

    public static Selector parse(String path) {
        List<Step> steps = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(name, steps);
            } else if (c == '[') {
                flush(name, steps);
                int end = path.indexOf(']', i);
                if (end < 0) throw new IllegalArgumentException("unterminated '[' in selector: " + path);
                steps.add(bracket(path.substring(i + 1, end), path));
                i = end;
            } else {
                name.append(c);
            }
        }
        flush(name, steps);
        return new Selector(path, steps);
    }

    private static void flush(StringBuilder name, List<Step> steps) {
        if (!name.isEmpty()) {
            steps.add(new Field(name.toString()));
            name.setLength(0);
        }
    }

    private static Step bracket(String inside, String path) {
        if (inside.equals("*")) return new All();
        if (inside.contains("~=")) {
            List<String[]> conditions = new ArrayList<>();
            for (String part : inside.split(",")) {
                int at = part.indexOf("~=");
                if (at < 0) continue;
                String field = part.substring(0, at).trim();
                boolean negated = field.endsWith("!");
                if (negated) field = field.substring(0, field.length() - 1).trim();
                conditions.add(new String[]{field, part.substring(at + 2).trim(), negated ? "!" : ""});
            }
            return new Contains(conditions);
        }
        int eq = inside.indexOf('=');
        if (eq > 0) return new Match(inside.substring(0, eq).trim(), inside.substring(eq + 1).trim());
        try {
            return new Index(Integer.parseInt(inside.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad selector segment '[" + inside + "]' in: " + path);
        }
    }

    public List<JsonElement> all(JsonElement root) {
        List<JsonElement> current = new ArrayList<>();
        if (root != null && !root.isJsonNull()) current.add(root);
        for (Step step : steps) {
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement element : current) step.apply(element, next);
            current = next;
            if (current.isEmpty()) break;
        }
        return current;
    }

    public JsonElement first(JsonElement root) {
        List<JsonElement> matches = all(root);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public String head() {
        for (Step step : steps) {
            if (step instanceof Field f) return f.name;
        }
        return null;
    }

    public Selector tail() {
        List<Step> rest = new ArrayList<>(steps);
        for (int i = 0; i < rest.size(); i++) {
            if (rest.get(i) instanceof Field) {
                rest.remove(i);
                break;
            }
        }
        return new Selector(raw, rest);
    }

    @Override
    public String toString() {
        return raw;
    }

    private interface Step {
        void apply(JsonElement in, List<JsonElement> out);
    }

    private record Field(String name) implements Step {
        @Override
        public void apply(JsonElement in, List<JsonElement> out) {
            if (!in.isJsonObject()) return;
            JsonElement v = in.getAsJsonObject().get(name);
            if (v != null && !v.isJsonNull()) out.add(v);
        }
    }

    private record Index(int index) implements Step {
        @Override
        public void apply(JsonElement in, List<JsonElement> out) {
            if (!in.isJsonArray()) return;
            JsonArray array = in.getAsJsonArray();
            if (index >= 0 && index < array.size()) out.add(array.get(index));
        }
    }

    private record All() implements Step {
        @Override
        public void apply(JsonElement in, List<JsonElement> out) {
            if (in.isJsonArray()) {
                for (JsonElement e : in.getAsJsonArray()) {
                    if (!e.isJsonNull()) out.add(e);
                }
            } else if (in.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : in.getAsJsonObject().entrySet()) {
                    if (!e.getValue().isJsonNull()) out.add(e.getValue());
                }
            }
        }
    }

    private record Contains(List<String[]> conditions) implements Step {
        @Override
        public void apply(JsonElement in, List<JsonElement> out) {
            if (conditions.isEmpty() || conditions.getFirst()[1].isEmpty() || !in.isJsonArray()) return;
            for (JsonElement e : in.getAsJsonArray()) {
                if (!e.isJsonObject() || !matches(e.getAsJsonObject())) continue;
                out.add(e);
                return;
            }
        }

        private boolean matches(JsonObject o) {
            for (String[] c : conditions) {
                JsonElement v = o.get(c[0]);
                boolean has = v != null && v.isJsonPrimitive() && v.getAsString().toLowerCase().contains(c[1].toLowerCase());
                if (has == c[2].equals("!")) return false;
            }
            return true;
        }
    }

    private record Match(String field, String value) implements Step {
        @Override
        public void apply(JsonElement in, List<JsonElement> out) {
            if (!in.isJsonArray()) return;
            for (JsonElement e : in.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                JsonElement v = o.get(field);
                if (v != null && !v.isJsonNull() && v.isJsonPrimitive() && v.getAsString().equals(value)) {
                    out.add(e);
                    return;
                }
            }
        }
    }
}
