package app.twintaillauncher.mobius.gen;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;

import java.util.HashMap;
import java.util.Map;

public final class SchemaCheck {

    private static final int RESERVED_LOW = 19000;
    private static final int RESERVED_HIGH = 19999;
    private static final int MAX_FIELD_NUMBER = 536870911;

    private SchemaCheck() { }

    public static void validate(String schemaFile, Map<String, Object> schema) {
        Map<String, Object> fields = Yml.map(schema, "fields");
        if (!fields.isEmpty()) message(schemaFile, "fields", fields);
    }

    private static void message(String file, String path, Map<String, Object> node) {
        Map<String, String> bindings = new HashMap<>();

        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            String key = entry.getKey();
            if (key.equals("when") || key.equals("translate") || key.equals("values")) continue;

            Map<String, Object> child = Yml.asMap(entry.getValue());
            String where = file + " " + path + "." + key;

            if (child.containsKey("oneof")) {
                for (Map.Entry<String, Object> member : Yml.map(child, "fields").entrySet()) {
                    if (member.getValue() instanceof Map) {
                        binding(where + "." + member.getKey(), Yml.asMap(member.getValue()), bindings);
                    }
                }
                continue;
            }

            binding(where, child, bindings);
            Map<String, Object> nested = child.containsKey("fields") ? Yml.map(child, "fields") : child;
            if (!nested.isEmpty()) message(file, path + "." + key, nested);
        }
    }

    private static void binding(String where, Map<String, Object> node, Map<String, String> bindings) {
        Object name = node.get("field_name");
        Object number = node.get("field_number");

        if (name != null && number != null) {
            throw new Config.ConfigException(where + ": bind by field_name or by field_number, not both");
        }
        if (name == null && number == null) return;

        String key;
        if (name != null) {
            key = "name " + name;
        } else {
            int n = getN(where, number);
            key = "number " + n;
        }

        String previous = bindings.put(key, where);
        if (previous != null) {
            throw new Config.ConfigException("field " + key + " used twice in the same message: " + previous + " and " + where);
        }
    }

    private static int getN(String where, Object number) {
        int n;
        try {
            n = Integer.parseInt(String.valueOf(number).trim());
        } catch (NumberFormatException e) {
            throw new Config.ConfigException(where + ": field_number '" + number + "' is not a number");
        }
        if (n < 1 || n > MAX_FIELD_NUMBER) {
            throw new Config.ConfigException(where + ": field_number " + n + " is outside 1.." + MAX_FIELD_NUMBER);
        }
        if (n >= RESERVED_LOW && n <= RESERVED_HIGH) {
            throw new Config.ConfigException(where + ": field_number " + n + " is in protobuf's reserved range " + RESERVED_LOW + ".." + RESERVED_HIGH);
        }
        return n;
    }
}
