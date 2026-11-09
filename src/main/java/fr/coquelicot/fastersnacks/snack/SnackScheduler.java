package fr.coquelicot.fastersnacks.snack;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;

/**
 * A lag guard for very busy snack areas. Snack bites are spread over time by random ticks, but nothing stops
 * several of them landing on the same tick (hundreds of snacks, sped-up snacks, the refill right after a
 * clear-lag). Once the bites of the current tick have used the budget, the next bites of that tick are moved to
 * the following tick instead of running at once. None is dropped and none changes: each still runs with the
 * nearest player, exactly as the snack's own random tick does; it only runs a tick or two later.
 *
 * With the default budget (10 ms, a fifth of a tick) this only acts on rare bursts: an optimised bite costs
 * about 1 to 6 ms.
 */
public final class SnackScheduler {
    private SnackScheduler() {}

    private record Pending(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final int MAX_QUEUE = 20_000;
    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    private static long usedNanos;
    private static boolean draining;
    private static boolean warnedFull;

    /** Counters for the bench. */
    public static long deferred, replayed, droppedQueueFull;

    public static void resetCounters() { deferred = replayed = droppedQueueFull = 0; }

    private static long budgetNanos() {
        FasterSnacksConfig c = FasterSnacksConfig.INSTANCE;
        return c.optimizeSnacks && c.snackTickBudgetMs > 0 ? (long) (c.snackTickBudgetMs * 1_000_000L) : -1;
    }

    /** @return true if this bite must wait for the next tick (the caller then skips it). */
    public static boolean deferIfOverBudget(PokeSnackBlockEntity be) {
        long budget = budgetNanos();
        if (budget < 0 || draining || usedNanos < budget || be.getLevel() == null) return false;
        if (QUEUE.size() >= MAX_QUEUE) {
            droppedQueueFull++;
            if (!warnedFull) {
                warnedFull = true;
                CobblemonFasterSnacks.LOGGER.warn("Snack bites are arriving faster than the tick budget allows for a long time; extra bites run now");
            }
            return false; // never drop a bite: past this point it runs immediately, as without the guard
        }
        QUEUE.add(new Pending(be.getLevel().dimension(), be.getBlockPos().immutable()));
        deferred++;
        return true;
    }

    /** Called by the timing mixin after each bite. */
    public static void addUsed(long nanos) { usedNanos += nanos; }

    /** Start of a server tick: new budget, then the bites left over from the previous tick, oldest first. */
    public static void onTickStart(MinecraftServer server) {
        usedNanos = 0;
        if (QUEUE.isEmpty()) return;
        long budget = budgetNanos();
        while (!QUEUE.isEmpty() && (budget < 0 || usedNanos < budget)) {
            Pending p = QUEUE.poll();
            ServerLevel level = server.getLevel(p.dimension());
            if (level == null || !level.isLoaded(p.pos())) continue;
            if (!(level.getBlockEntity(p.pos()) instanceof PokeSnackBlockEntity be)) continue;
            // same rule as PokeSnackBlockEntity.randomTick: the nearest player in range, or no bite
            Player player = level.getNearestPlayer(p.pos().getX(), p.pos().getY(), p.pos().getZ(),
                    Cobblemon.INSTANCE.getConfig().getMaximumSpawningZoneDistanceFromPlayer(), false);
            if (player == null) continue;
            draining = true;
            try {
                be.attemptSpawn(player);
                replayed++;
            } finally {
                draining = false;
            }
        }
    }

    public static int queued() { return QUEUE.size(); }
}
