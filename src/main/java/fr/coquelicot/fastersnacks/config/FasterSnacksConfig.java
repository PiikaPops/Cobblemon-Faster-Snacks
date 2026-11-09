package fr.coquelicot.fastersnacks.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Server config, stored in config/cobblemon_faster_snacks.json. */
public final class FasterSnacksConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("cobblemon_faster_snacks.json");

    public static FasterSnacksConfig INSTANCE = new FasterSnacksConfig();

    /** Master switch for the Poké Snack optimisations (can be toggled live with /fastersnacks snacks optimize). */
    public boolean optimizeSnacks = true;

    /** Exact shortcuts in wild Pokémon ticking (herd leader search, evolution checks). */
    public boolean optimizeEntities = true;

    /** Load spawning and Pokémon classes in the background after start-up, so the first spawn does not freeze the server. */
    public boolean preloadClasses = true;

    /** Lag guard: once snack bites have used this many milliseconds in a tick, further bites wait for the next tick. 0 = off. */
    public double snackTickBudgetMs = 10.0;

    /** Build the MoLang runtime of spawnable positions only when something asks for it. */
    public boolean lazyPositionRuntime = true;

    /** Build the list of blocks around a spawnable position only when a condition reads it. */
    public boolean lazyNearbyBlocks = true;

    /** Also use the exact spawn-matching memo for player spawners (same code path, same guarantees). */
    public boolean optimizeAllAreaSpawners = false;

    /** Debug: compute both the optimised and the original result and count differences (slower). */
    public boolean verifySnackOptimizations = false;

    public static void load() {
        try {
            if (Files.exists(PATH)) {
                FasterSnacksConfig read = GSON.fromJson(Files.readString(PATH), FasterSnacksConfig.class);
                if (read != null) INSTANCE = read;
            }
            save();
        } catch (Exception e) {
            CobblemonFasterSnacks.LOGGER.error("Could not read {}, using defaults", PATH, e);
        }
    }

    public static void save() {
        try {
            Files.writeString(PATH, GSON.toJson(INSTANCE));
        } catch (IOException e) {
            CobblemonFasterSnacks.LOGGER.error("Could not write {}", PATH, e);
        }
    }
}
