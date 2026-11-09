package fr.coquelicot.fastersnacks.snack;

import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.SpawningZone;
import com.cobblemon.mod.common.api.spawning.condition.AreaSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.AreaTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.BasicSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.CompositeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.FishingSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.GroundedSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.GroundedTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SeafloorSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SeafloorTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SubmergedSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SubmergedTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SurfaceSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SurfaceTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.BucketMultiplyingInfluence;
import com.cobblemon.mod.common.api.spawning.influence.BucketNormalizingInfluence;
import com.cobblemon.mod.common.api.spawning.influence.IncenseSweetInfluence;
import com.cobblemon.mod.common.api.spawning.influence.PlayerLevelRangeInfluence;
import com.cobblemon.mod.common.api.spawning.influence.SaccharineLogSlatheredInfluence;
import com.cobblemon.mod.common.api.spawning.influence.SpawnBaitInfluence;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.FlooredSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.GroundedSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.LavafloorSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.SeafloorSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.SubmergedSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.SurfaceSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Exact replacement for {@code Spawner.getMatchingSpawns} during one spawn computation.
 *
 * Cobblemon checks every candidate spawn detail against every spawnable position of the zone
 * (a Poké Snack zone is 17x17x17: ~300 to ~1800 positions x hundreds of candidates), and each check
 * runs every condition of the detail. Almost all of that work repeats itself.
 *
 * Each built-in condition only reads a handful of things, and compares them in a simple way: a number
 * against its own bounds, a block against its own tests, the biome against its own list... For each
 * condition we build a "view" that extracts exactly that from a position, in a few bits. The condition is
 * run once per distinct view value and the answer is reused for every other position with the same value.
 * Positions whose attributes are all equal then replay the same list of surviving candidates (the "plan").
 *
 * Why the result is identical to Cobblemon's (same details, same order):
 *  - a view keeps everything its condition reads: in-bounds bits for numbers (with the condition's own
 *    bounds and the same comparisons), "one of its tests matches" bits for nearby blocks and base blocks
 *    (the checks are "any"), the biome, the position class, the chunk (structures and slime chunks are
 *    resolved per chunk by Cobblemon), the fluid;
 *  - X/Z bounds, appendages, unknown condition classes, unknown block test kinds, unknown position classes
 *    and SpawnDetail subclasses overriding isSatisfiedBy are checked per position, the regular way;
 *  - world inputs (time, weather, moon, dimension, the spawner) cannot change during one computation: the
 *    memo lives for one SpawningZone object within one server tick;
 *  - influences are memoised only for classes whose affectSpawnable ignores the position (whitelist);
 *  - the free-space check for large Pokémon (postFilter) always runs per position;
 *  - conditions are evaluated in Cobblemon's order with the same short-circuits.
 * The "verify" mode recomputes everything the original way and counts differences.
 */
public final class SpawnMatchMemo {
    private SpawnMatchMemo() {}

    // ------------------------------------------------------------------ whitelists

    private static final Set<Class<?>> KNOWN_CONDITIONS = Set.of(
            BasicSpawningCondition.class, AreaSpawningCondition.class, GroundedSpawningCondition.class,
            SeafloorSpawningCondition.class, SubmergedSpawningCondition.class, SurfaceSpawningCondition.class,
            FishingSpawningCondition.class);

    /** Influences whose affectSpawnable(detail, position) does not read the position. */
    private static final Set<Class<?>> POSITION_FREE_INFLUENCES = Set.of(
            SpawnCause.class, PokeSnackBlockEntity.class, SpawnBaitInfluence.class, BucketNormalizingInfluence.class,
            BucketMultiplyingInfluence.class, IncenseSweetInfluence.class, PlayerLevelRangeInfluence.class,
            SaccharineLogSlatheredInfluence.class);

    private static final Set<Class<?>> KNOWN_POSITIONS = Set.of(
            GroundedSpawnablePosition.class, SurfaceSpawnablePosition.class, SubmergedSpawnablePosition.class,
            SeafloorSpawnablePosition.class, LavafloorSpawnablePosition.class);

    // ------------------------------------------------------------------ condition views

