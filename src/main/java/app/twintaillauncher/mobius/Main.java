package app.twintaillauncher.mobius;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;
import app.twintaillauncher.mobius.gen.Encoder;
import app.twintaillauncher.mobius.gen.Generator;
import app.twintaillauncher.mobius.tools.Hashes;
import app.twintaillauncher.mobius.tools.Port;
import app.twintaillauncher.mobius.tools.Restore;
import app.twintaillauncher.mobius.gen.Provider;
import app.twintaillauncher.mobius.net.Api;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.protobuf.Descriptors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Main {
    static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    flags.put(arg.substring(2, eq), arg.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    flags.put(arg.substring(2), args[++i]);
                } else {
                    flags.put(arg.substring(2), "true");
                }
            } else {
                positional.add(arg);
            }
        }

        String command = positional.isEmpty() ? "help" : positional.getFirst();
        List<String> rest = positional.isEmpty() ? List.of() : positional.subList(1, positional.size());

        try {
            String configs = flags.get("configs");
            if (configs != null) Config.from(configs);
            return switch (command) {
                case "games" -> games(flags);
                case "extract" -> extract(rest, flags);
                case "encode" -> encode(rest, flags);
                case "port" -> Port.run(rest, flags);
                case "decode" -> decode(rest, flags);
                case "fill-hashes" -> Hashes.run(rest, flags);
                case "restore" -> Restore.run(rest, flags);
                case "help", "--help", "-h" -> help();
                default -> {
                    System.err.println("unknown command: " + command);
                    help();
                    yield 2;
                }
            };
        } catch (Config.ConfigException | Api.ApiException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            System.err.println("io error: " + e.getMessage());
            return 1;
        }
    }

    private static int help() {
        System.out.println("""
                mobius | manifest generator for TwintailLauncher

                usage: java -jar mobius.jar <command> [options] [biz...]

                name one or more biz to work on just those, or name none to do the lot

                commands:
                  games                 show what the apis are serving right now
                  extract [biz...]      print the json a schema pulls out, before it is encoded
                  encode [biz...]       build <biz>.ttm into --out/<repository>/
                  port [biz...]         pull old versions back in from the json dumps in --from
                  decode [biz...]       turn .ttm files back into json you can read
                  restore               rebuild a .ttm tree out of a decode dump
                  fill-hashes [biz...]  fill in file hashes an old dump never carried
                  help                  this

                selection:
                  --provider <id>       just this provider, by id or id/instance
                  --repository <id>     just the entries that write to this repository, and drop
                                        providers with nothing in it before they hit the api

                encode and port:
                  --out <dir>           where the manifest tree lives (default: manifests)
                  --level <n>           zstd level (default: 19)
                  --fresh               start the version list over instead of adding to it
                  --from <dir>          the json dumps port reads its version list out of
                  --pages <n>           release pages to check per repo; it stops early once it
                                        has every version it was after (default: 3)
                  --walk sophon         port every sophon tag the api still answers for, one
                                        version each, taking diffs from --from when the old
                                        entry is new enough to have them
                  --assume-scenario <id>  scenario to stamp on walked versions, for games where
                                        there is no way left to tell which one they used
                  --scenario-from <v>   oldest version --assume-scenario applies to
                  --walk json           port every version straight out of the --from json, for
                                        apis that only describe whatever build is live
                  --from-name <base>    read <base>.json instead of <biz>.json
                  --walk yostar         ask the api for the versions in --versions, for providers
                                        that will serve any version but list none
                  --versions <a,b,c>    the versions --walk yostar should ask for
                  --hash-index <dir>    download the index files a dump left unhashed into <dir>
                                        and keep their md5, which is the hash the vendor
                                        publishes for them anyway
                  --assume-sequence     count build_sequence down by one per walked version, for
                                        apis that only report it for the live build

                restore:
                  --from <dir>          a json tree from decode --json-out, built back into .ttm
                                        files under --out; this is how a wiped tree comes back

                fill-hashes:
                  --write               actually rewrite the .ttm files; without it you only see
                                        what the sophon urls say the empty hashes should be

                decode:
                  --json-out <dir>      write the json there, same layout as the manifest tree,
                                        instead of printing it
                  --message <name>      message to decode against (default: TwintailManifest)

                json output:
                  --compact             one line instead of pretty printed

                api:
                  --offline <dir>       replay saved responses instead of calling anything
                  --save-responses <dir>  keep every response this run gets, ready for --offline

                configs:
                  --configs <dir>       use this config tree instead of ./configs next to the jar

                encode adds to the manifest already on disk, so ported history is not lost.
                a full run rewrites repository.json. a run narrowed by a biz or --provider
                merges into it instead, and so does any run a provider failed in, so the
                entries that run never rebuilt stay listed.

                configs come from --configs, or from ./configs next to the jar""");
        return 0;
    }

    private static int games(Map<String, String> flags) {
        Loadout all = load(flags);
        for (Loaded loaded : all.loaded()) {
            System.out.println("provider: " + loaded.provider().id());
            for (Provider.Game game : loaded.provider().games()) {
                System.out.printf("  %-14s %-12s %-22s regions=%d%n", game.biz(), game.status(), game.primaryId(), game.regions().size());
                for (Provider.Region region : game.regions()) {
                    if (game.regions().size() == 1 && region.code().isEmpty()) continue;
                    System.out.printf("      %-20s %-22s %s%n", region.code(), region.id(), region.description());
                }
            }
        }
        return report(all, 0);
    }

    private static int extract(List<String> bizFilter, Map<String, String> flags) {
        Gson gson = gson(flags);
        Loadout all = load(flags);
        try {
            for (Loaded loaded : all.loaded()) {
                for (Provider.Game game : loaded.provider().games()) {
                    if (!bizFilter.isEmpty() && !bizFilter.contains(game.biz())) continue;
                    JsonElement extracted = new Generator(loaded.provider(), loaded.gameConfigs().get(loaded.provider().gameKey(game))).render(game);
                    System.out.println(gson.toJson(extracted));
                }
            }
        } finally {
            dumpResponses(all, flags);
        }
        return report(all, 0);
    }

    public static void dumpResponses(Loadout all, Map<String, String> flags) {
        String save = flags.get("save-responses");
        if (save == null) return;
        for (Loaded entry : all.loaded()) {
            Provider provider = entry.provider();
            Path path = Path.of(save);
            provider.api().dumpTo(provider.instanceId().isEmpty() ? path : path.resolve(provider.instanceId()));
        }
    }

    private static int encode(List<String> bizFilter, Map<String, String> flags) throws IOException {
        String repoFilter = flags.get("repository");
        Path outDir = Path.of(flags.getOrDefault("out", "manifests"));
        int level = Integer.parseInt(flags.getOrDefault("level", "19"));
        Files.createDirectories(outDir);

        int written = 0;
        long raw = 0;
        long packed = 0;
        Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
        Loadout all = load(flags);
        List<String> failed = new ArrayList<>(all.failed());
        for (Loaded loaded : all.loaded()) {
            Map<String, Object> schema = loaded.provider().schema();
            String messageName = Yml.string(schema, "message", "mobius.Game");
            Descriptors.Descriptor descriptor;
            try {
                descriptor = Encoder.descriptorFor(messageName);
            } catch (Config.ConfigException e) {
                System.err.println("provider " + loaded.provider().id() + " skipped: " + e.getMessage());
                failed.add(loaded.provider().id());
                continue;
            }
            Map<String, Object> root = rootNode(schema);
            Map<String, Object> append = Yml.map(schema, "append");

            for (Provider.Game game : loaded.provider().games()) {
                if (!bizFilter.isEmpty() && !bizFilter.contains(game.biz())) continue;
                String repository = game.repository();
                if (repoFilter != null && !repoFilter.equals(repository)) continue;
                Path repoDir = outDir.resolve(repository);
                Files.createDirectories(repoDir);
                JsonElement extracted = new Generator(loaded.provider(), loaded.gameConfigs().get(loaded.provider().gameKey(game))).render(game);

                com.google.protobuf.DynamicMessage message = Encoder.build(descriptor, root, extracted);
                if (!append.isEmpty() && !flags.containsKey("fresh") && Files.exists(file(repoDir, game))) {
                    message = Encoder.append(message, Encoder.decode(descriptor, Files.readAllBytes(file(repoDir, game))), Yml.required(append, "field", "append"), Yml.required(append, "key", "append"), Yml.integer(append, "max", 0));
                }
                byte[] serialized = message.toByteArray();
                byte[] compressed = Encoder.compress(serialized, level);
                Path file = file(repoDir, game);
                Files.write(file, compressed);

                String region = loaded.provider().var("region", loaded.provider().instanceId());
                if (region.isEmpty()) region = "global";

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", game.biz());
                entry.put("name", displayName(extracted, game, loaded.gameConfigs().get(loaded.provider().gameKey(game))));
                entry.put("game_key", gameKey(game.biz(), region));
                entry.put("region", region);
                entry.put("region_name", loaded.provider().var("region_name", region));
                entry.put("publisher", loaded.provider().publisher());
                entry.put("manifest", file.getFileName().toString());
                entry.put("size", compressed.length);

                List<Map<String, String>> servers = new ArrayList<>();
                for (Provider.Region r : game.regions()) {
                    if (r.code().isEmpty()) continue;
                    Map<String, String> server = new LinkedHashMap<>();
                    server.put("display_name", r.description().isEmpty() ? r.name() : r.description());
                    server.put("id", r.code());
                    servers.add(server);
                }
                if (!servers.isEmpty()) entry.put("servers", servers);
                index.computeIfAbsent(repository, k -> new ArrayList<>()).add(entry);

                raw += serialized.length;
                packed += compressed.length;
                written++;
                System.out.printf("%-19s %9d -> %8d bytes  %s%n", game.biz(), serialized.length, compressed.length, file);
            }
        }
        if (written == 0) {
            System.err.println("nothing matched" + (bizFilter.isEmpty() ? "" : ": " + bizFilter));
            return 1;
        }
        System.out.printf("%d manifests, %d -> %d bytes (%.1f%% of protobuf)%n", written, raw, packed, 100.0 * packed / raw);
        boolean merge = !bizFilter.isEmpty() || !failed.isEmpty() || flags.containsKey("provider");
        for (Map.Entry<String, List<Map<String, Object>>> repo : index.entrySet()) {
            writeRepository(Config.load(), repo.getKey(), outDir.resolve(repo.getKey()), repo.getValue(), merge);
        }
        return report(new Loadout(all.loaded(), failed), 0);
    }

    private static void writeRepository(Config config, String id, Path outDir, List<Map<String, Object>> entries, boolean merge) throws IOException {
        Map<String, Object> meta = Yml.map(Yml.map(config.root(), "repositories"), id);
        if (meta.isEmpty()) throw new Config.ConfigException("mobius.yml declares no repository '" + id + "'");

        List<Object> manifests = new ArrayList<>();
        for (Map<String, Object> entry : entries) manifests.add(entry.get("manifest"));

        Map<String, Object> entryTemplate = Yml.map(meta, "entry");
        String groupBy = Yml.string(meta, "group_by", null);
        List<Object> rendered = new ArrayList<>();

        if (groupBy == null) {
            for (Map<String, Object> entry : entries) rendered.add(render(entryTemplate, entry));
        } else {
            Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
            for (Map<String, Object> entry : entries) {
                groups.computeIfAbsent(String.valueOf(entry.get(groupBy)), k -> new ArrayList<>()).add(entry);
            }
            Map<String, Object> groupTemplate = Yml.map(meta, "group");
            for (Map.Entry<String, List<Map<String, Object>>> group : groups.entrySet()) {
                List<Object> members = new ArrayList<>();
                for (Map<String, Object> entry : group.getValue()) members.add(render(entryTemplate, entry));

                Map<String, Object> label = group.getValue().stream().filter(member -> "global".equals(member.get("region"))).findFirst().orElse(group.getValue().getFirst());
                Map<String, Object> vars = new LinkedHashMap<>(label);
                vars.put("key", group.getKey());
                vars.put("members", members);
                rendered.add(render(groupTemplate, vars));
            }
        }

        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("manifests", manifests);
        vars.put("entries", rendered);
        Object document = render(Yml.map(meta, "output"), vars);

        Path file = outDir.resolve("repository.json");
        JsonElement fresh = new Gson().toJsonTree(document);
        if (merge && Files.exists(file)) {
            JsonElement existing = com.google.gson.JsonParser.parseString(Files.readString(file));
            if (existing.isJsonObject() && fresh.isJsonObject()) fresh = mergeJson(existing.getAsJsonObject(), fresh.getAsJsonObject());
        }
        if (fresh.isJsonObject()) pruneMissing(fresh.getAsJsonObject(), outDir, meta);
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(fresh) + "\n", StandardCharsets.UTF_8);
        String entriesField = templateField(Yml.map(meta, "output"), "@entries");
        String manifestsField = templateField(Yml.map(meta, "output"), "@manifests");
        int wroteEntries = counted(fresh, entriesField, rendered.size());
        int wroteManifests = counted(fresh, manifestsField, manifests.size());
        System.out.println("wrote " + file + "  (" + wroteEntries + " entries, " + wroteManifests + " manifests" + (merge ? ", " + rendered.size() + " from this run)" : ")"));
    }

    private static void pruneMissing(JsonObject document, Path outDir, Map<String, Object> meta) {
        for (Map.Entry<String, JsonElement> e : document.entrySet()) {
            if (!e.getValue().isJsonArray()) continue;
            JsonArray kept = new JsonArray();
            for (JsonElement item : e.getValue().getAsJsonArray()) {
                boolean manifest = item.isJsonPrimitive() && item.getAsString().endsWith(".ttm");
                if (manifest && !Files.exists(outDir.resolve(item.getAsString()))) continue;
                kept.add(item);
            }
            e.setValue(kept);
        }

        String entriesField = templateField(Yml.map(meta, "output"), "@entries");
        String idField = templateField(Yml.map(meta, "entry"), "@id");
        if (entriesField == null || idField == null) return;
        JsonElement entries = document.get(entriesField);
        if (entries == null || !entries.isJsonArray()) return;
        String membersField = templateField(Yml.map(meta, "group"), "@members");

        JsonArray keptEntries = new JsonArray();
        for (JsonElement item : entries.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                keptEntries.add(item);
                continue;
            }
            JsonObject entry = item.getAsJsonObject();
            if (membersField == null) {
                if (!missingManifest(entry, idField, outDir)) keptEntries.add(entry);
                continue;
            }
            JsonElement members = entry.get(membersField);
            if (members == null || !members.isJsonArray()) {
                keptEntries.add(entry);
                continue;
            }
            JsonArray keptMembers = new JsonArray();
            for (JsonElement member : members.getAsJsonArray()) {
                if (member.isJsonObject() && missingManifest(member.getAsJsonObject(), idField, outDir)) continue;
                keptMembers.add(member);
            }
            if (keptMembers.isEmpty()) continue;
            entry.add(membersField, keptMembers);
            keptEntries.add(entry);
        }
        document.add(entriesField, keptEntries);
    }

    private static int counted(JsonElement document, String field, int fallback) {
        if (field == null || !document.isJsonObject()) return fallback;
        JsonElement value = document.getAsJsonObject().get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray().size() : fallback;
    }

    private static boolean missingManifest(JsonObject object, String idField, Path outDir) {
        JsonElement id = object.get(idField);
        if (id == null || !id.isJsonPrimitive()) return false;
        return !Files.exists(outDir.resolve(id.getAsString() + ".ttm"));
    }

    private static String templateField(Map<String, Object> template, String token) {
        for (Map.Entry<String, Object> field : template.entrySet()) {
            if (token.equals(field.getValue())) return field.getKey();
        }
        return null;
    }

    private static JsonObject mergeJson(JsonObject existing, JsonObject fresh) {
        JsonObject out = existing.deepCopy();
        for (Map.Entry<String, JsonElement> e : fresh.entrySet()) {
            JsonElement was = out.get(e.getKey());
            if (was != null && was.isJsonArray() && e.getValue().isJsonArray()) {
                out.add(e.getKey(), mergeArray(was.getAsJsonArray(), e.getValue().getAsJsonArray()));
            } else if (was != null && was.isJsonObject() && e.getValue().isJsonObject()) {
                out.add(e.getKey(), mergeJson(was.getAsJsonObject(), e.getValue().getAsJsonObject()));
            } else {
                out.add(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static JsonArray mergeArray(JsonArray existing, JsonArray fresh) {
        JsonArray out = new JsonArray();
        List<String> keys = new ArrayList<>();
        for (JsonElement e : existing) {
            out.add(e);
            keys.add(identity(e));
        }
        for (JsonElement e : fresh) {
            int at = keys.indexOf(identity(e));
            if (at < 0) {
                out.add(e);
                keys.add(identity(e));
            } else if (e.isJsonObject() && out.get(at).isJsonObject()) {
                out.set(at, mergeJson(out.get(at).getAsJsonObject(), e.getAsJsonObject()));
            } else {
                out.set(at, e);
            }
        }
        return out;
    }

    private static String identity(JsonElement element) {
        if (!element.isJsonObject()) return element.toString();
        JsonObject object = element.getAsJsonObject();
        for (String key : List.of("id", "key", "display_name", "name")) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) return key + "=" + value.getAsString();
        }
        return object.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object render(Object template, Map<String, Object> vars) {
        switch (template) {
            case String text when text.startsWith("@") -> {
                return vars.get(text.substring(1));
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                    Object value = render(e.getValue(), vars);
                    if (value != null) out.put(e.getKey(), value);
                }
                return out;
            }
            case List<?> list -> {
                List<Object> out = new ArrayList<>();
                for (Object item : list) {
                    Object value = render(item, vars);
                    if (value != null) out.add(value);
                }
                return out;
            }
            case null, default -> {
                return template;
            }
        }
    }

    private static String displayName(JsonElement extracted, Provider.Game game, Map<String, Object> gameConfig) {
        String declared = gameConfig == null ? "" : Yml.string(Yml.map(gameConfig, "static"), "display_name", "");
        if (!declared.isEmpty()) return declared;
        if (extracted.isJsonObject()) {
            for (String arm : List.of("game_manifest", "runner_manifest")) {
                JsonElement manifest = extracted.getAsJsonObject().get(arm);
                if (manifest == null || !manifest.isJsonObject()) continue;
                JsonElement name = manifest.getAsJsonObject().get("display_name");
                if (name != null && name.isJsonPrimitive() && !name.getAsString().isEmpty()) {
                    return name.getAsString();
                }
            }
        }
        String region = game.regions().getFirst().name();
        return region.isEmpty() ? game.biz() : region;
    }

    private static int decode(List<String> bizFilter, Map<String, String> flags) throws IOException {
        Path outDir = Path.of(flags.getOrDefault("out", "manifests"));
        String repoFilter = flags.get("repository");
        String jsonOut = flags.get("json-out");
        Descriptors.Descriptor descriptor = Encoder.descriptorFor(flags.getOrDefault("message", "TwintailManifest"));

        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(outDir)) throw new Config.ConfigException(outDir + " is not a directory");
        try (var repos = Files.list(outDir)) {
            for (Path repo : repos.sorted().toList()) {
                if (!Files.isDirectory(repo)) continue;
                if (repoFilter != null && !repo.getFileName().toString().equals(repoFilter)) continue;
                try (var manifests = Files.list(repo)) {
                    for (Path file : manifests.sorted().toList()) {
                        String name = file.getFileName().toString();
                        if (!name.endsWith(".ttm")) continue;
                        if (!bizFilter.isEmpty() && !bizFilter.contains(name.substring(0, name.length() - 4))) continue;
                        files.add(file);
                    }
                }
            }
        }
        if (files.isEmpty()) {
            System.err.println("no .ttm files matched");
            return 1;
        }

        Gson pretty = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        for (Path file : files) {
            JsonElement json = toJson(Encoder.decode(descriptor, Files.readAllBytes(file)));
            String text = pretty.toJson(json);
            if (jsonOut == null) {
                System.out.println("=== " + file);
                System.out.println(text);
            } else {
                Path target = Path.of(jsonOut).resolve(outDir.relativize(file).toString().replaceAll("\\.ttm$", ".json"));
                Files.createDirectories(target.getParent());
                Files.writeString(target, text + System.lineSeparator(), StandardCharsets.UTF_8);
                System.out.printf("%-42s -> %s%n", file, target);
            }
        }
        return 0;
    }

    private static JsonElement toJson(com.google.protobuf.Message message) {
        JsonObject out = new JsonObject();
        for (Map.Entry<Descriptors.FieldDescriptor, Object> e : message.getAllFields().entrySet()) {
            Descriptors.FieldDescriptor field = e.getKey();
            if (field.isRepeated()) {
                JsonArray array = new JsonArray();
                for (Object item : (List<?>) e.getValue()) array.add(jsonValue(field, item));
                out.add(field.getName(), array);
            } else {
                out.add(field.getName(), jsonValue(field, e.getValue()));
            }
        }
        return out;
    }

    private static JsonElement jsonValue(Descriptors.FieldDescriptor field, Object value) {
        return switch (field.getJavaType()) {
            case MESSAGE -> toJson((com.google.protobuf.Message) value);
            case ENUM -> new JsonPrimitive(((Descriptors.EnumValueDescriptor) value).getName());
            case LONG -> new JsonPrimitive(String.valueOf(value));
            case BOOLEAN -> new JsonPrimitive((Boolean) value);
            case INT, FLOAT, DOUBLE -> new JsonPrimitive((Number) value);
            case BYTE_STRING -> new JsonPrimitive(Base64.getEncoder().encodeToString(((com.google.protobuf.ByteString) value).toByteArray()));
            default -> new JsonPrimitive(String.valueOf(value));
        };
    }

    public static Path file(Path outDir, Provider.Game game) {
        return outDir.resolve(game.biz() + ".ttm");
    }

    private static String gameKey(String biz, String region) {
        String suffix = "_" + region;
        return !region.isEmpty() && biz.endsWith(suffix) ? biz.substring(0, biz.length() - suffix.length()) : biz;
    }

    public static Map<String, Object> rootNode(Map<String, Object> schema) {
        Map<String, Object> fields = Yml.map(schema, "fields");
        if (fields.isEmpty()) throw new Config.ConfigException("schema has no fields: block");
        return fields;
    }

    public static int report(Loadout all, int code) {
        if (all.failed().isEmpty()) return code;
        System.err.println("failed providers: " + String.join(", ", all.failed()));
        return 1;
    }

    private static boolean declaresRepository(Map<String, Object> yml, String repository) {
        String providerLevel = Yml.string(yml, "repository", "games");
        if (providerLevel.equals(repository)) return true;
        for (Object entry : Yml.list(yml, "catalog")) {
            if (Yml.string(Yml.asMap(entry), "repository", providerLevel).equals(repository)) return true;
        }
        return false;
    }

    public static Loadout load(Map<String, String> flags) {
        Config config = Config.load();
        String offline = flags.get("offline");
        Path offlineDir = offline == null ? null : Path.of(offline);
        String only = flags.get("provider");
        String repoFilter = flags.get("repository");
        String save = flags.get("save-responses");

        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> ymls = new ArrayList<>();
        List<Map<String, Object>> instanceCfgs = new ArrayList<>();
        List<Path> offlines = new ArrayList<>();
        boolean matched = false;

        for (Map<String, Object> yml : config.providers()) {
            String base = String.valueOf(yml.get("id"));
            if (repoFilter != null && !declaresRepository(yml, repoFilter)) continue;

            List<Object> instances = Yml.list(yml, "instances");
            if (instances.isEmpty()) instances = List.of(Map.of());

            for (Object entry : instances) {
                Map<String, Object> instance = Yml.asMap(entry);
                String name = Yml.string(instance, "id", "");
                String id = name.isEmpty() ? base : base + "/" + name;
                if (only != null && !only.equals(id) && !only.equals(base)) continue;
                matched = true;

                ids.add(id);
                ymls.add(yml);
                instanceCfgs.add(instance);
                offlines.add(offlineDir == null || name.isEmpty() ? offlineDir : offlineDir.resolve(name));
            }
        }

        List<Loaded> loaded = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Loaded>> futures = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                Map<String, Object> yml = ymls.get(i);
                Map<String, Object> instance = instanceCfgs.get(i);
                Path instanceOffline = offlines.get(i);
                String name = Yml.string(instance, "id", "");
                futures.add(pool.submit(() -> {
                    Map<String, Object> http = Api.mergeHttp(Api.mergeHttp(config.http(), Yml.map(yml, "http")), Yml.map(instance, "http"));
                    Map<String, Object> auth = Yml.map(instance, "auth").isEmpty() ? Yml.map(yml, "auth") : Yml.map(instance, "auth");

                    Api api = new Api(http, auth, instanceOffline);
                    Provider provider = Provider.load(config, yml, api, Yml.map(instance, "vars"), name);
                    Loaded out = new Loaded(provider, provider.gameConfigs(config));
                    if (save != null) {
                        Path path = Path.of(save);
                        api.dumpTo(name.isEmpty() ? path : path.resolve(name));
                    }
                    return out;
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    loaded.add(futures.get(i).get());
                } catch (ExecutionException e) {
                    System.err.println("provider " + ids.get(i) + " failed: " + e.getCause().getMessage());
                    failed.add(ids.get(i));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!matched) {
            throw new Config.ConfigException("no provider matched" + (only == null ? "" : " --provider " + only) + (repoFilter == null ? "" : " --repository " + repoFilter));
        }
        return new Loadout(loaded, failed);
    }

    public record Loadout(List<Loaded> loaded, List<String> failed) { }

    private static Gson gson(Map<String, String> flags) {
        GsonBuilder builder = new GsonBuilder().serializeNulls();
        if (!flags.containsKey("compact")) builder.setPrettyPrinting();
        return builder.create();
    }

    public record Loaded(Provider provider, Map<String, Map<String, Object>> gameConfigs) { }
}
