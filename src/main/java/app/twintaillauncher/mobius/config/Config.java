package app.twintaillauncher.mobius.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Config {
    private static final String RESOURCE_ROOT = "configs";

    private static Path override;

    private final Loader loader;
    private final Map<String, Object> root;

    private Config(Loader loader, Map<String, Object> root) {
        this.loader = loader;
        this.root = root;
    }

    public static Config load() {
        Loader loader = locate();
        return new Config(loader, loader.read("mobius.yml"));
    }

    public static void from(String path) {
        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) throw new ConfigException("not a configs directory: " + dir);
        if (!Files.isRegularFile(dir.resolve("mobius.yml"))) {
            throw new ConfigException("no mobius.yml in " + dir);
        }
        override = dir;
    }

    private static Loader locate() {
        if (override != null) return new DirLoader(override);

        Path code = codeSource();
        boolean packaged = code != null && Files.isRegularFile(code);

        if (packaged) {
            Path dir = code.getParent() == null ? Path.of("configs") : code.getParent().resolve("configs");
            if (!Files.isDirectory(dir)) {
                throw new ConfigException("no configs/ next to the jar (expected " + dir + ")");
            }
            return new DirLoader(dir.toAbsolutePath().normalize());
        }

        if (Config.class.getClassLoader().getResource(RESOURCE_ROOT + "/mobius.yml") == null) {
            throw new ConfigException("no configs on the classpath (expected " + RESOURCE_ROOT + "/mobius.yml" + " put your local configs in src/main/resources/" + RESOURCE_ROOT + ")");
        }
        return new ResourceLoader();
    }

    public static Path besideJar(String name) {
        Path code = codeSource();
        if (code == null || !Files.isRegularFile(code) || code.getParent() == null) return null;
        Path file = code.getParent().resolve(name);
        return Files.isRegularFile(file) ? file : null;
    }

    private static Path codeSource() {
        try {
            CodeSource src = Config.class.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            return Path.of(src.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String what, Reader reader) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try {
            Object parsed = new Yaml(options).load(reader);
            if (parsed == null) return new LinkedHashMap<>();
            if (!(parsed instanceof Map)) {
                throw new ConfigException(what + ": expected a yaml mapping at the top level");
            }
            return (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            if (e instanceof ConfigException) throw e;
            throw new ConfigException("cannot parse " + what + ": " + e.getMessage(), e);
        }
    }

    public Map<String, Object> read(String relative) {
        return loader.read(relative);
    }

    public boolean exists(String relative) {
        return loader.exists(relative);
    }

    public String describe() {
        return loader.describe();
    }

    public Map<String, Object> root() {
        return root;
    }

    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String rel : Yml.stringList(root, "providers")) {
            Map<String, Object> provider = read(rel);
            provider.put("__file", rel);
            out.add(provider);
        }
        if (out.isEmpty()) throw new ConfigException("mobius.yml lists no providers");
        return out;
    }

    public Map<String, Object> http() {
        return Yml.map(root, "http");
    }

    private interface Loader {
        Map<String, Object> read(String relative);
        boolean exists(String relative);
        String describe();
    }

    private record DirLoader(Path dir) implements Loader {
        @Override
        public Map<String, Object> read(String relative) {
            Path file = dir.resolve(relative);
            if (!Files.isRegularFile(file)) throw new ConfigException("config file not found: " + file);
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parse(file.toString(), r);
            } catch (IOException e) {
                throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
            }
        }

        @Override
        public boolean exists(String relative) {
            return Files.isRegularFile(dir.resolve(relative));
        }

        @Override
        public String describe() {
            return dir.toString();
        }
    }

    private record ResourceLoader() implements Loader {
        @Override
        public Map<String, Object> read(String relative) {
            String path = RESOURCE_ROOT + "/" + relative;
            try (InputStream in = Config.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) throw new ConfigException("config resource not found: " + path);
                return parse(path, new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new ConfigException("cannot read " + path + ": " + e.getMessage(), e);
            }
        }

        @Override
        public boolean exists(String relative) {
            return Config.class.getClassLoader().getResource(RESOURCE_ROOT + "/" + relative) != null;
        }

        @Override
        public String describe() {
            return "classpath:" + RESOURCE_ROOT;
        }
    }

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
