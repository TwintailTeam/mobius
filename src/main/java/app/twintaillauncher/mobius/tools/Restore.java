package app.twintaillauncher.mobius.tools;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.gen.Encoder;
import app.twintaillauncher.mobius.gen.Json;
import com.google.gson.JsonElement;
import com.google.protobuf.Descriptors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Restore {
    private Restore() { }

    public static int run(List<String> bizFilter, Map<String, String> flags) throws IOException {
        Path from = Path.of(flags.getOrDefault("from", "."));
        Path outDir = Path.of(flags.getOrDefault("out", "manifests"));
        String repoFilter = flags.get("repository");
        int level = Integer.parseInt(flags.getOrDefault("level", "19"));
        Descriptors.Descriptor descriptor = Encoder.descriptorFor(flags.getOrDefault("message", "TwintailManifest"));
        if (!Files.isDirectory(from)) throw new Config.ConfigException(from + " is not a directory");

        int written = 0;
        try (var repos = Files.list(from)) {
            for (Path repo : repos.sorted().toList()) {
                if (!Files.isDirectory(repo)) continue;
                String repoName = repo.getFileName().toString();
                if (repoFilter != null && !repoName.equals(repoFilter)) continue;
                try (var files = Files.list(repo)) {
                    for (Path file : files.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".json") || name.equals("repository.json")) continue;
                        String biz = name.substring(0, name.length() - 5);
                        if (!bizFilter.isEmpty() && !bizFilter.contains(biz)) continue;
                        JsonElement json = com.google.gson.JsonParser.parseString(Files.readString(file));
                        if (!json.isJsonObject()) {
                            System.err.println(file + ": not a json object, skipped");
                            continue;
                        }
                        byte[] compressed = Encoder.compress(Json.message(descriptor, json.getAsJsonObject()).toByteArray(), level);
                        Path target = outDir.resolve(repoName).resolve(biz + ".ttm");
                        Files.createDirectories(target.getParent());
                        Files.write(target, compressed);
                        System.out.printf("%-42s -> %s (%d bytes)%n", file, target, compressed.length);
                        written++;
                    }
                }
            }
        }
        System.out.println(written + " manifests restored");
        return written > 0 ? 0 : 1;
    }
}
