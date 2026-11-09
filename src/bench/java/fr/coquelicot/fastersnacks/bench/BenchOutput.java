package fr.coquelicot.fastersnacks.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Writes benchmark results as JSON files under <game dir>/fastersnacks-bench/. */
final class BenchOutput {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BenchOutput() {}

    static Path write(String type, String label, JsonObject result) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("fastersnacks-bench");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = dir.resolve(type + "_" + label.replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + stamp + ".json");
            Files.writeString(file, GSON.toJson(result));
            CobblemonFasterSnacks.LOGGER.info("[bench] result written to {}", file.toAbsolutePath());
            return file;
        } catch (Exception e) {
            CobblemonFasterSnacks.LOGGER.error("[bench] could not write result", e);
            return null;
        }
    }
}