    /** What one built-in condition reads from a position. */
    private static final class View {
        boolean biome, chunk, fluid, baseFluid;
        boolean light, sky, seeSky, markers, y, height, depth, nearby, base;
        int lightMin = Integer.MIN_VALUE, lightMax = Integer.MAX_VALUE, skyMin = Integer.MIN_VALUE, skyMax = Integer.MAX_VALUE;
        int heightMin = Integer.MIN_VALUE, heightMax = Integer.MAX_VALUE, depthMin = Integer.MIN_VALUE, depthMax = Integer.MAX_VALUE;
        float yMin = Float.NEGATIVE_INFINITY, yMax = Float.POSITIVE_INFINITY;
        boolean seeSkyValue;
        String[] markerList;
        long[] nearbyMask, baseMask;

        long key(Pos p) {
            long k = p.typeId;                                   // spawnablePositionMatches: class check
            if (biome) k |= (long) p.biomeId << 3;
            if (chunk) k |= (long) p.chunkId << 8;
            if (fluid) k |= (long) p.fluidId << 12;
            if (baseFluid) k |= (long) p.baseFluidId << 16;
            int bit = 20;
            if (light && p.light >= lightMin && p.light <= lightMax) k |= 1L << bit;
            bit++;
            if (sky && p.sky >= skyMin && p.sky <= skyMax) k |= 1L << bit;
            bit++;
            if (seeSky && p.seeSky == seeSkyValue) k |= 1L << bit;
            bit++;
            if (markers && anyMarker(p)) k |= 1L << bit;
            bit++;
            if (y && !(p.y < yMin) && !(p.y > yMax)) k |= 1L << bit;
            bit++;
            if (height && p.height >= heightMin && p.height <= heightMax) k |= 1L << bit;
            bit++;
            if (depth && p.depth >= depthMin && p.depth <= depthMax) k |= 1L << bit;
            bit++;
            if (nearby && overlaps(p.nearbyBits, nearbyMask)) k |= 1L << bit;
            bit++;
            if (base && overlaps(p.baseBits, baseMask)) k |= 1L << bit;
            return k;
        }

        private boolean anyMarker(Pos p) {
            for (String m : markerList) if (p.markers.contains(m)) return true;
            return false;
        }

        private static boolean overlaps(long[] a, long[] b) {
            if (a == null) return false;
            int n = Math.min(a.length, b.length);
            for (int i = 0; i < n; i++) if ((a[i] & b[i]) != 0) return true;
            return false;
        }
    }

