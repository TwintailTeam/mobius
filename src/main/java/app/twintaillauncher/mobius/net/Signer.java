package app.twintaillauncher.mobius.net;

import app.twintaillauncher.mobius.config.Config;
import app.twintaillauncher.mobius.config.Yml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public final class Signer {
    private Signer() { }

    public static void apply(Map<String, Object> auth, Map<String, String> vars, String body, Map<String, String> headers) {
        if (auth.isEmpty()) return;
        String type = Yml.required(auth, "type", "auth");
        String header = Yml.string(auth, "header", "Authorization");

        switch (type) {
            case "none" -> headers.remove(header);
            case "yostar_md5" -> headers.put(header, yostarMd5(auth, vars, body));
            default -> throw new Config.ConfigException("unknown auth type '" + type + "'");
        }
    }

    private static String yostarMd5(Map<String, Object> auth, Map<String, String> vars, String body) {
        String gameTag = Api.substitute(Yml.required(auth, "game_tag", "auth"), vars);
        String version = Api.substitute(Yml.required(auth, "version", "auth"), vars);
        String salt = Api.substitute(Yml.required(auth, "salt", "auth"), vars);
        long time = System.currentTimeMillis() / 1000;

        String head = "{\"game_tag\":\"" + gameTag + "\",\"time\":" + time + ",\"version\":\"" + version + "\"}";
        return "{\"head\":" + head + ",\"sign\":\"" + md5(head + body + salt) + "\"}";
    }

    private static String md5(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("md5 unavailable", e);
        }
    }
}
