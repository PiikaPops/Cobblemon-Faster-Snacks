package fr.coquelicot.fastersnacks.snack;

import com.cobblemon.mod.common.api.spawning.spawner.Spawner;

/**
 * Marks a spawn computation that picks a single spawn (a Poké Snack bite: maxSpawns = 1).
 *
 * After each pick Cobblemon removes, from all its selection tables, the positions too close to the picked
 * one, so that the next pick of the same pass lands elsewhere. When the pick is the only one of the pass the
 * tables are thrown away right after, the action is already created and holds no reference to them, and the
 * removal draws no random number: skipping it changes nothing but the time spent.
 */
public final class SingleChoice {
    private SingleChoice() {}

    private static Spawner owner;
    private static long ownerTick = Long.MIN_VALUE;

    public static void begin(Spawner spawner, long tick) { owner = spawner; ownerTick = tick; }

    public static void end() { owner = null; ownerTick = Long.MIN_VALUE; }

    public static boolean isLastChoice(Spawner spawner, long tick) { return spawner != null && spawner == owner && tick == ownerTick; }
}