    /** @return the view of a condition, or null if it must be checked per position. */
    private static View viewOf(SpawningCondition<?> c, PoolProfile prof) {
        if (!KNOWN_CONDITIONS.contains(c.getClass())) return null;
        if (c.getAppendages() != null && !c.getAppendages().isEmpty()) return null;
        if (c.getMinX() != null || c.getMaxX() != null || c.getMinZ() != null || c.getMaxZ() != null) return null;
        View v = new View();
        v.biome = c.getBiomes() != null && !c.getBiomes().isEmpty();
        if (c.getMinLight() != null || c.getMaxLight() != null) {
            v.light = true;
            if (c.getMinLight() != null) v.lightMin = c.getMinLight();
            if (c.getMaxLight() != null) v.lightMax = c.getMaxLight();
        }
        if (c.getMinSkyLight() != null || c.getMaxSkyLight() != null) {
            v.sky = true;
            if (c.getMinSkyLight() != null) v.skyMin = c.getMinSkyLight();
            if (c.getMaxSkyLight() != null) v.skyMax = c.getMaxSkyLight();
        }
        if (c.getCanSeeSky() != null) { v.seeSky = true; v.seeSkyValue = c.getCanSeeSky(); }
        if (c.getMarkers() != null && !c.getMarkers().isEmpty()) { v.markers = true; v.markerList = c.getMarkers().toArray(new String[0]); }
        if (c.getMinY() != null || c.getMaxY() != null) {
            v.y = true;
            if (c.getMinY() != null) v.yMin = c.getMinY();
            if (c.getMaxY() != null) v.yMax = c.getMaxY();
        }
        v.chunk = (c.getStructures() != null && !c.getStructures().isEmpty()) || (c.isSlimeChunk() != null && c.isSlimeChunk());
        if (c instanceof AreaTypeSpawningCondition<?> a) {
            if (a.getMinHeight() != null || a.getMaxHeight() != null) {
                v.height = true;
                if (a.getMinHeight() != null) v.heightMin = a.getMinHeight();
                if (a.getMaxHeight() != null) v.heightMax = a.getMaxHeight();
            }
            if (a.getNeededNearbyBlocks() != null) {
                v.nearby = true;
                v.nearbyMask = prof.nearby.maskOf(a.getNeededNearbyBlocks());
                if (v.nearbyMask == null) return null;
            }
        }
        if (c instanceof GroundedTypeSpawningCondition<?> g && g.getNeededBaseBlocks() != null) {
            v.base = true;
            v.baseMask = prof.base.maskOf(g.getNeededBaseBlocks());
            if (v.baseMask == null) return null;
        }
        if (c instanceof SeafloorTypeSpawningCondition<?> s && s.getNeededBaseBlocks() != null) {
            v.base = true;
            v.baseMask = prof.base.maskOf(s.getNeededBaseBlocks());
            if (v.baseMask == null) return null;
        }
        if (c instanceof SurfaceTypeSpawningCondition<?> s) {
            v.baseFluid = true; // reads the fluid of the base block
            if (s.getMinDepth() != null || s.getMaxDepth() != null) {
                v.depth = true;
                if (s.getMinDepth() != null) v.depthMin = s.getMinDepth();
                if (s.getMaxDepth() != null) v.depthMax = s.getMaxDepth();
            }
        }
        if (c instanceof SubmergedTypeSpawningCondition<?> s) {
            v.fluid = true; // reads the fluid at the position (type and source flag)
            if (s.getMinDepth() != null || s.getMaxDepth() != null) {
                v.depth = true;
                if (s.getMinDepth() != null) v.depthMin = s.getMinDepth();
                if (s.getMaxDepth() != null) v.depthMax = s.getMaxDepth();
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ registry (rebuilt when the pool reloads)

    private static Object registryVersion;
    private static PoolProfile registryProfile;
    private static final Reference2IntOpenHashMap<SpawningCondition<?>> COND_INDEX = new Reference2IntOpenHashMap<>();
    private static final List<SpawningCondition<?>> CONDS = new ArrayList<>();
    private static final List<View> VIEWS = new ArrayList<>();
    private static final Reference2IntOpenHashMap<SpawnDetail> DETAIL_INDEX = new Reference2IntOpenHashMap<>();
    private static final IdentityHashMap<List<SpawnDetail>, ListInfo> LIST_INFO = new IdentityHashMap<>();

    static {
        COND_INDEX.defaultReturnValue(-1);
        DETAIL_INDEX.defaultReturnValue(-1);
    }

    private static int condIndex(SpawningCondition<?> c) {
        int idx = COND_INDEX.getInt(c);
        if (idx >= 0) return idx;
        idx = CONDS.size();
        CONDS.add(c);
        VIEWS.add(viewOf(c, registryProfile));
        COND_INDEX.put(c, idx);
        return idx;
    }

    private static int detailIndex(SpawnDetail d) {
        int idx = DETAIL_INDEX.getInt(d);
        if (idx >= 0) return idx;
        idx = DETAIL_INDEX.size();
        DETAIL_INDEX.put(d, idx);
        return idx;
    }

    public static int distinctConditions() { return CONDS.size(); }

    private static final int[] NONE = new int[0];

    /** Condition indices of every candidate of a retrieve() list. Such lists are stable until the pool reloads. */
    private static final class ListInfo {
        final boolean[] impure;
        final int[] detail;
        final int[][] conds, antis, compConds, compAntis;
        final boolean[] hasComposite;
        final int impureCount;

        ListInfo(List<SpawnDetail> candidates) {
            int n = candidates.size();
            impure = new boolean[n];
            detail = new int[n];
            conds = new int[n][];
            antis = new int[n][];
            compConds = new int[n][];
            compAntis = new int[n][];
            hasComposite = new boolean[n];
            int impureN = 0;
            for (int i = 0; i < n; i++) {
                SpawnDetail d = candidates.get(i);
                detail[i] = detailIndex(d);
                boolean bad = overridesIsSatisfiedBy(d);
                conds[i] = indices(d.getConditions());
                antis[i] = indices(d.getAnticonditions());
                CompositeSpawningCondition comp = d.getCompositeCondition();
                hasComposite[i] = comp != null;
                compConds[i] = comp == null ? NONE : indices(comp.getConditions());
                compAntis[i] = comp == null ? NONE : indices(comp.getAnticonditions());
                bad |= anyImpure(conds[i]) || anyImpure(antis[i]) || anyImpure(compConds[i]) || anyImpure(compAntis[i]);
                impure[i] = bad;
                if (bad) impureN++;
            }
            impureCount = impureN;
        }

        private static int[] indices(List<SpawningCondition<?>> list) {
            if (list == null || list.isEmpty()) return NONE;
            int[] a = new int[list.size()];
            for (int i = 0; i < a.length; i++) a[i] = condIndex(list.get(i));
            return a;
        }

        private static boolean anyImpure(int[] idx) {
            for (int i : idx) if (VIEWS.get(i) == null) return true;
            return false;
        }

        private static boolean overridesIsSatisfiedBy(SpawnDetail d) {
            try {
                return d.getClass().getMethod("isSatisfiedBy", SpawnablePosition.class).getDeclaringClass() != SpawnDetail.class;
            } catch (NoSuchMethodException e) {
                return true;
            }
        }
    }

    private static void ensureRegistry(PoolProfile profile) {
        if (registryVersion == profile.version && registryProfile == profile) return;
        registryVersion = profile.version;
        registryProfile = profile;
        COND_INDEX.clear();
        CONDS.clear();
        VIEWS.clear();
        DETAIL_INDEX.clear();
        LIST_INFO.clear();
    }

    // ------------------------------------------------------------------ per position

    /** Every attribute a built-in condition can read, plus the exact key used to share plans. */
    private static final class Pos {
        int typeId, biomeId, chunkId, fluidId, baseFluidId, inflId;
        int light, sky, height, depth, y;
        boolean seeSky;
        Set<String> markers;
        long[] nearbyBits, baseBits;
        long planKey;
    }

    private static final Pos DIRECT = new Pos();

    // ------------------------------------------------------------------ per computation (one zone, one tick)

    private static SpawningZone ctxZone;
    private static long ctxTick = Long.MIN_VALUE;
    private static PoolProfile ctxProfile;
    private static final IdentityHashMap<SpawnablePosition, Pos> POSITIONS = new IdentityHashMap<>();

    private static final Reference2IntOpenHashMap<Object> typeIds = new Reference2IntOpenHashMap<>(), biomeIds = new Reference2IntOpenHashMap<>(),
            fluidIds = new Reference2IntOpenHashMap<>(), baseFluidIds = new Reference2IntOpenHashMap<>();
    private static final Long2IntOpenHashMap chunkIds = new Long2IntOpenHashMap();
    private static final Int2IntOpenHashMap lightQIds = new Int2IntOpenHashMap(), skyQIds = new Int2IntOpenHashMap(),
            heightQIds = new Int2IntOpenHashMap(), depthQIds = new Int2IntOpenHashMap(), yQIds = new Int2IntOpenHashMap();
    private static final Object2IntOpenHashMap<Object> markerIds = new Object2IntOpenHashMap<>(), inflIds = new Object2IntOpenHashMap<>(),
            bitsIds = new Object2IntOpenHashMap<>();

    private static Long2ByteOpenHashMap[] condAnswers = new Long2ByteOpenHashMap[1024];
    private static byte[][] preAnswers = new byte[1024][];
    private static final List<Integer> touchedConds = new ArrayList<>(), touchedDetails = new ArrayList<>();

    private static final class ListMemo {
        final Long2ObjectOpenHashMap<int[]> plans = new Long2ObjectOpenHashMap<>();
        final Int2ObjectOpenHashMap<int[]> order = new Int2ObjectOpenHashMap<>(); // inflId -> candidates worth checking
    }

    private static final IdentityHashMap<List<SpawnDetail>, ListMemo> LIST_MEMOS = new IdentityHashMap<>();

    /** Counters for /fastersnacks stats and the bench. */
    public static long evaluations, freshEvaluations, directEvaluations, positionsMemoised, positionsDirect, signatures, mismatches, matchNanos;
    public static long keyNanos, retrieveNanos, replayNanos, replayed, planNanos;
    /** Fine-grained timers (debug only: they cost a few System.nanoTime() calls per position). */
    public static boolean debugTimings;

    public static void resetCounters() {
        evaluations = freshEvaluations = directEvaluations = positionsMemoised = positionsDirect = signatures = mismatches = matchNanos = 0;
        keyNanos = retrieveNanos = replayNanos = replayed = planNanos = 0;
    }

    private static void bind(SpawningZone zone, long tick, Spawner spawner) {
        if (zone == ctxZone && tick == ctxTick) return;
        clear();
        ctxZone = zone;
        ctxTick = tick;
        ctxProfile = PoolProfile.of(spawner.getSpawnPool());
        ensureRegistry(ctxProfile);
    }

    private static void clear() {
        ctxZone = null;
        ctxTick = Long.MIN_VALUE;
        ctxProfile = null;
        POSITIONS.clear();
        typeIds.clear(); biomeIds.clear(); fluidIds.clear(); baseFluidIds.clear(); chunkIds.clear();
        lightQIds.clear(); skyQIds.clear(); heightQIds.clear(); depthQIds.clear(); yQIds.clear();
        markerIds.clear(); inflIds.clear(); bitsIds.clear();
        for (int c : touchedConds) condAnswers[c] = null;
        for (int d : touchedDetails) preAnswers[d] = null;
        touchedConds.clear();
        touchedDetails.clear();
        LIST_MEMOS.clear();
        kindsZone = null; kinds = null; KIND_OF.clear(); MASK_TO_NEARBY.clear();
    }

    /** Releases references to the last zone (called at the end of each server tick). */
    public static void release() {
        if (ctxZone != null) clear();
    }

    /**
     * @return the matching spawns, or null if this position type is not handled (the caller lets Cobblemon do it).
     */
    public static List<SpawnDetail> getMatchingSpawns(Spawner spawner, String bucket, SpawnablePosition position, long tick) {
        if (!(position instanceof AreaSpawnablePosition area) || !KNOWN_POSITIONS.contains(position.getClass())) return null;
        bind(area.getZone(), tick, spawner);

        boolean timing = debugTimings;
        long tk0 = timing ? System.nanoTime() : 0;
        Pos pos = POSITIONS.get(position);
        if (pos == null) {
            pos = createPos(area);
            POSITIONS.put(position, pos);
            if (pos == DIRECT) positionsDirect++; else positionsMemoised++;
        }
        long tk1 = timing ? System.nanoTime() : 0;
        if (timing) keyNanos += tk1 - tk0;
        List<SpawnDetail> candidates = spawner.getSpawnPool().retrieve(bucket, position);
        if (timing) retrieveNanos += System.nanoTime() - tk1;

        List<SpawnDetail> spawns = new ArrayList<>();
        int n = candidates.size();
        evaluations += n;
        if (pos == DIRECT) {
            directEvaluations += n;
            for (SpawnDetail detail : candidates) if (detail.isSatisfiedBy(position)) spawns.add(detail);
        } else {
            ListInfo info = LIST_INFO.computeIfAbsent(candidates, ListInfo::new);
            ListMemo memo = LIST_MEMOS.computeIfAbsent(candidates, k -> new ListMemo());
            int[] plan = memo.plans.get(pos.planKey);
            if (plan == null) {
                long tp0 = timing ? System.nanoTime() : 0;
                plan = buildPlan(candidates, info, memo, pos, position);
                memo.plans.put(pos.planKey, plan);
                signatures++;
                if (timing) planNanos += System.nanoTime() - tp0;
            }
            long tr0 = timing ? System.nanoTime() : 0;
            // plan codes: candidate index << 1, | 1 if it must be checked directly
            for (int code : plan) {
                SpawnDetail detail = candidates.get(code >>> 1);
                boolean ok = (code & 1) != 0 ? detail.isSatisfiedBy(position) : position.postFilter(detail);
                if (ok) spawns.add(detail);
            }
            if (timing) replayNanos += System.nanoTime() - tr0;
            replayed += plan.length;
            directEvaluations += info.impureCount;
        }
        for (SpawningInfluence influence : position.getInfluences()) {
            List<SpawnDetail> injected = influence.injectSpawns(bucket, position);
            if (injected != null) spawns.addAll(injected);
        }
        return spawns;
    }

    private static int[] buildPlan(List<SpawnDetail> candidates, ListInfo info, ListMemo memo, Pos pos, SpawnablePosition position) {
        int[] order = memo.order.get(pos.inflId);
        if (order == null) {
            order = candidatesWorthChecking(candidates, info, pos.inflId, position);
            memo.order.put(pos.inflId, order);
        }
        int[] codes = new int[order.length];
        int size = 0;
        for (int i : order) {
            if (info.impure[i]) { codes[size++] = (i << 1) | 1; continue; }
            if (conditionsPass(info, i, pos, position)) codes[size++] = i << 1;
        }
        return Arrays.copyOf(codes, size);
    }

    /**
     * Candidates in Cobblemon's order, minus those whose preFilter fails: preFilter only consults influences
     * that ignore the position, so its answer is the same for every position with the same influences.
     */
    private static int[] candidatesWorthChecking(List<SpawnDetail> candidates, ListInfo info, int inflId, SpawnablePosition position) {
        int[] tmp = new int[candidates.size()];
        int size = 0;
        for (int i = 0; i < tmp.length; i++) {
            if (info.impure[i]) { tmp[size++] = i; continue; }
            int di = info.detail[i];
            if (di >= preAnswers.length) preAnswers = Arrays.copyOf(preAnswers, Math.max(di + 1, preAnswers.length * 2));
            byte[] pre = preAnswers[di];
            if (pre == null) { preAnswers[di] = pre = new byte[16]; touchedDetails.add(di); }
            if (inflId >= pre.length) preAnswers[di] = pre = Arrays.copyOf(pre, inflId * 2 + 1);
            if (pre[inflId] == 0) { freshEvaluations++; pre[inflId] = position.preFilter(candidates.get(i)) ? (byte) 1 : (byte) 2; }
            if (pre[inflId] == 1) tmp[size++] = i;
        }
        return Arrays.copyOf(tmp, size);
    }

    /** Conditions, anticonditions and composite of SpawnDetail.isSatisfiedBy, memoised, same order and short-circuits. */
    private static boolean conditionsPass(ListInfo info, int i, Pos pos, SpawnablePosition position) {
        int[] conds = info.conds[i];
        if (conds.length > 0 && !any(conds, pos, position)) return false;
        int[] antis = info.antis[i];
        if (antis.length > 0 && any(antis, pos, position)) return false;
        if (info.hasComposite[i]) {
            int[] cc = info.compConds[i];
            if (cc.length > 0 && !any(cc, pos, position)) return false;
            int[] ca = info.compAntis[i];
            if (ca.length > 0 && any(ca, pos, position)) return false;
        }
        return true;
    }

    private static boolean any(int[] conds, Pos pos, SpawnablePosition position) {
        for (int c : conds) if (condition(c, pos, position)) return true;
        return false;
    }

    private static boolean condition(int c, Pos pos, SpawnablePosition position) {
        if (c >= condAnswers.length) condAnswers = Arrays.copyOf(condAnswers, Math.max(c + 1, condAnswers.length * 2));
        Long2ByteOpenHashMap answers = condAnswers[c];
        if (answers == null) { condAnswers[c] = answers = new Long2ByteOpenHashMap(4); touchedConds.add(c); }
        long k = VIEWS.get(c).key(pos);
        byte a = answers.get(k);
        if (a == 0) {
            freshEvaluations++;
            a = CONDS.get(c).isSatisfiedBy(position) ? (byte) 1 : (byte) 2;
            answers.put(k, a);
        }
        return a == 1;
    }

    // ------------------------------------------------------------------ building a position

    private static final Object NO_VALUE = new Object();
    // plan key layout: widths of each interned id (62 bits used)
    private static final int[] PLAN_WIDTH = {3, 5, 4, 4, 1, 5, 5, 6, 4, 9, 3, 5, 4, 4};

    private static Pos createPos(AreaSpawnablePosition p) {
        List<SpawningInfluence> influences = p.getInfluences();
        for (SpawningInfluence influence : influences) {
            if (!POSITION_FREE_INFLUENCES.contains(influence.getClass())) return direct(influence.getClass());
        }
        if (!POSITION_FREE_INFLUENCES.contains(p.getCause().getClass())) return direct(p.getCause().getClass());

        PoolProfile prof = ctxProfile;
        Pos pos = new Pos();
        pos.typeId = intern(typeIds, p.getClass());
        pos.biomeId = intern(biomeIds, p.getBiomeHolder());
        int x = p.getPosition().getX(), z = p.getPosition().getZ();
        pos.chunkId = intern(chunkIds, (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xFFFFFFFFL));
        pos.fluidId = intern(fluidIds, p instanceof SubmergedSpawnablePosition s ? s.getFluid() : NO_VALUE);
        BlockState base = p instanceof FlooredSpawnablePosition f ? f.getBaseBlock() : null;
        pos.baseFluidId = intern(baseFluidIds, base != null ? base.getFluidState() : NO_VALUE);
        pos.light = p.getLight();
        pos.sky = p.getSkyLight();
        pos.seeSky = p.getCanSeeSky();
        pos.height = p.getHeight();
        pos.depth = p instanceof SurfaceSpawnablePosition s ? s.getDepth() : p instanceof SubmergedSpawnablePosition s2 ? s2.getDepth() : 0;
        pos.y = p.getPosition().getY();
        pos.markers = p.getMarkers();
        Registry<Block> registry = p.getBlockRegistry();
        pos.nearbyBits = nearbyBits(p, prof, registry);
        pos.baseBits = base != null ? prof.base.profile(base.getBlock(), registry) : null;
        pos.inflId = internObj(inflIds, new InfluenceKey(influences.toArray(), p.getCause()));

        int[] ids = {
                pos.typeId, pos.biomeId,
                intern(lightQIds, PoolProfile.quantise(pos.light, prof.lightMin, prof.lightMax)),
                intern(skyQIds, PoolProfile.quantise(pos.sky, prof.skyMin, prof.skyMax)),
                pos.seeSky ? 1 : 0,
                intern(heightQIds, PoolProfile.quantise(pos.height, prof.heightMin, prof.heightMax)),
                intern(depthQIds, PoolProfile.quantise(pos.depth, prof.depthMin, prof.depthMax)),
                internObj(bitsIds, new BitsKey(pos.baseBits, pos.baseFluidId, 0)),
                pos.fluidId,
                internObj(bitsIds, new BitsKey(pos.nearbyBits, -1, 1)),
                internObj(markerIds, pos.markers),
                intern(yQIds, PoolProfile.quantise((float) pos.y, prof.yMin, prof.yMax)),
                pos.chunkId,
                pos.inflId
        };
        long key = 0;
        int off = 0;
        for (int f = 0; f < ids.length; f++) {
            if (ids[f] >= (1 << PLAN_WIDTH[f])) return overflow();
            key |= ((long) ids[f]) << off;
            off += PLAN_WIDTH[f];
        }
        pos.planKey = key;
        // the view keys pack some ids in fixed widths too
        if (pos.typeId >= 8 || pos.biomeId >= 32 || pos.chunkId >= 16 || pos.fluidId >= 16 || pos.baseFluidId >= 16) return overflow();
        return pos;
    }

    private static int intern(Reference2IntOpenHashMap<Object> map, Object v) {
        int id = map.getOrDefault(v, -1);
        if (id < 0) { id = map.size(); map.put(v, id); }
        return id;
    }

    private static int intern(Int2IntOpenHashMap map, int v) {
        int id = map.getOrDefault(v, -1);
        if (id < 0) { id = map.size(); map.put(v, id); }
        return id;
    }

    private static int intern(Long2IntOpenHashMap map, long v) {
        int id = map.getOrDefault(v, -1);
        if (id < 0) { id = map.size(); map.put(v, id); }
        return id;
    }

    private static int internObj(Object2IntOpenHashMap<Object> map, Object v) {
        int id = map.getOrDefault(v, -1);
        if (id < 0) { id = map.size(); map.put(v, id); }
        return id;
    }

    private record BitsKey(long[] bits, int extra, int kind) {
        @Override public boolean equals(Object o) {
            return o instanceof BitsKey k && k.kind == kind && k.extra == extra && Arrays.equals(bits, k.bits);
        }

        @Override public int hashCode() { return (Arrays.hashCode(bits) * 31 + extra) * 31 + kind; }
    }

    private record InfluenceKey(Object[] influences, Object cause) {
        @Override public boolean equals(Object o) {
            if (!(o instanceof InfluenceKey k) || k.cause != cause || k.influences.length != influences.length) return false;
            for (int i = 0; i < influences.length; i++) if (influences[i] != k.influences[i]) return false;
            return true;
        }

        @Override public int hashCode() {
            int h = System.identityHashCode(cause);
            for (Object o : influences) h = h * 31 + System.identityHashCode(o);
            return h;
        }
    }

    private static final Set<Class<?>> REPORTED = new HashSet<>();

    private static Pos direct(Class<?> reason) {
        if (REPORTED.add(reason)) {
            CobblemonFasterSnacks.LOGGER.info("Positions influenced by {} are checked the regular way (influence not known to ignore the position)",
                    reason.getName());
        }
        return DIRECT;
    }

    private static boolean overflowReported;

    private static Pos overflow() {
        if (!overflowReported) {
            overflowReported = true;
            CobblemonFasterSnacks.LOGGER.info("A spawn zone has more distinct position kinds than the packed keys hold; extra positions are checked the regular way");
        }
        return DIRECT;
    }

    // ------------------------------------------------------------------ nearby blocks

    private static SpawningZone kindsZone;
    private static byte[] kinds;              // kind of every cell of the zone
    private static final Block[] KIND_BLOCKS = new Block[64];
    private static int kindCount, stoneKind;
    private static boolean kindsOverflow;
    private static final Reference2IntOpenHashMap<Block> KIND_OF = new Reference2IntOpenHashMap<>();
    private static final Long2ObjectOpenHashMap<long[]> MASK_TO_NEARBY = new Long2ObjectOpenHashMap<>();

    private static int kindOf(Block b) {
        int k = KIND_OF.getOrDefault(b, -1);
        if (k >= 0) return k;
        if (kindCount == 64) { kindsOverflow = true; return 0; }
        k = kindCount++;
        KIND_BLOCKS[k] = b;
        KIND_OF.put(b, k);
        return k;
    }

    private static void ensureKinds(SpawningZone zone) {
        if (kindsZone == zone) return;
        kindsZone = zone;
        KIND_OF.clear();
        MASK_TO_NEARBY.clear();
        kindCount = 0;
        kindsOverflow = false;
        int lx = zone.getLength(), ly = zone.getHeight(), lz = zone.getWidth();
        kinds = new byte[lx * ly * lz];
        SpawningZone.BlockData[][][] blocks = zone.getBlocks();
        int i = 0;
        for (int x = 0; x < lx; x++)
            for (int y = 0; y < ly; y++)
                for (int z = 0; z < lz; z++)
                    kinds[i++] = (byte) kindOf(blocks[x][y][z].getState().getBlock());
        stoneKind = kindOf(Blocks.STONE.defaultBlockState().getBlock());
    }

    /** Bit mask of the block kinds in the same box as SpawningZone.nearbyBlocks (same bounds, stone outside the zone). */
    private static long boxMask(LazyNearbyBlocks l) {
        SpawningZone zone = l.zone();
        int bx = zone.getBaseX(), by = zone.getBaseY(), bz = zone.getBaseZ();
        int lx = zone.getLength(), ly = zone.getHeight(), lz = zone.getWidth();
        int minX = Math.max(l.cx() - l.h(), bx), maxX = Math.min(l.cx() + l.h(), bx + lx);
        int minY = Math.max(l.cy() - l.v(), by), maxY = Math.min(l.cy() + l.v(), by + ly);
        int minZ = Math.max(l.cz() - l.h(), bz), maxZ = Math.min(l.cz() + l.h(), bz + lz);
        long stone = 1L << stoneKind;
        long mask = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (x >= bx + lx || y >= by + ly) { mask |= stone; continue; }
                int row = ((x - bx) * ly + (y - by)) * lz - bz;
                for (int z = minZ; z <= maxZ; z++) mask |= z < bz + lz ? 1L << kinds[row + z] : stone;
            }
        }
        return mask;
    }

    /** Which nearby-block tests at least one block around the position passes. */
    private static long[] nearbyBits(AreaSpawnablePosition p, PoolProfile prof, Registry<Block> registry) {
        List<BlockState> list = p.getNearbyBlocks();
        if (list instanceof LazyNearbyBlocks lazy && !lazy.materialized() && lazy.zone() == p.getZone()) {
            ensureKinds(lazy.zone());
            if (!kindsOverflow) {
                long mask = boxMask(lazy);
                long[] bits = MASK_TO_NEARBY.get(mask);
                if (bits == null) {
                    bits = new long[Math.max(1, prof.nearby.words())];
                    for (long m = mask; m != 0; m &= m - 1) or(bits, prof.nearby.profile(KIND_BLOCKS[Long.numberOfTrailingZeros(m)], registry));
                    MASK_TO_NEARBY.put(mask, bits);
                }
                return bits;
            }
        }
        long[] bits = new long[Math.max(1, prof.nearby.words())];
        for (Block b : list instanceof LazyNearbyBlocks lazy ? lazy.distinctBlocks() : distinctBlocks(list)) or(bits, prof.nearby.profile(b, registry));
        return bits;
    }

    private static void or(long[] into, long[] bits) {
        for (int i = 0; i < into.length; i++) into[i] |= bits[i];
    }

    static Block[] distinctBlocks(List<BlockState> states) {
        Block[] distinct = new Block[8];
        int n = 0;
        BlockState last = null;
        outer:
        for (BlockState state : states) {
            if (state == last) continue;
            last = state;
            Block b = state.getBlock();
            for (int i = 0; i < n; i++) if (distinct[i] == b) continue outer;
            if (n == distinct.length) distinct = Arrays.copyOf(distinct, n * 2);
            distinct[n++] = b;
        }
        return Arrays.copyOf(distinct, n);
    }

}
