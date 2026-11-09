package fr.coquelicot.fastersnacks.bench;

import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.perf.Histogram;
import fr.coquelicot.fastersnacks.perf.SnackStats;

/**
 * Per-attempt A/B: when enabled, every snack attempt first flips {@code optimizeSnacks}, so consecutive attempts
 * alternate between Cobblemon's original pipeline (index 0) and the optimised one (index 1) under the same world
 * conditions. Each attempt's duration is recorded under its mode.
 */
public final class AttemptAlternation implements SnackStats.AttemptHook {
    public static final AttemptAlternation INSTANCE = new AttemptAlternation();
    public static boolean enabled;
    public static final Histogram[] BY_MODE = {new Histogram(), new Histogram()};

    private int mode = -1;

    private AttemptAlternation() {}

    @Override
    public void begin() {
        if (!enabled) return;
        FasterSnacksConfig c = FasterSnacksConfig.INSTANCE;
        c.optimizeSnacks = !c.optimizeSnacks;
        mode = c.optimizeSnacks ? 1 : 0;
    }

    @Override
    public void end(long nanos) {
        if (mode < 0) return;
        BY_MODE[mode].add(nanos);
        mode = -1;
    }

    public static void reset() {
        BY_MODE[0].reset();
        BY_MODE[1].reset();
    }
}
