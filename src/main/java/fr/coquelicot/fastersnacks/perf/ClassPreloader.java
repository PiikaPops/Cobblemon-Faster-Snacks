package fr.coquelicot.fastersnacks.perf;

import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The first Poké Snack bite (or the first wild spawn) after a server start freezes the server for about half a
 * second: hundreds of Cobblemon and Minecraft classes are loaded and transformed by mixins on the spot.
 * This loads them in a background thread right after start-up instead. Classes are loaded without running
 * their static initialisers (Class.forName(name, false, ...)), so nothing happens earlier than it would have.
 */
public final class ClassPreloader {
    private ClassPreloader() {}

    /** Cobblemon packages used by a spawn and by a wild Pokémon's first ticks. */
    private static final String[] COBBLEMON_PREFIXES = {
            "com/cobblemon/mod/common/api/spawning/", "com/cobblemon/mod/common/entity/pokemon/",
            "com/cobblemon/mod/common/api/ai/", "com/cobblemon/mod/common/entity/ai/", "com/cobblemon/mod/common/pokemon/ai/",
            "com/cobblemon/mod/common/pokemon/requirements/", "com/cobblemon/mod/common/pokemon/evolution/",
            "com/cobblemon/mod/common/api/pokemon/", "com/cobblemon/mod/common/api/molang/", "com/cobblemon/mod/common/api/fishing/",
    };

    public static volatile int loaded, failed;
    public static volatile long millis = -1;

    public static void startInBackground() {
        Thread t = new Thread(ClassPreloader::run, "CobblemonFasterSnacks class preloader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void run() {
        long start = System.currentTimeMillis();
        ClassLoader loader = ClassPreloader.class.getClassLoader();
        List<String> names = new ArrayList<>();
        FabricLoader.getInstance().getModContainer("cobblemon").ifPresent(c -> collect(c, COBBLEMON_PREFIXES, names));
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
                loaded++;
            } catch (Throwable e) {
                failed++; // client-only or optional classes: they would never load on this side anyway
            }
        }
        millis = System.currentTimeMillis() - start;
        CobblemonFasterSnacks.LOGGER.info("Preloaded {} Cobblemon classes in {} ms ({} skipped)", loaded, millis, failed);
    }

    private static void collect(ModContainer mod, String[] prefixes, List<String> out) {
        for (Path root : mod.getRootPaths()) {
            for (String prefix : prefixes) {
                Path dir = root.resolve(prefix);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        out.add(rel.substring(0, rel.length() - ".class".length()).replace('/', '.'));
                    });
                } catch (Exception e) {
                    CobblemonFasterSnacks.LOGGER.debug("Could not list {}", dir, e);
                }
            }
        }
    }
}
