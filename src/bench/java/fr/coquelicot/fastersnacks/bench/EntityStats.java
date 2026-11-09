package fr.coquelicot.fastersnacks.bench;

/** Time spent ticking Pokémon entities, measured only while a bench asks for it. */
public final class EntityStats {
    public static boolean enabled;
    public static long tickNanos;
    public static int ticked;
    private static long start;
    private static int depth;

    private EntityStats() {}

    public static void begin() {
        if (depth++ == 0) start = System.nanoTime();
    }

    public static void end() {
        if (--depth == 0) {
            tickNanos += System.nanoTime() - start;
            ticked++;
        }
        if (depth < 0) depth = 0;
    }

    public static void resetTick() { tickNanos = 0; ticked = 0; }
}
