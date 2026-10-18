package app.twintaillauncher.mobius.tools;

import app.twintaillauncher.mobius.gen.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Dumps {
    private Dumps() { }

    public static final java.util.regex.Pattern SOPHON_MANIFEST = java.util.regex.Pattern.compile("^manifest_[0-9a-f]+_([0-9a-f]{32})$");

    public static String dumpHash(JsonObject old) {
        String hash = Json.text(old, "file_hash");
        if (!hash.isEmpty()) return hash.contains(":") ? hash : "md5:" + hash;
        String url = Json.text(old, "file_url");
        String base = url.substring(url.lastIndexOf('/') + 1);
        java.util.regex.Matcher match = SOPHON_MANIFEST.matcher(base);
        return match.matches() ? "md5:" + match.group(1) : "";
    }

    public static JsonArray oldFulls(JsonObject section) {
        JsonArray out = new JsonArray();
        if (section == null) return out;
        JsonElement full = section.get("full");
        if (full == null || !full.isJsonArray()) return out;
        for (JsonElement e : full.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject old = e.getAsJsonObject();
            JsonObject row = new JsonObject();
            row.addProperty("file_url", Json.text(old, "file_url"));
            String hash = dumpHash(old);
            if (!hash.isEmpty()) row.addProperty("file_hash", hash);
            row.addProperty("compressed_size", Json.text(old, "compressed_size"));
            row.addProperty("decompressed_size", Json.text(old, "decompressed_size"));
            String base = Json.text(old, "file_path");
            if (!base.isEmpty()) row.addProperty("resource_base_url", base);
            String language = Json.text(old, "language");
            if (!language.isEmpty()) row.addProperty("language", language);
            out.add(row);
        }
        return out;
    }

    public static JsonArray oldDiffs(JsonObject section) {
        JsonArray out = new JsonArray();
        if (section == null) return out;
        JsonElement diff = section.get("diff");
        if (diff == null || !diff.isJsonArray()) return out;
        for (JsonElement e : diff.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject old = e.getAsJsonObject();
            JsonObject row = new JsonObject();
            row.addProperty("file_url", Json.text(old, "file_url"));
            row.addProperty("file_hash", dumpHash(old));
            row.addProperty("compressed_size", Json.text(old, "compressed_size"));
            row.addProperty("decompressed_size", Json.text(old, "decompressed_size"));
            row.addProperty("from_version", Json.text(old, "original_version"));
            String base = Json.text(old, "file_path");
            if (!base.isEmpty()) row.addProperty("resource_base_url", base);
            String language = Json.text(old, "language");
            if (!language.isEmpty()) row.addProperty("language", language);
            out.add(row);
        }
        return out;
    }

    public static JsonObject oldAssets(JsonObject old) {
        if (old == null || old.get("assets") == null || !old.get("assets").isJsonObject()) return null;
        JsonObject assets = old.getAsJsonObject("assets");
        JsonObject out = new JsonObject();
        for (String[] pair : new String[][]{{"game_icon", "game_icon_url"}, {"game_background", "game_background_url"}, {"game_live_background", "game_live_background_url"}}) {
            String value = Json.text(assets, pair[0]);
            if (!value.isEmpty()) out.addProperty(pair[1], value);
        }
        return out.isEmpty() ? null : out;
    }

    public static String oldResourceBase(JsonObject old) {
        if (old == null) return "";
        JsonObject meta = old.getAsJsonObject("metadata");
        return meta == null ? "" : Json.text(meta, "res_list_url");
    }

    public static String oldArchivePassword(JsonObject old) {
        if (old == null) return "";
        JsonObject meta = old.getAsJsonObject("metadata");
        if (meta == null || meta.get("diff_list_url") == null || !meta.get("diff_list_url").isJsonObject()) return "";
        String value = Json.text(meta.getAsJsonObject("diff_list_url"), "game");
        return value.startsWith("http") ? "" : value;
    }

    public static Map<String, JsonObject> sophonEraHistory(Path json, String biz) throws IOException {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(json)) return out;
        JsonObject old;
        try {
            old = new Gson().fromJson(Files.readString(json), JsonObject.class);
        } catch (RuntimeException e) {
            System.err.println(json + ": not a json manifest, no diffs ported for " + biz);
            return out;
        }
        if (old == null || !old.has("game_versions") || !old.get("game_versions").isJsonArray()) {
            System.err.println(json + ": no 'game_versions' array, no diffs ported for " + biz);
            return out;
        }
        for (JsonElement e : old.getAsJsonArray("game_versions")) {
            if (!e.isJsonObject()) continue;
            JsonObject entry = e.getAsJsonObject();
            JsonObject meta = entry.getAsJsonObject("metadata");
            if (meta == null) continue;
            out.put(Json.text(meta, "version"), entry);
        }
        return out;
    }

    public static boolean sophonEra(JsonObject old) {
        if (old == null) return false;
        JsonObject meta = old.getAsJsonObject("metadata");
        return meta != null && "DOWNLOAD_MODE_CHUNK".equals(Json.text(meta, "download_mode"));
    }
}
