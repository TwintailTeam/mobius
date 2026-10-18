package app.twintaillauncher.mobius.extract;

import app.twintaillauncher.mobius.config.Config;
import com.google.gson.JsonElement;

import java.util.Map;

public final class When {
    private When() { }

    public static boolean test(Map<String, Object> when, JsonElement root) {
        if (when.isEmpty()) return true;
        Object from = when.get("from");
        if (from == null) throw new Config.ConfigException("when: needs a from: selector, got " + when.keySet());
        JsonElement match = Selector.parse(String.valueOf(from)).first(root);

        if (when.containsKey("exists")) {
            boolean wanted = !"false".equals(String.valueOf(when.get("exists")));
            return (match != null) == wanted;
        }
        if (when.containsKey("equals")) {
            return match != null && String.valueOf(when.get("equals")).equals(text(match));
        }
        return match != null;
    }

    public static String text(JsonElement match) {
        if (match == null || match.isJsonNull()) return "<missing>";
        return match.isJsonPrimitive() ? match.getAsString() : match.toString();
    }
}
