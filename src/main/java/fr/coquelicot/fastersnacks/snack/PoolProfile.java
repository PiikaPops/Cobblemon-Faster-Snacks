package fr.coquelicot.fastersnacks.snack;

import com.cobblemon.mod.common.api.conditional.RegistryLikeCondition;
import com.cobblemon.mod.common.api.conditional.RegistryLikeIdentifierCondition;
import com.cobblemon.mod.common.api.conditional.RegistryLikeTagCondition;
import com.cobblemon.mod.common.api.spawning.condition.AreaTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.CompositeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.GroundedTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SeafloorTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SubmergedTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.condition.SurfaceTypeSpawningCondition;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnPool;
import com.cobblemon.mod.common.registry.BlockIdentifierCondition;
import com.cobblemon.mod.common.registry.BlockTagCondition;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

/**
 * What the built-in conditions of a spawn pool can tell apart.
 *
 * Built-in conditions only compare numbers against bounds ("fails if value < min", "fails if value > max")
 * and only ask whether a block matches an id or a tag. This class collects every bound and every block test
 * of the pool, deduplicated (two tests on the same tag or the same id always agree), and gives each block
 * its "profile": the set of tests it passes.
 */
final class PoolProfile {
    final Object version;
    final int[] lightMin, lightMax, skyMin, skyMax, heightMin, heightMax, depthMin, depthMax;
    final float[] yMin, yMax;
    final BlockTests nearby = new BlockTests(), base = new BlockTests();

    /** Deduplicated block tests and the profile of each block against them. */
    static final class BlockTests {
        final List<RegistryLikeCondition<Block>> tests = new ArrayList<>();
        private final LinkedHashMap<Object, Integer> index = new LinkedHashMap<>();
        private final IdentityHashMap<Block, long[]> profiles = new IdentityHashMap<>();
        boolean allKnown = true;

        int words() { return (tests.size() + 63) / 64; }

        /** Test classes known to answer with holder.is(tag) / holder.is(id) only (the Block* ones just add a constructor). */
        private static final java.util.Set<Class<?>> TAG_TESTS = java.util.Set.of(RegistryLikeTagCondition.class, BlockTagCondition.class);
        private static final java.util.Set<Class<?>> ID_TESTS = java.util.Set.of(RegistryLikeIdentifierCondition.class, BlockIdentifierCondition.class);

        private static Object key(RegistryLikeCondition<Block> t) {
            if (TAG_TESTS.contains(t.getClass())) return ((RegistryLikeTagCondition<Block>) t).getTag();
            if (ID_TESTS.contains(t.getClass())) return ((RegistryLikeIdentifierCondition<Block>) t).getIdentifier();
            return null;
        }

        void add(RegistryLikeCondition<Block> t) {
            Object k = key(t);
            if (k == null) {
                if (allKnown) fr.coquelicot.fastersnacks.CobblemonFasterSnacks.LOGGER.info("Block test {} is not a known kind; conditions using it are checked the regular way", t.getClass().getName());
                allKnown = false;
                return;
            }
            if (!index.containsKey(k)) {
                index.put(k, tests.size());
                tests.add(t);
            }
        }

        /** Bit mask (over this list) of the given tests, or null if one of them is of an unknown kind. */
        long[] maskOf(List<RegistryLikeCondition<Block>> list) {
            long[] mask = new long[Math.max(1, words())];
            for (RegistryLikeCondition<Block> t : list) {
                Object k = key(t);
                Integer i = k == null ? null : index.get(k);
                if (i == null) return null;
                mask[i >> 6] |= 1L << (i & 63);
            }
            return mask;
        }

        long[] profile(Block block, Registry<Block> registry) {
            long[] p = profiles.get(block);
            if (p == null) {
                p = new long[Math.max(1, words())];
                Holder<Block> holder = registry.wrapAsHolder(block);
                for (int i = 0; i < tests.size(); i++) if (tests.get(i).fits(holder)) p[i >> 6] |= 1L << (i & 63);
                profiles.put(block, p);
            }
            return p;
        }
    }

    private PoolProfile(Object version, Collector c) {
        this.version = version;
        lightMin = ints(c.lightMin); lightMax = ints(c.lightMax);
        skyMin = ints(c.skyMin); skyMax = ints(c.skyMax);
        heightMin = ints(c.heightMin); heightMax = ints(c.heightMax);
        depthMin = ints(c.depthMin); depthMax = ints(c.depthMax);
        yMin = floats(c.yMin); yMax = floats(c.yMax);
    }

    private static int[] ints(TreeSet<Integer> set) { return set.stream().mapToInt(Integer::intValue).toArray(); }

