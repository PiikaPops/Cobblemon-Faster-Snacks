package fr.coquelicot.fastersnacks.bench;

import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** Second entrypoint, only in the benchmark build (gradlew build -Pbench). */
public final class BenchInit implements ModInitializer {
    @Override
    public void onInitialize() {
        SnackBench.init();
        SnackStats.hook = AttemptAlternation.INSTANCE;
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> BenchCommands.register(dispatcher));
        CobblemonFasterSnacks.LOGGER.warn("Benchmark build: /fastersnacks bench is available. It places blocks and removes entities: test worlds only.");
    }
}
