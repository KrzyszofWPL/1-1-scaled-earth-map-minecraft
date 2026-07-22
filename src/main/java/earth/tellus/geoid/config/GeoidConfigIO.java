package earth.tellus.geoid.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads {@link GeoidConfig} from {@code config/geoid.json}, so a server admin can tune it (in
 * particular {@code seaLevelY}, which must match whatever Tellus uses) without recompiling the mod.
 *
 * <p>Gson is used because it already ships with Minecraft itself, so this adds no new dependency.
 * {@link GeoidConfig}'s fields are all public with no annotations, which is exactly what Gson's
 * reflective (de)serialisation wants. If the file is missing, a copy of the current defaults is
 * written out as a starting template; if it exists but fails to parse, the defaults are kept and a
 * warning is logged rather than crashing startup.
 */
public final class GeoidConfigIO {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOG = LoggerFactory.getLogger("geoid/config");

    private GeoidConfigIO() {
    }

    /** Call once during mod init, on both the client and the dedicated server. */
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("geoid.json");
        try {
            if (Files.exists(path)) {
                try (Reader reader = Files.newBufferedReader(path)) {
                    GeoidConfig cfg = GSON.fromJson(reader, GeoidConfig.class);
                    if (cfg != null) {
                        GeoidConfig.set(cfg);
                        LOG.info("Loaded config from {}", path);
                    }
                }
            } else {
                writeTemplate(path);
            }
        } catch (IOException | JsonParseException e) {
            LOG.warn("Failed to read {}; using built-in defaults.", path, e);
        }
    }

    private static void writeTemplate(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(GeoidConfig.get(), writer);
            }
            LOG.info("Wrote default config to {} (edit seaLevelY to match Tellus, then restart)", path);
        } catch (IOException e) {
            LOG.warn("Could not write default config to {}", path, e);
        }
    }
}
