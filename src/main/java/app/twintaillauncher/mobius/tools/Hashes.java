package app.twintaillauncher.mobius.tools;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.gen.Encoder;
import com.google.protobuf.Descriptors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Hashes {
    private Hashes() { }

    public static int run(List<String> bizFilter, Map<String, String> flags) throws IOException {
        Path outDir = Path.of(flags.getOrDefault("out", "manifests"));
        String repoFilter = flags.get("repository");
        boolean write = flags.containsKey("write");
        int level = Integer.parseInt(flags.getOrDefault("level", "19"));
        Descriptors.Descriptor descriptor = Encoder.descriptorFor(flags.getOrDefault("message", "TwintailManifest"));
        if (!Files.isDirectory(outDir)) throw new Config.ConfigException(outDir + " is not a directory");

        int touched = 0;
        int filled = 0;
        try (var repos = Files.list(outDir)) {
            for (Path repo : repos.sorted().toList()) {
                if (!Files.isDirectory(repo)) continue;
                if (repoFilter != null && !repo.getFileName().toString().equals(repoFilter)) continue;
                try (var manifests = Files.list(repo)) {
                    for (Path file : manifests.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".ttm")) continue;
                        if (!bizFilter.isEmpty() && !bizFilter.contains(name.substring(0, name.length() - 4))) continue;
                        com.google.protobuf.Message.Builder builder = Encoder.decode(descriptor, Files.readAllBytes(file)).toBuilder();
                        int count = fillRows(builder);
                        if (count == 0) continue;
                        touched++;
                        filled += count;
                        System.out.printf("%-42s %d rows%s%n", file, count, write ? " filled" : " fillable");
                        if (write) Files.write(file, Encoder.compress(builder.build().toByteArray(), level));
                    }
                }
            }
        }
        System.out.println(filled + " rows across " + touched + " manifests" + (write ? " rewritten" : ", nothing written without --write"));
        return 0;
    }

    static int fillRows(com.google.protobuf.Message.Builder builder) {
        int filled = 0;
        Descriptors.Descriptor descriptor = builder.getDescriptorForType();
        Descriptors.FieldDescriptor url = descriptor.findFieldByName("file_url");
        Descriptors.FieldDescriptor hash = descriptor.findFieldByName("file_hash");
        if (url != null && hash != null && ((String) builder.getField(hash)).isEmpty()) {
            String value = (String) builder.getField(url);
            String base = value.substring(value.lastIndexOf('/') + 1);
            java.util.regex.Matcher match = Dumps.SOPHON_MANIFEST.matcher(base);
            if (match.matches()) {
                builder.setField(hash, "md5:" + match.group(1));
                filled++;
            }
        }
        for (Descriptors.FieldDescriptor field : descriptor.getFields()) {
            if (field.getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
            if (field.isRepeated()) {
                for (int i = 0; i < builder.getRepeatedFieldCount(field); i++) {
                    com.google.protobuf.Message.Builder nested = builder.newBuilderForField(field).mergeFrom((com.google.protobuf.Message) builder.getRepeatedField(field, i));
                    int count = fillRows(nested);
                    if (count == 0) continue;
                    builder.setRepeatedField(field, i, nested.build());
                    filled += count;
                }
            } else if (builder.hasField(field)) {
                com.google.protobuf.Message.Builder nested = builder.newBuilderForField(field).mergeFrom((com.google.protobuf.Message) builder.getField(field));
                int count = fillRows(nested);
                if (count == 0) continue;
                builder.setField(field, nested.build());
                filled += count;
            }
        }
        return filled;
    }
}