    private static float[] floats(TreeSet<Float> set) {
        float[] a = new float[set.size()];
        int i = 0;
        for (Float f : set) a[i++] = f;
        return a;
    }

    // ------------------------------------------------------------------ cache

    private static SpawnPool cachedPool;
    private static PoolProfile cached;

    static PoolProfile of(SpawnPool pool) {
        Object version = pool.getPrecalculation(); // replaced on every (re)load of the pool
        if (pool == cachedPool && cached != null && cached.version == version) return cached;
        Collector c = new Collector();
        for (SpawnDetail d : pool.getDetails()) {
            c.addAll(d.getConditions());
            c.addAll(d.getAnticonditions());
            CompositeSpawningCondition composite = d.getCompositeCondition();
            if (composite != null) {
                c.addAll(composite.getConditions());
                c.addAll(composite.getAnticonditions());
            }
        }
        PoolProfile profile = new PoolProfile(version, c);
        for (RegistryLikeCondition<Block> t : c.nearbyTests) profile.nearby.add(t);
        for (RegistryLikeCondition<Block> t : c.baseTests) profile.base.add(t);
        cachedPool = pool;
        cached = profile;
        return profile;
    }

    private static final class Collector {
        final TreeSet<Integer> lightMin = new TreeSet<>(), lightMax = new TreeSet<>(), skyMin = new TreeSet<>(), skyMax = new TreeSet<>(),
                heightMin = new TreeSet<>(), heightMax = new TreeSet<>(), depthMin = new TreeSet<>(), depthMax = new TreeSet<>();
        final TreeSet<Float> yMin = new TreeSet<>(), yMax = new TreeSet<>();
        final List<RegistryLikeCondition<Block>> nearbyTests = new ArrayList<>(), baseTests = new ArrayList<>();

        void addAll(List<SpawningCondition<?>> conditions) {
            if (conditions == null) return;
            for (SpawningCondition<?> c : conditions) add(c);
        }

        void add(SpawningCondition<?> c) {
            if (c.getMinLight() != null) lightMin.add(c.getMinLight());
            if (c.getMaxLight() != null) lightMax.add(c.getMaxLight());
            if (c.getMinSkyLight() != null) skyMin.add(c.getMinSkyLight());
            if (c.getMaxSkyLight() != null) skyMax.add(c.getMaxSkyLight());
            if (c.getMinY() != null) yMin.add(c.getMinY());
            if (c.getMaxY() != null) yMax.add(c.getMaxY());
            if (c instanceof AreaTypeSpawningCondition<?> a) {
                if (a.getMinHeight() != null) heightMin.add(a.getMinHeight());
                if (a.getMaxHeight() != null) heightMax.add(a.getMaxHeight());
                if (a.getNeededNearbyBlocks() != null) nearbyTests.addAll(a.getNeededNearbyBlocks());
            }
            if (c instanceof GroundedTypeSpawningCondition<?> g && g.getNeededBaseBlocks() != null) baseTests.addAll(g.getNeededBaseBlocks());
            if (c instanceof SeafloorTypeSpawningCondition<?> s && s.getNeededBaseBlocks() != null) baseTests.addAll(s.getNeededBaseBlocks());
            if (c instanceof SurfaceTypeSpawningCondition<?> s) {
                if (s.getMinDepth() != null) depthMin.add(s.getMinDepth());
                if (s.getMaxDepth() != null) depthMax.add(s.getMaxDepth());
            }
            if (c instanceof SubmergedTypeSpawningCondition<?> s) {
                if (s.getMinDepth() != null) depthMin.add(s.getMinDepth());
                if (s.getMaxDepth() != null) depthMax.add(s.getMaxDepth());
            }
        }
    }

    // ------------------------------------------------------------------ quantisation (for the plan key)

    /** Encodes which "min" bounds pass and which "max" bounds fail for v: equal codes => identical outcomes. */
    static int quantise(int v, int[] mins, int[] maxs) {
        return (countAtMost(mins, v) << 16) | countBelow(maxs, v);
    }

    static int quantise(float v, float[] mins, float[] maxs) {
        int a = 0, b = 0;
        for (float m : mins) if (m <= v) a++;
        for (float m : maxs) if (m < v) b++;
        return (a << 16) | b;
    }

    private static int countAtMost(int[] sorted, int v) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (sorted[mid] <= v) lo = mid + 1; else hi = mid; }
        return lo;
    }

    private static int countBelow(int[] sorted, int v) {
        int lo = 0, hi = sorted.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (sorted[mid] < v) lo = mid + 1; else hi = mid; }
        return lo;
    }
}
