package app.twintaillauncher.mobius.gen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.Descriptors;

import java.util.Map;

public final class Json {
    private Json() { }

    public static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    public static com.google.protobuf.DynamicMessage message(Descriptors.Descriptor type, JsonObject json) {
        com.google.protobuf.DynamicMessage.Builder builder = com.google.protobuf.DynamicMessage.newBuilder(type);
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            Descriptors.FieldDescriptor field = type.findFieldByName(entry.getKey());
            if (field == null) continue;
            if (field.isRepeated()) {
                if (!entry.getValue().isJsonArray()) continue;
                for (JsonElement item : entry.getValue().getAsJsonArray()) {
                    Object value = value(field, item);
                    if (value != null) builder.addRepeatedField(field, value);
                }
                continue;
            }
            Object value = value(field, entry.getValue());
            if (value != null) builder.setField(field, value);
        }
        return builder.build();
    }

    public static Object value(Descriptors.FieldDescriptor field, JsonElement json) {
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.MESSAGE) {
            return json.isJsonObject() ? message(field.getMessageType(), json.getAsJsonObject()) : null;
        }
        if (field.getJavaType() == Descriptors.FieldDescriptor.JavaType.ENUM) {
            return json.isJsonPrimitive() ? field.getEnumType().findValueByName(json.getAsString()) : null;
        }
        if (!json.isJsonPrimitive()) return null;
        return switch (field.getJavaType()) {
            case STRING -> json.getAsString();
            case BOOLEAN -> json.getAsBoolean();
            case INT -> json.getAsInt();
            case LONG -> json.getAsLong();
            case DOUBLE -> json.getAsDouble();
            case FLOAT -> json.getAsFloat();
            default -> null;
        };
    }

    public static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String one = i < a.length ? a[i].trim() : "0";
            String two = i < b.length ? b[i].trim() : "0";
            int result = one.matches("\\d+") && two.matches("\\d+") ? Integer.compare(Integer.parseInt(one), Integer.parseInt(two)) : one.compareTo(two);
            if (result != 0) return result;
        }
        return 0;
    }

    public static int parseOrZero(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
