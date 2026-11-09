package fr.coquelicot.fastersnacks.perf;

import com.google.gson.JsonObject;
import fr.coquelicot.fastersnacks.snack.SpawnMatchMemo;

/**
 * Live, always-on measurement of Poké Snack spawn attempts on the server thread.
 * Cost: two System.nanoTime() calls per attempt (an attempt happens every ~2 minutes per snack).
 */
public final class SnackStats {
    public static final Histogram ATTEMPTS = new Histogram();
    public static int spawned;
    /** verify mode: zone brightness values computed from the reused sky light, and how many differed from Minecraft's */
    public static long lightChecks, lightMismatches;

    /** Optional observer of snack attempts; only the benchmark build sets one. */
    public interface AttemptHook {
        void begin();
        void end(long nanos);
    }

    public static AttemptHook hook;

    private static long attemptStart;

    private SnackStats() {}

    /** True between beginAttempt and endAttempt (a snack attempt is running on the server thread). */
    public static boolean inAttempt() { return attemptStart != 0; }

    public static void beginAttempt() {
        if (hook != null) hook.begin();
        attemptStart = System.nanoTime();
    }

    /** @return the duration of the attempt that just ended, in nanoseconds (0 if none was running) */
    public static long endAttempt() {
        if (attemptStart == 0) return 0;
        long elapsed = System.nanoTime() - attemptStart;
        ATTEMPTS.add(elapsed);
        if (hook != null) hook.end(elapsed);
        attemptStart = 0;
        return elapsed;
    }

    public static void reset() {
        ATTEMPTS.reset();
        spawned = 0;
        lightChecks = 0;
        lightMismatches = 0;
        SpawnMatchMemo.resetCounters();
        fr.coquelicot.fastersnacks.snack.SnackScheduler.resetCounters();
    }

    public static JsonObject memoJson() {
        JsonObject o = new JsonObject();
        o.addProperty("candidateEvaluations", SpawnMatchMemo.evaluations);
        o.addProperty("freshConditionChecks", SpawnMatchMemo.freshEvaluations);
        o.addProperty("memoHits", SpawnMatchMemo.evaluations - SpawnMatchMemo.directEvaluations - SpawnMatchMemo.freshEvaluations);
        o.addProperty("directEvaluations", SpawnMatchMemo.directEvaluations);
        o.addProperty("positionsMemoised", SpawnMatchMemo.positionsMemoised);
        o.addProperty("positionsDirect", SpawnMatchMemo.positionsDirect);
        o.addProperty("signatures", SpawnMatchMemo.signatures);
        o.addProperty("verifyMismatches", SpawnMatchMemo.mismatches);
        o.addProperty("matchingTotalMs", Math.round(SpawnMatchMemo.matchNanos / 1e3) / 1e3);
        o.addProperty("keyMs", Math.round(SpawnMatchMemo.keyNanos / 1e3) / 1e3);
        o.addProperty("retrieveMs", Math.round(SpawnMatchMemo.retrieveNanos / 1e3) / 1e3);
        o.addProperty("planBuildMs", Math.round(SpawnMatchMemo.planNanos / 1e3) / 1e3);
        o.addProperty("replayMs", Math.round(SpawnMatchMemo.replayNanos / 1e3) / 1e3);
        o.addProperty("replayedCandidates", SpawnMatchMemo.replayed);
        o.addProperty("distinctConditions", SpawnMatchMemo.distinctConditions());
        o.addProperty("lightChecks", lightChecks);
        o.addProperty("lightMismatches", lightMismatches);
        return o;
    }

    public static String memoSummary() {
        long e = SpawnMatchMemo.evaluations;
        return String.format("Memo: %d candidate checks, %.1f%% answered from memo, %.1f%% evaluated directly, %d signatures for %d positions (%d direct), %d verify mismatches, matching %.0f ms total (key %.0f, retrieve %.0f, plans %.0f, replay %.0f for %d)",
                e, e == 0 ? 0 : 100.0 * (e - SpawnMatchMemo.directEvaluations - SpawnMatchMemo.freshEvaluations) / e, e == 0 ? 0 : 100.0 * SpawnMatchMemo.directEvaluations / e,
                SpawnMatchMemo.signatures, SpawnMatchMemo.positionsMemoised + SpawnMatchMemo.positionsDirect,
                SpawnMatchMemo.positionsDirect, SpawnMatchMemo.mismatches, SpawnMatchMemo.matchNanos / 1e6,
                SpawnMatchMemo.keyNanos / 1e6, SpawnMatchMemo.retrieveNanos / 1e6, SpawnMatchMemo.planNanos / 1e6, SpawnMatchMemo.replayNanos / 1e6, SpawnMatchMemo.replayed);
    }
}
