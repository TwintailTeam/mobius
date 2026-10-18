package app.twintaillauncher.mobius.tools;

import app.twintaillauncher.mobius.Main;
import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.extract.Selector;
import app.twintaillauncher.mobius.net.Api;
import com.google.gson.Gson;
import app.twintaillauncher.mobius.config.Yml;
import app.twintaillauncher.mobius.gen.Encoder;
import app.twintaillauncher.mobius.gen.Generator;
import app.twintaillauncher.mobius.gen.Json;
import app.twintaillauncher.mobius.gen.Provider;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.Descriptors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Port {
    private Port() { }

    public static int run(List<String> bizFilter, Map<String, String> flags) throws IOException {
        String repoFilter = flags.get("repository");
        Path from = Path.of(flags.getOrDefault("from", "."));
        Path outDir = Path.of(flags.getOrDefault("out", "manifests"));
        int level = Integer.parseInt(flags.getOrDefault("level", "19"));
        int pages = Integer.parseInt(flags.getOrDefault("pages", "3"));

        Main.Loadout all = Main.load(flags);
        List<String> failed = new ArrayList<>(all.failed());
        Gson gson = new Gson();
        int written = 0;

        for (Main.Loaded loaded : all.loaded()) {
            Provider provider = loaded.provider();
            Map<String, Object> schema = provider.schema();
            Descriptors.Descriptor descriptor;
            try {
                descriptor = Encoder.descriptorFor(Yml.string(schema, "message", "mobius.Game"));
            } catch (Config.ConfigException e) {
                System.err.println("provider " + provider.id() + " skipped: " + e.getMessage());
                failed.add(provider.id());
                continue;
            }
            Map<String, Object> root = Main.rootNode(schema);

            for (Provider.Game game : provider.games()) {
                if (!bizFilter.isEmpty() && !bizFilter.contains(game.biz())) continue;
                if (repoFilter != null && !repoFilter.equals(game.repository())) continue;
                Path repoDir = outDir.resolve(game.repository());
                Files.createDirectories(repoDir);

                if ("yostar".equals(flags.get("walk"))) {
                    if (!yostarVersions(provider, game, loaded, descriptor, root, schema, repoDir, level, flags)) {
                        failed.add(game.biz());
                    } else {
                        written++;
                    }
                    continue;
                }

                if ("json".equals(flags.get("walk"))) {
                    if (!jsonHistory(provider, game, loaded, descriptor, root, schema, repoDir, from, level, flags)) {
                        failed.add(game.biz());
                    } else {
                        written++;
                    }
                    continue;
                }

                if ("sophon".equals(flags.get("walk"))) {
                    if (!sophonWalk(provider, game, loaded, descriptor, root, schema, repoDir, from, level, flags)) {
                        failed.add(game.biz());
                    } else {
                        written++;
                    }
                    continue;
                }

                Path json = from.resolve(game.biz() + ".json");
                if (!Files.isRegularFile(json)) {
                    System.err.println("no old manifest for " + game.biz() + " at " + json);
                    continue;
                }

                JsonObject old;
                try {
                    old = gson.fromJson(Files.readString(json), JsonObject.class);
                } catch (RuntimeException e) {
                    System.err.println(json + ": not a json manifest, skipped");
                    failed.add(game.biz());
                    continue;
                }
                if (old == null || !old.has("versions") || !old.get("versions").isJsonArray()) {
                    System.err.println(json + ": no 'versions' array, so nothing to port for " + game.biz() + (old != null && old.has("game_versions") ? " (that file is a game manifest, not a runner one)" : ""));
                    failed.add(game.biz());
                    continue;
                }

                List<String> wanted = new ArrayList<>();
                for (JsonElement v : old.getAsJsonArray("versions")) {
                    if (!v.isJsonObject() || !v.getAsJsonObject().has("version")) continue;
                    wanted.add(v.getAsJsonObject().get("version").getAsString());
                }
                if (wanted.isEmpty()) {
                    System.err.println(json + ": 'versions' carries no version strings, skipped");
                    failed.add(game.biz());
                    continue;
                }

                JsonArray history = releases(provider, game, pages, wanted);
                reportGaps(history, game.vars(), game.biz());
                provider.putSource("release", game.regions().getFirst().id(), history);

                JsonElement extracted = new Generator(provider, loaded.gameConfigs().get(provider.gameKey(game))).render(game);
                com.google.protobuf.DynamicMessage message = Encoder.build(descriptor, root, extracted);
                Path file = Main.file(repoDir, game);
                Map<String, Object> append = Yml.map(schema, "append");
                if (!append.isEmpty() && !flags.containsKey("fresh") && Files.exists(file)) {
                    message = Encoder.append(message, Encoder.decode(descriptor, Files.readAllBytes(file)), Yml.required(append, "field", "append"), Yml.required(append, "key", "append"), Yml.integer(append, "max", 0));
                }
                message = ordered(descriptor, message);
        byte[] compressed = Encoder.compress(message.toByteArray(), level);
                Files.write(file, compressed);
                written++;
                System.out.printf("%-18s %2d/%-2d versions ported -> %s (%d bytes)%n", game.biz(), history.size(), wanted.size(), file, compressed.length);
            }
        }
        Main.dumpResponses(all, flags);
        if (written == 0) System.err.println("nothing ported");
        return Main.report(new Main.Loadout(all.loaded(), failed), written == 0 ? 1 : 0);
    }

    static boolean sophonWalk(Provider provider, Provider.Game game, Main.Loaded loaded, Descriptors.Descriptor descriptor, Map<String, Object> root, Map<String, Object> schema, Path repoDir, Path from, int level, Map<String, String> flags) throws IOException {
        Map<String, Object> endpoint = Yml.map(Yml.map(provider.yml(), "endpoints"), "sophon_build");
        if (endpoint.isEmpty()) {
            System.err.println(game.biz() + ": provider " + provider.id() + " has no sophon_build endpoint, skipped");
            return false;
        }
        Map<String, JsonElement> branches = provider.source("branch");

        Map<String, Map<String, JsonObject>> byTag = new LinkedHashMap<>();
        String currentTag = "";
        for (Provider.Region region : game.regions()) {
            JsonElement branch = branches.get(region.id());
            if (branch == null || !branch.isJsonObject()) continue;
            JsonElement main = branch.getAsJsonObject().get("main");
            if (main == null || !main.isJsonObject()) continue;
            JsonObject m = main.getAsJsonObject();
            String tag = Json.text(m, "tag");
            if (tag.isEmpty()) continue;
            if (currentTag.isEmpty()) currentTag = tag;

            List<String> tags = new ArrayList<>();
            tags.add(tag);
            for (List<String> line : Provider.candidates("minor_lines", tag, 40, 9, 30, 0)) tags.addAll(line);

            Map<String, String> fixed = new LinkedHashMap<>();
            fixed.put("branch", Json.text(m, "branch"));
            fixed.put("package_id", Json.text(m, "package_id"));
            fixed.put("password", Json.text(m, "password"));
            fixed.put("plat_app", region.id());

            for (String candidate : tags) {
                Map<String, String> params = new LinkedHashMap<>(fixed);
                params.put("tag", candidate);
                JsonObject body;
                try {
                    body = provider.api().fetch("walk_" + region.id() + "_" + candidate, "sophon_build", endpoint, game.vars(), params);
                } catch (RuntimeException e) {
                    continue;
                }
                JsonElement code = body.get("retcode");
                if (code == null || !code.isJsonPrimitive() || code.getAsInt() != 0) continue;
                JsonElement data = body.get("data");
                if (data == null || !data.isJsonObject()) continue;
                byTag.computeIfAbsent(candidate, k -> new LinkedHashMap<>()).put(region.code(), data.getAsJsonObject());
            }
        }
        if (byTag.isEmpty()) {
            System.err.println(game.biz() + ": sophon answered for no tag, nothing walked");
            return false;
        }

        Map<String, JsonObject> oldByVersion = Dumps.sophonEraHistory(from.resolve(game.biz() + ".json"), game.biz());
        String assumeScenario = flags.getOrDefault("assume-scenario", "");
        String scenarioFrom = flags.getOrDefault("scenario-from", "");

        JsonElement extracted = new Generator(provider, loaded.gameConfigs().get(provider.gameKey(game))).render(game);
        com.google.protobuf.DynamicMessage message = Encoder.build(descriptor, root, extracted);

        Descriptors.FieldDescriptor manifestField = descriptor.findFieldByName("game_manifest");
        if (manifestField == null || !message.hasField(manifestField)) {
            System.err.println(game.biz() + ": rendered manifest has no game_manifest, nothing walked");
            return false;
        }
        com.google.protobuf.DynamicMessage current = (com.google.protobuf.DynamicMessage) message.getField(manifestField);
        Descriptors.FieldDescriptor listField = current.getDescriptorForType().findFieldByName("version_list");
        com.google.protobuf.DynamicMessage.Builder manifestBuilder = current.toBuilder();
        int walked = 0;
        int backfilled = 0;
        boolean single = game.regions().size() == 1;
        for (Map.Entry<String, Map<String, JsonObject>> entry : byTag.entrySet()) {
            if (entry.getKey().equals(currentTag)) continue;
            JsonObject old = oldByVersion.get(entry.getKey());
            JsonObject version = sophonVersion(entry.getKey(), entry.getValue(), old, single);
            if (!assumeScenario.isEmpty() && atLeast(entry.getKey(), scenarioFrom)) {
                stampScenario(version, assumeScenario);
            }
            String password = Dumps.oldArchivePassword(old);
            if (!password.isEmpty()) stampPassword(version, password);
            JsonObject assets = Dumps.oldAssets(old);
            if (assets != null) version.add("asset_info", assets);
            manifestBuilder.addRepeatedField(listField, Json.message(listField.getMessageType(), version));
            if (diffCount(version) > 0) backfilled++;
            walked++;
        }
        message = message.toBuilder().setField(manifestField, manifestBuilder.build()).build();
        Path file = Main.file(repoDir, game);
        Map<String, Object> append = Yml.map(schema, "append");
        if (!append.isEmpty() && !flags.containsKey("fresh") && Files.exists(file)) {
            message = Encoder.append(message, Encoder.decode(descriptor, Files.readAllBytes(file)), Yml.required(append, "field", "append"), Yml.required(append, "key", "append"), Yml.integer(append, "max", 0));
        }
        message = ordered(descriptor, message);
        byte[] compressed = Encoder.compress(message.toByteArray(), level);
        Files.write(file, compressed);
        System.out.printf("%-18s %2d walked + 1 current, %d with diffs ported -> %s (%d bytes)%n", game.biz(), walked, backfilled, file, compressed.length);
        return true;
    }

    static JsonObject sophonVersion(String tag, Map<String, JsonObject> perRegion, JsonObject old, boolean single) {
        JsonObject version = new JsonObject();
        version.addProperty("version", tag);
        if (single) {
            JsonObject pkg = new JsonObject();
            JsonArray full = new JsonArray();
            for (JsonObject data : perRegion.values()) full.addAll(sophonRows(data, false));
            if (!full.isEmpty()) pkg.add("full", full);
            if (Dumps.sophonEra(old)) {
                JsonArray diff = Dumps.oldDiffs(old.getAsJsonObject("game"));
                if (!diff.isEmpty()) pkg.add("diff", diff);
            }
            if (!pkg.isEmpty()) version.add("game_package", pkg);
            JsonArray audio = sophonAudio(perRegion.values().iterator().hasNext() ? perRegion.values().iterator().next() : null, old);
            if (!audio.isEmpty()) version.add("audio_package", audio);
            return version;
        }
        JsonArray variants = new JsonArray();
        for (Map.Entry<String, JsonObject> region : perRegion.entrySet()) {
            JsonArray full = sophonRows(region.getValue(), false);
            if (full.isEmpty()) continue;
            JsonObject pkg = new JsonObject();
            pkg.add("full", full);
            JsonObject variant = new JsonObject();
            variant.addProperty("key", region.getKey());
            variant.add("game_package", pkg);
            variants.add(variant);
        }
        if (!variants.isEmpty()) {
            JsonObject pkg = new JsonObject();
            pkg.add("variants", variants);
            version.add("game_package", pkg);
        }
        return version;
    }

    static JsonArray sophonRows(JsonObject data, boolean audio) {
        JsonArray out = new JsonArray();
        if (data == null) return out;
        JsonElement manifests = data.get("manifests");
        if (manifests == null || !manifests.isJsonArray()) return out;
        for (JsonElement e : manifests.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String field = Json.text(m, "matching_field");
            boolean locale = field.matches("(mini-)?[a-z]{2}-[a-z]{2}");
            boolean plain = field.matches("[a-z]{2}-[a-z]{2}");
            if (audio ? !plain : locale) continue;
            JsonObject row = new JsonObject();
            JsonObject md = m.getAsJsonObject("manifest");
            JsonObject down = m.getAsJsonObject("manifest_download");
            JsonObject chunk = m.getAsJsonObject("chunk_download");
            JsonObject sizes = m.has("deduplicated_stats") && m.get("deduplicated_stats").isJsonObject() ? m.getAsJsonObject("deduplicated_stats") : m.getAsJsonObject("stats");
            if (md == null || down == null) continue;
            row.addProperty("file_url", Json.text(down, "url_prefix") + "/" + Json.text(md, "id"));
            row.addProperty("file_hash", "md5:" + Json.text(md, "checksum"));
            row.addProperty("compressed_size", sizes == null ? "0" : Json.text(sizes, "compressed_size"));
            row.addProperty("decompressed_size", sizes == null ? "0" : Json.text(sizes, "uncompressed_size"));
            if (chunk != null) row.addProperty("resource_base_url", Json.text(chunk, "url_prefix"));
            if (audio) row.addProperty("language", field);
            out.add(row);
        }
        return out;
    }

    static JsonArray sophonAudio(JsonObject data, JsonObject old) {
        JsonArray out = new JsonArray();
        JsonArray rows = sophonRows(data, true);
        Map<String, JsonArray> diffs = new LinkedHashMap<>();
        if (Dumps.sophonEra(old) && old.get("audio") != null && old.get("audio").isJsonObject()) {
            for (JsonElement e : Dumps.oldDiffs(old.getAsJsonObject("audio"))) {
                if (!e.isJsonObject()) continue;
                JsonObject row = e.getAsJsonObject();
                String lang = Json.text(row, "language");
                row.remove("language");
                diffs.computeIfAbsent(lang, k -> new JsonArray()).add(row);
            }
        }
        for (JsonElement e : rows) {
            JsonObject row = e.getAsJsonObject();
            String lang = Json.text(row, "language");
            row.remove("language");
            JsonObject pkg = new JsonObject();
            JsonArray full = new JsonArray();
            full.add(row);
            pkg.add("full", full);
            JsonArray diff = diffs.get(lang);
            if (diff != null && !diff.isEmpty()) pkg.add("diff", diff);
            JsonObject track = new JsonObject();
            track.addProperty("language", lang);
            track.add("package", pkg);
            out.add(track);
        }
        return out;
    }

    static boolean jsonHistory(Provider provider, Provider.Game game, Main.Loaded loaded, Descriptors.Descriptor descriptor, Map<String, Object> root, Map<String, Object> schema, Path repoDir, Path from, int level, Map<String, String> flags) throws IOException {
        String name = flags.getOrDefault("from-name", game.biz());
        Map<String, JsonObject> oldByVersion = Dumps.sophonEraHistory(from.resolve(name + ".json"), game.biz());
        if (oldByVersion.isEmpty()) {
            System.err.println(game.biz() + ": no old history at " + from.resolve(name + ".json") + ", nothing ported");
            return false;
        }

        JsonElement extracted = new Generator(provider, loaded.gameConfigs().get(provider.gameKey(game))).render(game);
        com.google.protobuf.DynamicMessage message = Encoder.build(descriptor, root, extracted);
        Descriptors.FieldDescriptor manifestField = descriptor.findFieldByName("game_manifest");
        if (manifestField == null || !message.hasField(manifestField)) {
            System.err.println(game.biz() + ": rendered manifest has no game_manifest, nothing ported");
            return false;
        }
        com.google.protobuf.DynamicMessage current = (com.google.protobuf.DynamicMessage) message.getField(manifestField);
        Descriptors.FieldDescriptor listField = current.getDescriptorForType().findFieldByName("version_list");
        String currentVersion = Json.text(extracted.getAsJsonObject().getAsJsonObject("game_manifest"), "latest_version");
        com.google.protobuf.DynamicMessage.Builder manifestBuilder = current.toBuilder();

        int ported = 0;
        int withDiffs = 0;
        for (Map.Entry<String, JsonObject> entry : oldByVersion.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().equals(currentVersion)) continue;
            JsonObject version = jsonVersion(entry.getKey(), entry.getValue());
            if (version == null) continue;
            if (flags.containsKey("hash-index")) fillIndexHashes(version, provider, game, entry.getKey(), flags.get("hash-index"));
            manifestBuilder.addRepeatedField(listField, Json.message(listField.getMessageType(), version));
            if (diffCount(version) > 0) withDiffs++;
            ported++;
        }
        message = message.toBuilder().setField(manifestField, manifestBuilder.build()).build();

        Path file = Main.file(repoDir, game);
        Map<String, Object> append = Yml.map(schema, "append");
        if (!append.isEmpty() && !flags.containsKey("fresh") && Files.exists(file)) {
            message = Encoder.append(message, Encoder.decode(descriptor, Files.readAllBytes(file)), Yml.required(append, "field", "append"), Yml.required(append, "key", "append"), Yml.integer(append, "max", 0));
        }
        message = ordered(descriptor, message);
        if (flags.containsKey("assume-sequence")) message = sequenced(descriptor, message);
        byte[] compressed = Encoder.compress(message.toByteArray(), level);
        Files.write(file, compressed);
        System.out.printf("%-18s %2d ported + 1 current, %d with diffs -> %s (%d bytes)%n", game.biz(), ported, withDiffs, file, compressed.length);
        return true;
    }

    static JsonObject jsonVersion(String version, JsonObject old) {
        JsonObject out = new JsonObject();
        out.addProperty("version", version);
        JsonObject pkg = new JsonObject();
        JsonArray full = Dumps.oldFulls(old.getAsJsonObject("game"));
        if (!full.isEmpty()) pkg.add("full", full);
        JsonArray diff = Dumps.oldDiffs(old.getAsJsonObject("game"));
        if (!diff.isEmpty()) pkg.add("diff", diff);
        if (!pkg.isEmpty()) out.add("game_package", pkg);

        JsonArray tracks = new JsonArray();
        JsonObject audio = old.getAsJsonObject("audio");
        if (audio != null) {
            Map<String, JsonObject> byLanguage = new LinkedHashMap<>();
            for (String arm : new String[]{"full", "diff"}) {
                JsonArray rows = arm.equals("full") ? Dumps.oldFulls(audio) : Dumps.oldDiffs(audio);
                for (JsonElement e : rows) {
                    JsonObject row = e.getAsJsonObject();
                    String language = Json.text(row, "language");
                    row.remove("language");
                    JsonObject pack = byLanguage.computeIfAbsent(language, k -> new JsonObject());
                    if (!pack.has(arm)) pack.add(arm, new JsonArray());
                    pack.getAsJsonArray(arm).add(row);
                }
            }
            for (Map.Entry<String, JsonObject> e : byLanguage.entrySet()) {
                JsonObject track = new JsonObject();
                track.addProperty("language", e.getKey());
                track.add("package", e.getValue());
                tracks.add(track);
            }
        }
        if (!tracks.isEmpty()) out.add("audio_package", tracks);

        String base = Dumps.oldResourceBase(old);
        if (!base.isEmpty()) stampBase(out, base);

        String password = Dumps.oldArchivePassword(old);
        if (!password.isEmpty()) stampPassword(out, password);

        JsonObject assets = Dumps.oldAssets(old);
        if (assets != null) out.add("asset_info", assets);
        return out.size() > 1 ? out : null;
    }

    static boolean yostarVersions(Provider provider, Provider.Game game, Main.Loaded loaded, Descriptors.Descriptor descriptor, Map<String, Object> root, Map<String, Object> schema, Path repoDir, int level, Map<String, String> flags) throws IOException {
        String wanted = flags.getOrDefault("versions", "");
        if (wanted.isBlank()) {
            System.err.println(game.biz() + ": --walk yostar needs --versions");
            return false;
        }
        Map<String, Object> config = Yml.map(Yml.map(provider.yml(), "endpoints"), "config_json");
        Map<String, Object> index = Yml.map(Yml.map(provider.yml(), "endpoints"), "zip_index");
        if (config.isEmpty() || index.isEmpty()) {
            System.err.println(game.biz() + ": provider " + provider.id() + " has no config_json and zip_index endpoints");
            return false;
        }
        String region = game.regions().getFirst().id();
        JsonElement source = provider.source("game").get(region);
        JsonElement cdns = provider.source("cdn").get(region);
        if (source == null || !source.isJsonObject()) {
            System.err.println(game.biz() + ": no game source to derive a file path from");
            return false;
        }
        String latest = Json.text(source.getAsJsonObject(), "game_latest_version");
        String path = Json.text(source.getAsJsonObject(), "game_latest_file_path");
        String cdn = cdns != null && cdns.isJsonObject() ? Json.text(cdns.getAsJsonObject(), "primary_cdn") : "";

        JsonElement extracted = new Generator(provider, loaded.gameConfigs().get(provider.gameKey(game))).render(game);
        com.google.protobuf.DynamicMessage message = Encoder.build(descriptor, root, extracted);
        Descriptors.FieldDescriptor manifestField = descriptor.findFieldByName("game_manifest");
        com.google.protobuf.DynamicMessage current = (com.google.protobuf.DynamicMessage) message.getField(manifestField);
        Descriptors.FieldDescriptor listField = current.getDescriptorForType().findFieldByName("version_list");
        com.google.protobuf.DynamicMessage.Builder manifestBuilder = current.toBuilder();

        int added = 0;
        for (String version : wanted.split("[,\\s]+")) {
            if (version.isBlank() || version.equals(latest)) continue;
            String candidate = path.replace(latest, version);
            JsonObject answer;
            try {
                answer = provider.api().fetch("yostar_" + game.biz() + "_" + version, "config_json", config, game.vars(), Map.of("version", version, "file_path", candidate));
            } catch (RuntimeException e) {
                System.err.println(game.biz() + " " + version + ": config lookup failed: " + e.getMessage());
                continue;
            }
            JsonObject data = answer.getAsJsonObject("data");
            String url = data == null ? "" : Json.text(data, "url");
            if (url.isEmpty()) {
                System.err.println(game.biz() + " " + version + ": the api serves no index for it, skipped");
                continue;
            }
            JsonObject listing;
            try {
                listing = provider.api().fetch("yostar_index_" + game.biz() + "_" + version, "zip_index", index, game.vars(), Map.of("index_url", url));
            } catch (RuntimeException e) {
                System.err.println(game.biz() + " " + version + ": index unavailable: " + e.getMessage());
                continue;
            }
            long total = 0;
            JsonElement files = listing.get("file");
            if (files != null && files.isJsonArray()) {
                for (JsonElement file : files.getAsJsonArray()) {
                    if (file.isJsonObject()) total += Long.parseLong(Json.text(file.getAsJsonObject(), "size").isEmpty() ? "0" : Json.text(file.getAsJsonObject(), "size"));
                }
            }
            JsonObject row = new JsonObject();
            row.addProperty("file_url", url);
            row.addProperty("compressed_size", Long.toString(total));
            row.addProperty("decompressed_size", Long.toString(total));
            String base = Json.text(listing, "source");
            if (!cdn.isEmpty() && !base.isEmpty()) row.addProperty("resource_base_url", cdn + base);
            JsonArray full = new JsonArray();
            full.add(row);
            JsonObject pkg = new JsonObject();
            pkg.add("full", full);
            JsonObject entry = new JsonObject();
            entry.addProperty("version", version);
            entry.add("game_package", pkg);
            manifestBuilder.addRepeatedField(listField, Json.message(listField.getMessageType(), entry));
            added++;
        }
        if (added == 0) {
            System.err.println(game.biz() + ": no version added");
            return false;
        }
        message = message.toBuilder().setField(manifestField, manifestBuilder.build()).build();
        Path file = Main.file(repoDir, game);
        Map<String, Object> append = Yml.map(schema, "append");
        if (!append.isEmpty() && !flags.containsKey("fresh") && Files.exists(file)) {
            message = Encoder.append(message, Encoder.decode(descriptor, Files.readAllBytes(file)), Yml.required(append, "field", "append"), Yml.required(append, "key", "append"), Yml.integer(append, "max", 0));
        }
        message = ordered(descriptor, message);
        byte[] compressed = Encoder.compress(message.toByteArray(), level);
        Files.write(file, compressed);
        System.out.printf("%-18s %2d pulled + 1 current -> %s (%d bytes)%n", game.biz(), added, file, compressed.length);
        return true;
    }

    static com.google.protobuf.DynamicMessage ordered(Descriptors.Descriptor descriptor, com.google.protobuf.DynamicMessage message) {
        Descriptors.FieldDescriptor manifestField = descriptor.findFieldByName("game_manifest");
        if (manifestField == null || !message.hasField(manifestField)) return message;
        com.google.protobuf.DynamicMessage game = (com.google.protobuf.DynamicMessage) message.getField(manifestField);
        Descriptors.FieldDescriptor listField = game.getDescriptorForType().findFieldByName("version_list");
        if (listField == null || game.getRepeatedFieldCount(listField) < 2) return message;
        Descriptors.FieldDescriptor versionField = listField.getMessageType().findFieldByName("version");

        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < game.getRepeatedFieldCount(listField); i++) entries.add(game.getRepeatedField(listField, i));
        entries.sort((left, right) -> Json.compareVersions(String.valueOf(((com.google.protobuf.Message) right).getField(versionField)), String.valueOf(((com.google.protobuf.Message) left).getField(versionField))));

        com.google.protobuf.DynamicMessage.Builder builder = game.toBuilder().clearField(listField);
        for (Object entry : entries) builder.addRepeatedField(listField, entry);
        return message.toBuilder().setField(manifestField, builder.build()).build();
    }

    static com.google.protobuf.DynamicMessage sequenced(Descriptors.Descriptor descriptor, com.google.protobuf.DynamicMessage message) {
        Descriptors.FieldDescriptor manifestField = descriptor.findFieldByName("game_manifest");
        if (manifestField == null || !message.hasField(manifestField)) return message;
        com.google.protobuf.DynamicMessage manifest = (com.google.protobuf.DynamicMessage) message.getField(manifestField);
        Descriptors.FieldDescriptor listField = manifest.getDescriptorForType().findFieldByName("version_list");
        if (listField == null || manifest.getRepeatedFieldCount(listField) < 2) return message;
        Descriptors.FieldDescriptor sequenceField = listField.getMessageType().findFieldByName("build_sequence");
        if (sequenceField == null) return message;

        com.google.protobuf.DynamicMessage first = (com.google.protobuf.DynamicMessage) manifest.getRepeatedField(listField, 0);
        long latest;
        try {
            latest = Long.parseLong((String) first.getField(sequenceField));
        } catch (NumberFormatException e) {
            System.err.println("--assume-sequence: the current entry reports no build sequence, left alone");
            return message;
        }
        com.google.protobuf.DynamicMessage.Builder manifestBuilder = manifest.toBuilder();
        for (int i = 1; i < manifestBuilder.getRepeatedFieldCount(listField); i++) {
            com.google.protobuf.DynamicMessage entry = (com.google.protobuf.DynamicMessage) manifestBuilder.getRepeatedField(listField, i);
            if (!((String) entry.getField(sequenceField)).isEmpty()) continue;
            long sequence = latest - i;
            if (sequence < 1) break;
            manifestBuilder.setRepeatedField(listField, i, entry.toBuilder().setField(sequenceField, Long.toString(sequence)).build());
        }
        return message.toBuilder().setField(manifestField, manifestBuilder.build()).build();
    }

    static void stampScenario(JsonObject version, String scenario) {
        JsonObject pkg = version.getAsJsonObject("game_package");
        if (pkg != null) {
            stampRows(pkg.getAsJsonArray("full"), scenario);
            stampRows(pkg.getAsJsonArray("diff"), scenario);
            JsonArray variants = pkg.getAsJsonArray("variants");
            if (variants != null) {
                for (JsonElement variant : variants) {
                    JsonObject inner = variant.getAsJsonObject().getAsJsonObject("game_package");
                    if (inner == null) continue;
                    stampRows(inner.getAsJsonArray("full"), scenario);
                    stampRows(inner.getAsJsonArray("diff"), scenario);
                }
            }
        }
        JsonArray tracks = version.getAsJsonArray("audio_package");
        if (tracks == null) return;
        for (JsonElement track : tracks) {
            JsonObject inner = track.getAsJsonObject().getAsJsonObject("package");
            if (inner == null) continue;
            stampRows(inner.getAsJsonArray("full"), scenario);
            stampRows(inner.getAsJsonArray("diff"), scenario);
        }
    }

    static void stampRows(JsonArray rows, String scenario) {
        if (rows == null) return;
        for (JsonElement row : rows) {
            JsonArray list = new JsonArray();
            list.add(scenario);
            row.getAsJsonObject().add("scenarios", list);
        }
    }

    static void stampBase(JsonObject version, String base) {
        JsonObject pkg = version.getAsJsonObject("game_package");
        if (pkg == null) return;
        for (String arm : new String[]{"full", "diff"}) {
            JsonArray rows = pkg.getAsJsonArray(arm);
            if (rows == null) continue;
            for (JsonElement row : rows) {
                JsonObject object = row.getAsJsonObject();
                if (Json.text(object, "resource_base_url").isEmpty()) object.addProperty("resource_base_url", base);
            }
        }
    }

    static void stampPassword(JsonObject version, String password) {
        JsonObject pkg = version.getAsJsonObject("game_package");
        if (pkg == null) return;
        JsonArray diff = pkg.getAsJsonArray("diff");
        if (diff == null) return;
        for (JsonElement row : diff) row.getAsJsonObject().addProperty("archive_password", password);
    }

    static boolean atLeast(String version, String floor) {
        if (floor.isEmpty()) return true;
        String[] a = version.split("\\.");
        String[] b = floor.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? Json.parseOrZero(a[i]) : 0;
            int right = i < b.length ? Json.parseOrZero(b[i]) : 0;
            if (left != right) return left > right;
        }
        return true;
    }

    static int diffCount(JsonObject version) {
        int total = 0;
        JsonObject pkg = version.getAsJsonObject("game_package");
        if (pkg != null && pkg.has("diff")) total += pkg.getAsJsonArray("diff").size();
        if (pkg != null && pkg.has("variants")) {
            for (JsonElement variant : pkg.getAsJsonArray("variants")) {
                JsonObject inner = variant.getAsJsonObject().getAsJsonObject("game_package");
                if (inner != null && inner.has("diff")) total += inner.getAsJsonArray("diff").size();
            }
        }
        if (version.has("audio_package")) {
            for (JsonElement track : version.getAsJsonArray("audio_package")) {
                JsonObject inner = track.getAsJsonObject().getAsJsonObject("package");
                if (inner != null && inner.has("diff")) total += inner.getAsJsonArray("diff").size();
            }
        }
        return total;
    }

    static JsonArray releases(Provider provider, Provider.Game game, int pages, List<String> wanted) {
        Map<String, String> vars = game.vars();
        List<String> repos = new ArrayList<>();
        repos.add(vars.getOrDefault("repo", ""));
        for (String extra : vars.getOrDefault("legacy_repos", "").split("[,\\s]+")) {
            if (!extra.isBlank()) repos.add(extra.trim());
        }

        List<String> outstanding = new ArrayList<>();
        for (String want : wanted) outstanding.add(want.toLowerCase());

        Map<String, JsonElement> byVersion = new LinkedHashMap<>();
        for (String repo : repos) {
            if (repo.isEmpty() || outstanding.isEmpty()) continue;
            for (int page = 1; page <= pages; page++) {
                Map<String, Object> endpoint = Map.of("url", vars.getOrDefault("api", "https://api.github.com") + "/repos/" + repo + "/releases?per_page=100&page=" + page);
                JsonElement body;
                try {
                    body = provider.api().fetchElement(repo.replace('/', '_') + "_p" + page, "releases", endpoint, vars, Map.of());
                } catch (Api.ApiException e) {
                    System.err.println("warn: " + repo + " page " + page + ": " + e.getMessage());
                    break;
                }
                if (!body.isJsonArray() || body.getAsJsonArray().isEmpty()) break;
                for (JsonElement release : body.getAsJsonArray()) {
                    String version = versionOf(release, vars);
                    if (version.isEmpty()) continue;
                    if (byVersion.putIfAbsent(version.toLowerCase(), release) == null) outstanding.remove(version.toLowerCase());
                }
                if (outstanding.isEmpty()) break;
            }
        }

        JsonArray out = new JsonArray();
        for (String want : wanted) {
            JsonElement release = byVersion.get(want.toLowerCase());
            if (release == null) {
                System.err.println("warn: " + game.biz() + " " + want + ": no release upstream, skipped");
                continue;
            }
            out.add(release);
        }
        return out;
    }

    static void reportGaps(JsonArray releases, Map<String, String> vars, String biz) {
        for (String arch : List.of("x86_64", "aarch64")) {
            String assetNeedle = vars.getOrDefault(arch + "_asset", "");
            String validationNeedle = vars.getOrDefault(arch + "_validation", "");
            if (assetNeedle.isEmpty()) continue;

            for (JsonElement release : releases) {
                String version = versionOf(release, vars);
                JsonElement archive = Selector.parse("assets[name~=" + assetNeedle + "]").first(release);
                JsonElement validation = validationNeedle.isEmpty() ? null : Selector.parse("assets[name~=" + validationNeedle + "]").first(release);
                if (archive == null) continue;
                List<String> gaps = new ArrayList<>();
                if (hasDigest(archive)) gaps.add("archive_hash");
                if (validation == null) gaps.add("validation_file_url");
                else if (hasDigest(validation)) gaps.add("validation_file_hash");
                if (!gaps.isEmpty()) System.err.println("gap: " + biz + " " + version + " " + arch + ": " + String.join(", ", gaps));
            }
        }
    }

    static boolean hasDigest(JsonElement asset) {
        JsonElement digest = asset.getAsJsonObject().get("digest");
        return digest == null || digest.isJsonNull() || digest.getAsString().isEmpty();
    }

    static String versionOf(JsonElement release, Map<String, String> vars) {
        String needle = vars.getOrDefault("x86_64_asset", "");
        if (needle.isEmpty() || !release.isJsonObject()) return "";
        JsonElement asset = Selector.parse("assets[name~=" + needle + "]").first(release);
        if (asset == null || !asset.isJsonObject()) return "";

        String name = asset.getAsJsonObject().get("name").getAsString();
        Matcher m = Pattern.compile(vars.getOrDefault("version_pattern", ".*")).matcher(name);
        if (!m.find()) return "";
        String out = vars.getOrDefault("version_template", "{0}");
        for (int i = m.groupCount(); i >= 0; i--) {
            out = out.replace("{" + i + "}", m.group(i) == null ? "" : m.group(i));
        }
        return out;
    }

    static void fillIndexHashes(JsonObject version, Provider provider, Provider.Game game, String tag, String dir) {
        JsonObject pkg = version.getAsJsonObject("game_package");
        if (pkg == null) return;
        String agent = Yml.string(Yml.map(provider.yml(), "http"), "user_agent", "mobius/1.0");
        for (String arm : new String[]{"full", "diff"}) {
            JsonArray rows = pkg.getAsJsonArray(arm);
            if (rows == null) continue;
            int index = 0;
            for (JsonElement element : rows) {
                JsonObject row = element.getAsJsonObject();
                index++;
                if (!Json.text(row, "file_hash").isEmpty() || Json.text(row, "file_url").isEmpty()) continue;
                String name = game.biz() + "-" + tag + "-" + arm + index + ".json";
                String md5 = md5Of(Json.text(row, "file_url"), agent, Path.of(dir).resolve(name));
                if (md5.isEmpty()) continue;
                row.addProperty("file_hash", "md5:" + md5);
                System.out.println("  hashed " + tag + " " + arm + " " + md5);
            }
        }
    }

    static String md5Of(String url, String agent, Path save) {
        try {
            Files.createDirectories(save.getParent());
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("User-Agent", agent).timeout(java.time.Duration.ofMinutes(5)).build();
            java.net.http.HttpResponse<Path> response = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build().send(request, java.net.http.HttpResponse.BodyHandlers.ofFile(save));
            if (response.statusCode() / 100 != 2) {
                System.err.println(url + ": http " + response.statusCode() + ", hash left empty");
                return "";
            }
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            try (java.io.InputStream stream = Files.newInputStream(save)) {
                byte[] buffer = new byte[1 << 16];
                for (int read; (read = stream.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (Exception e) {
            System.err.println(url + ": " + e.getMessage() + ", hash left empty");
            return "";
        }
    }
}
