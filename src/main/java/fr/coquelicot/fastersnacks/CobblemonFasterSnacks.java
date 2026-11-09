package fr.coquelicot.fastersnacks;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.cooking.PokeSnackSpawnPokemonEvent;
import fr.coquelicot.fastersnacks.command.FasterSnacksCommands;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import fr.coquelicot.fastersnacks.snack.SpawnMatchMemo;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CobblemonFasterSnacks implements ModInitializer {
    public static final String MOD_ID = "cobblemon_faster_snacks";
    public static final Logger LOGGER = LoggerFactory.getLogger("Cobblemon Faster Snacks");

    @Override
    public void onInitialize() {
        FasterSnacksConfig.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> FasterSnacksCommands.register(dispatcher));
        CobblemonEvents.POKE_SNACK_SPAWN_POKEMON_POST.subscribe(Priority.LOWEST,
                (java.util.function.Consumer<PokeSnackSpawnPokemonEvent.Post>) e -> SnackStats.spawned++);
        ServerTickEvents.END_SERVER_TICK.register(server -> SpawnMatchMemo.release());
        ServerTickEvents.START_SERVER_TICK.register(fr.coquelicot.fastersnacks.snack.SnackScheduler::onTickStart);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            FasterSnacksConfig c = FasterSnacksConfig.INSTANCE;
            if (c.preloadClasses && (c.optimizeSnacks || c.optimizeEntities)) fr.coquelicot.fastersnacks.perf.ClassPreloader.startInBackground();
        });
        LOGGER.info("Cobblemon Faster Snacks loaded (Cobblemon {}), snack optimisations {}", cobblemonVersion(),
                FasterSnacksConfig.INSTANCE.optimizeSnacks ? "enabled" : "disabled");
    }

    public static String cobblemonVersion() {
        return FabricLoader.getInstance().getModContainer("cobblemon")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }
}
