package app.twintaillauncher.mobius.gen;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;
import com.github.luben.zstd.Zstd;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Encoder {
    private static final String DESCRIPTOR_SET = "mobius.desc";
    private static Map<String, Descriptors.Descriptor> messages;

    private Encoder() {
    }

    public static Descriptors.Descriptor descriptorFor(String messageName) {
        if (messages == null) messages = loadDescriptors();
        Descriptors.Descriptor descriptor = messages.get(messageName);
        if (descriptor == null) {
            throw new Config.ConfigException("no compiled message '" + messageName + "' -- is it declared in proto/?");
        }
        return descriptor;
    }

    private static Map<String, Descriptors.Descriptor> loadDescriptors() {
        try (InputStream in = openDescriptorSet()) {
            if (in == null) throw new Config.ConfigException(DESCRIPTOR_SET + " missing from the jar; run the build");
            DescriptorProtos.FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.parseFrom(in);

            Map<String, DescriptorProtos.FileDescriptorProto> byName = new LinkedHashMap<>();
            for (DescriptorProtos.FileDescriptorProto file : set.getFileList()) byName.put(file.getName(), file);

            Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
            Map<String, Descriptors.Descriptor> out = new LinkedHashMap<>();
            for (String name : byName.keySet()) {
                for (Descriptors.Descriptor descriptor : build(name, byName, built).getMessageTypes()) {
                    collect(descriptor, out);
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Descriptors.DescriptorValidationException e) {
            throw new Config.ConfigException("descriptor set is not valid: " + e.getMessage(), e);
        }
    }

    private static Descriptors.FileDescriptor build(String name, Map<String, DescriptorProtos.FileDescriptorProto> byName, Map<String, Descriptors.FileDescriptor> built) throws Descriptors.DescriptorValidationException {
        Descriptors.FileDescriptor done = built.get(name);
        if (done != null) return done;

        DescriptorProtos.FileDescriptorProto file = byName.get(name);
        if (file == null) throw new Config.ConfigException("descriptor set is missing dependency " + name);

        Descriptors.FileDescriptor[] deps = new Descriptors.FileDescriptor[file.getDependencyCount()];
        for (int i = 0; i < deps.length; i++) deps[i] = build(file.getDependency(i), byName, built);

        Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(file, deps);
        built.put(name, fd);
        return fd;
    }

    private static void collect(Descriptors.Descriptor descriptor, Map<String, Descriptors.Descriptor> out) {
        out.put(descriptor.getFullName(), descriptor);
        for (Descriptors.Descriptor nested : descriptor.getNestedTypes()) collect(nested, out);
    }

    private static InputStream openDescriptorSet() throws IOException {
        Path external = Config.besideJar(DESCRIPTOR_SET);
        if (external != null) return Files.newInputStream(external);
        return Encoder.class.getClassLoader().getResourceAsStream(DESCRIPTOR_SET);
    }

    public static DynamicMessage append(DynamicMessage fresh, DynamicMessage previous, String path, String key, int max) {
        return appendAt(fresh, previous, path.split("\\."), 0, key, max);
    }

    private static DynamicMessage appendAt(DynamicMessage fresh, DynamicMessage previous, String[] path, int depth, String key, int max) {
        Descriptors.FieldDescriptor f = fresh.getDescriptorForType().findFieldByName(path[depth]);
        if (f == null) {
            throw new Config.ConfigException("append: " + fresh.getDescriptorForType().getFullName() + " has no field '" + path[depth] + "'");
        }
        if (depth < path.length - 1) {
            if (!fresh.hasField(f) || !previous.hasField(f)) return fresh;
            DynamicMessage merged = appendAt((DynamicMessage) fresh.getField(f), (DynamicMessage) previous.getField(f), path, depth + 1, key, max);
            return fresh.toBuilder().setField(f, merged).build();
        }
        if (!f.isRepeated()) {
            throw new Config.ConfigException("append: '" + String.join(".", path) + "' is not a repeated field");
        }
        Descriptors.FieldDescriptor keyField = f.getMessageType().findFieldByName(key);
        if (keyField == null) {
            throw new Config.ConfigException("append: entries of '" + f.getName() + "' have no field '" + key + "'");
        }

        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < fresh.getRepeatedFieldCount(f); i++) seen.add(keyOf(fresh.getRepeatedField(f, i), keyField));

        DynamicMessage.Builder out = fresh.toBuilder();
        for (int i = 0; i < previous.getRepeatedFieldCount(f); i++) {
            Object entry = previous.getRepeatedField(f, i);
            if (!seen.add(keyOf(entry, keyField))) continue;
            if (max > 0 && out.getRepeatedFieldCount(f) >= max) break;
            out.addRepeatedField(f, entry);
        }
        return out.build();
    }

    private static String keyOf(Object entry, Descriptors.FieldDescriptor keyField) {
        return entry instanceof com.google.protobuf.Message m ? String.valueOf(m.getField(keyField)) : "";
    }

    public static DynamicMessage decode(Descriptors.Descriptor descriptor, byte[] ttm) throws IOException {
        try (com.github.luben.zstd.ZstdInputStream in = new com.github.luben.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(ttm))) {
            return DynamicMessage.parseFrom(descriptor, in.readAllBytes());
        }
    }

    public static DynamicMessage build(Descriptors.Descriptor descriptor, Map<String, Object> schemaNode, JsonElement extracted) {
        if (!extracted.isJsonObject()) {
            throw new Config.ConfigException("expected an object to encode, got " + extracted);
        }
        return message(descriptor, schemaNode, extracted.getAsJsonObject());
    }

    public static byte[] compress(byte[] serialized, int level) {
        return Zstd.compress(serialized, level);
    }

    private static DynamicMessage message(Descriptors.Descriptor descriptor, Map<String, Object> schemaNode, JsonObject json) {
        DynamicMessage.Builder out = DynamicMessage.newBuilder(descriptor);

        for (Map.Entry<String, Object> entry : schemaNode.entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            String key = entry.getKey();
            if (key.equals("when") || key.equals("translate") || key.equals("values")) continue;
            Map<String, Object> node = Yml.asMap(entry.getValue());

            if (node.containsKey("oneof")) {
                for (Map.Entry<String, Object> member : Yml.map(node, "fields").entrySet()) {
                    if (member.getValue() instanceof Map) {
                        write(out, descriptor, member.getKey(), Yml.asMap(member.getValue()), json);
                    }
                }
                continue;
            }
            write(out, descriptor, key, node, json);
        }
        return out.build();
    }

    private static void write(DynamicMessage.Builder out, Descriptors.Descriptor descriptor, String key, Map<String, Object> node, JsonObject json) {
        JsonElement value = json.get(key);
        if (value == null || value.isJsonNull()) return;

        Descriptors.FieldDescriptor fd = field(descriptor, key, node);
        if (fd == null) {
            throw new Config.ConfigException("schema binds " + descriptor.getFullName() + "." + key + " but no such field exists in proto/ -- regenerate or fix the binding");
        }

        if (fd.isMapField()) {
            writeMap(out, fd, node, value);
        } else if (fd.isRepeated()) {
            if (!value.isJsonArray()) return;
            com.google.gson.JsonArray array = value.getAsJsonArray();
            java.util.List<Object> entries = Yml.list(node, "entries");
            for (int i = 0; i < array.size(); i++) {
                Map<String, Object> at = i < entries.size() ? Yml.asMap(entries.get(i)) : node;
                out.addRepeatedField(fd, single(fd, at, array.get(i)));
            }
        } else {
            out.setField(fd, single(fd, node, value));
        }
    }

    private static Descriptors.FieldDescriptor field(Descriptors.Descriptor descriptor, String key, Map<String, Object> node) {
        Object number = node.get("field_number");
        if (number != null) {
            return descriptor.findFieldByNumber(Integer.parseInt(String.valueOf(number).trim()));
        }
        return descriptor.findFieldByName(Yml.string(node, "field_name", key));
    }

    private static Object single(Descriptors.FieldDescriptor fd, Map<String, Object> node, JsonElement value) {
        return switch (fd.getJavaType()) {
            case MESSAGE -> {
                String type = fd.getMessageType().getFullName();
                yield switch (type) {
                    case "google.protobuf.Timestamp" -> timestamp(value.getAsString());
                    case "google.protobuf.Duration" -> duration(value.getAsString());
                    case "google.protobuf.Struct" -> struct(value);
                    default -> message(fd.getMessageType(), node.containsKey("fields") ? Yml.map(node, "fields") : node, value.getAsJsonObject());
                };
            }
            case ENUM -> {
                Descriptors.EnumValueDescriptor constant = fd.getEnumType().findValueByName(value.getAsString());
                yield constant != null ? constant : fd.getEnumType().getValues().getFirst();
            }
            default -> primitive(fd, value);
        };
    }

    private static void writeMap(DynamicMessage.Builder out, Descriptors.FieldDescriptor fd, Map<String, Object> node, JsonElement value) {
        if (!value.isJsonObject()) return;
        Descriptors.Descriptor entry = fd.getMessageType();
        Descriptors.FieldDescriptor keyField = entry.findFieldByName("key");
        Descriptors.FieldDescriptor valueField = entry.findFieldByName("value");

        for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
            out.addRepeatedField(fd, DynamicMessage.newBuilder(entry).setField(keyField, primitive(keyField, new JsonPrimitive(e.getKey()))).setField(valueField, single(valueField, node, e.getValue())).build());
        }
    }

    private static Object primitive(Descriptors.FieldDescriptor fd, JsonElement value) {
        try {
            String text = value.getAsString();
            return switch (fd.getJavaType()) {
                case STRING -> text;
                case BOOLEAN -> value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : Boolean.parseBoolean(text);
                case INT -> fd.getLiteType() == com.google.protobuf.WireFormat.FieldType.UINT32 || fd.getLiteType() == com.google.protobuf.WireFormat.FieldType.FIXED32 ? Integer.parseUnsignedInt(text) : Integer.parseInt(text);
                case LONG -> fd.getLiteType() == com.google.protobuf.WireFormat.FieldType.UINT64 || fd.getLiteType() == com.google.protobuf.WireFormat.FieldType.FIXED64 ? Long.parseUnsignedLong(text) : Long.parseLong(text);
                case FLOAT -> Float.parseFloat(text);
                case DOUBLE -> Double.parseDouble(text);
                case BYTE_STRING -> ByteString.copyFrom(Base64.getDecoder().decode(text));
                default -> text;
            };
        } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException e) {
            throw new Config.ConfigException("cannot write " + value + " into " + fd.getFullName() + " (" + fd.getType() + (fd.isRepeated() ? ", repeated" : "") + "): " + e);
        }
    }

    private static Timestamp timestamp(String rfc3339) {
        Instant instant = Instant.parse(rfc3339);
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static com.google.protobuf.Duration duration(String text) {
        double seconds = Double.parseDouble(text.endsWith("s") ? text.substring(0, text.length() - 1) : text);
        long whole = (long) seconds;
        return com.google.protobuf.Duration.newBuilder().setSeconds(whole).setNanos((int) Math.round((seconds - whole) * 1_000_000_000L)).build();
    }

    private static Struct struct(JsonElement value) {
        Struct.Builder out = Struct.newBuilder();
        if (!value.isJsonObject()) return out.build();
        for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
            out.putFields(e.getKey(), structValue(e.getValue()));
        }
        return out.build();
    }

    private static Value structValue(JsonElement value) {
        if (value.isJsonNull()) return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        if (value.isJsonObject()) return Value.newBuilder().setStructValue(struct(value)).build();
        if (value.isJsonArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            for (JsonElement element : value.getAsJsonArray()) list.addValues(structValue(element));
            return Value.newBuilder().setListValue(list).build();
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        if (p.isBoolean()) return Value.newBuilder().setBoolValue(p.getAsBoolean()).build();
        if (p.isNumber()) return Value.newBuilder().setNumberValue(p.getAsDouble()).build();
        return Value.newBuilder().setStringValue(p.getAsString()).build();
    }
}
