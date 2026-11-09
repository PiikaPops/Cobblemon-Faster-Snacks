package fr.coquelicot.fastersnacks.bench;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.cooking.PokeSnackSpawnPokemonEvent;
import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.SpawningZone;
import com.cobblemon.mod.common.api.spawning.detail.SpawnAction;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePosition;
import com.cobblemon.mod.common.api.spawning.position.calculators.SpawnablePositionCalculator;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import com.cobblemon.mod.common.block.PokeSnackBlock;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.components.BaitEffectsComponent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.perf.Histogram;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reproducible Poké Snack benchmarks, driven by commands (see FasterSnacksCommands).
 *
 * micro: one snack, N back-to-back spawn computations, timed per phase.
 * macro: a field of snacks, vanilla random-tick rate emulated with a seeded RNG,
 *        whole server tick time measured with and without snack activity.
 *
 * On a dedicated server without players, random ticks do not run and snacks find no player,
 * so the bench emulates both: it random-ticks the snacks itself (3 random ticks per 4096 blocks
 * per tick, like vanilla randomTickSpeed=3) and adds fake players next to them.
 * Spawned Pokémon are discarded right away (like a player catching them) and snacks are refilled,
 * so every attempt runs the full spawn computation instead of stopping at the density cap.
 */
public final class SnackBench {
    interface Task {
        /** @return true when the task is finished */
        boolean tick(MinecraftServer server, long tickStart);
        void cancel();
        String status();
    }

    private static Task current;
    private static long tickStart;

    // State read by the spawn listener while a bench runs.
    private static final Set<BlockPos> benchSnacks = new HashSet<>();
    private static boolean discardSpawned = true;
    private static final Map<String, Integer> spawnedSpecies = new TreeMap<>();

    private SnackBench() {}

    public static void init() {
        ServerTickEvents.START_SERVER_TICK.register(s -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(SnackBench::onEndTick);
        CobblemonEvents.POKE_SNACK_SPAWN_POKEMON_POST.subscribe(Priority.LOWEST,
                (java.util.function.Consumer<PokeSnackSpawnPokemonEvent.Post>) SnackBench::onSnackSpawn);
    }

    public static boolean running() { return current != null; }
    public static String status() { return current == null ? "idle" : current.status(); }

    public static void cancel() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    private static void onEndTick(MinecraftServer server) {
        if (current == null) return;
        try {
            if (current.tick(server, tickStart)) current = null;
        } catch (Throwable t) {
            CobblemonFasterSnacks.LOGGER.error("[bench] task failed", t);
            try { current.cancel(); } catch (Throwable ignored) {}
            current = null;
        }
    }

    /** Lets another bench register its snacks (refilled after each bite, Pokémon kept or discarded). */
    static void trackSnacks(List<BlockPos> positions, boolean discard) {
        benchSnacks.clear();
        benchSnacks.addAll(positions);
        discardSpawned = discard;
        spawnedSpecies.clear();
    }

    public static boolean startPlatform(CommandSourceStack src, ServerLevel level, int x, int z, int y, int snacks, int ticks,
                                        int speed, int clearEvery, long seed, String label, String baits) {
        return startPlatform(src, level, x, z, y, snacks, ticks, speed, clearEvery, seed, label, baits, 0);
    }

    public static boolean startPlatform(CommandSourceStack src, ServerLevel level, int x, int z, int y, int snacks, int ticks,
                                        int speed, int clearEvery, long seed, String label, String baits, int alternate) {
        if (current != null) return false;
        current = new PlatformBench(src, level, x, z, y, snacks, ticks, speed, clearEvery, seed, label, parseBaits(baits), alternate);
        return true;
    }

    private static void onSnackSpawn(PokeSnackSpawnPokemonEvent.Post event) {
        PokeSnackBlockEntity be = event.getPokeSnackBlockEntity();
        if (!benchSnacks.contains(be.getBlockPos())) return;
        PokemonEntity entity = event.getPokemonEntity();
        spawnedSpecies.merge(entity.getPokemon().getSpecies().getName(), 1, Integer::sum);
        if (discardSpawned) entity.discard();
        refill(be);
    }

    /** Puts the snack back to full so the bench keeps running on the same block. */
    private static void refill(PokeSnackBlockEntity be) {
        be.setAmountSpawned(0);
        if (be.getLevel() == null) return;
        BlockState state = be.getLevel().getBlockState(be.getBlockPos());
        if (state.getBlock() instanceof PokeSnackBlock) {
            be.getLevel().setBlock(be.getBlockPos(), state.setValue(PokeSnackBlock.Companion.getBITES(), 0), 2);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void send(CommandSourceStack src, String msg) {
        CobblemonFasterSnacks.LOGGER.info("[bench] {}", msg);
        if (src != null) src.sendSystemMessage(Component.literal(msg));
    }

    private static List<ResourceLocation> parseBaits(String baits) {
        List<ResourceLocation> list = new ArrayList<>();
        if (baits == null || baits.isBlank() || baits.equalsIgnoreCase("none")) return list;
        for (String s : baits.split(",")) {
            ResourceLocation id = ResourceLocation.tryParse(s.trim());
            if (id != null) list.add(id);
        }
        return list;
    }

    private static Set<ChunkPos> chunksAround(List<BlockPos> positions, int marginBlocks) {
        Set<ChunkPos> set = new LinkedHashSet<>();
        for (BlockPos p : positions) {
            for (int x = p.getX() - marginBlocks; x <= p.getX() + marginBlocks; x += 16)
                for (int z = p.getZ() - marginBlocks; z <= p.getZ() + marginBlocks; z += 16)
                    set.add(new ChunkPos(x >> 4, z >> 4));
            set.add(new ChunkPos((p.getX() + marginBlocks) >> 4, (p.getZ() + marginBlocks) >> 4));
        }
        return set;
    }

    private static void force(ServerLevel level, Set<ChunkPos> chunks, boolean forced) {
        for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, forced);
    }

    private static boolean entitiesLoaded(ServerLevel level, Set<ChunkPos> chunks) {
        for (ChunkPos c : chunks) if (!level.areEntitiesLoaded(c.toLong())) return false;
        return true;
    }

    /** Places a fresh lure snack on the surface at (x, z) and returns its block entity. */
    private static PokeSnackBlockEntity placeSnack(ServerLevel level, int x, int z, List<ResourceLocation> baits) {
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        BlockState below = level.getBlockState(pos.below());
        if (!below.getFluidState().isEmpty()) level.setBlock(pos.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pos, CobblemonBlocks.POKE_SNACK.defaultBlockState(), 3);
        PokeSnackBlockEntity be = (PokeSnackBlockEntity) level.getBlockEntity(pos);
        if (be != null && !baits.isEmpty()) be.setBaitEffectsComponent(new BaitEffectsComponent(baits));
        return be;
    }

    /** Removes non-player living entities around a point so every run starts from the same scene. */
    private static int clearLiving(ServerLevel level, BlockPos center, int radius) {
        AABB box = new AABB(center).inflate(radius, 64, radius);
        List<net.minecraft.world.entity.LivingEntity> found = level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                e -> !(e instanceof net.minecraft.world.entity.player.Player));
        found.forEach(net.minecraft.world.entity.Entity::discard);
        return found.size();
    }

    private static String biomeAt(ServerLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("?");
    }

    private static JsonObject baseJson(String type, String label, ServerLevel level, BlockPos pos, List<ResourceLocation> baits) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("label", label);
        o.addProperty("optimizeSnacks", FasterSnacksConfig.INSTANCE.optimizeSnacks);
        o.addProperty("cobblemonVersion", CobblemonFasterSnacks.cobblemonVersion());
        o.addProperty("pos", pos.toShortString());
        o.addProperty("biome", biomeAt(level, pos));
        JsonArray b = new JsonArray();
        baits.forEach(id -> b.add(id.toString()));
        o.add("baits", b);
        o.addProperty("timestamp", System.currentTimeMillis());
        return o;
    }

    private static JsonObject speciesJson(Map<String, Integer> map) {
        JsonObject o = new JsonObject();
        map.forEach(o::addProperty);
        return o;
    }

    private static FakePlayer fakePlayer(ServerLevel level, int index, BlockPos at) {
        String name = "cfbench" + index;
        FakePlayer fp = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        fp.setPos(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        return fp;
    }

    // ------------------------------------------------------------------ micro

    public static boolean startMicro(CommandSourceStack src, ServerLevel level, int x, int z, int iterations,
                                     int realAttempts, String label, String baits) {
        if (current != null) return false;
        current = new MicroTask(src, level, x, z, iterations, realAttempts, label, parseBaits(baits));
        return true;
    }

    private static final class MicroTask implements Task {
        private static final int WARMUP = 15;
        private static final int PER_TICK = 3;

        final CommandSourceStack src;
        final ServerLevel level;
        final int x, z, iterations, realAttempts;
        final String label;
        final List<ResourceLocation> baits;
        final Set<ChunkPos> chunks;

        int stage, waited, done, attemptsDone;
        PokeSnackBlockEntity be;
        FakePlayer player;

        final Histogram full = new Histogram(), constrain = new Histogram(), entityCount = new Histogram(),
                generate = new Histogram(), resolve = new Histogram(), select = new Histogram(), attempt = new Histogram();
        final Histogram positions = new Histogram(); // stores counts, not nanos
        int actionsFound;
        String runtimeCheck;
        int clearedEntities;
        final Map<String, Integer> chosen = new TreeMap<>();

        MicroTask(CommandSourceStack src, ServerLevel level, int x, int z, int iterations, int realAttempts,
                  String label, List<ResourceLocation> baits) {
            this.src = src; this.level = level; this.x = x; this.z = z;
            this.iterations = iterations; this.realAttempts = realAttempts; this.label = label; this.baits = baits;
            this.chunks = chunksAround(List.of(new BlockPos(x, 0, z)), 48);
        }

        @Override public String status() {
            return "micro '" + label + "' stage " + stage + " " + done + "/" + (WARMUP + iterations) + " attempts " + attemptsDone + "/" + realAttempts;
        }

        @Override public boolean tick(MinecraftServer server, long unused) {
            switch (stage) {
                case 0 -> {
                    force(level, chunks, true);
                    stage = 1;
                }
                case 1 -> {
                    if (entitiesLoaded(level, chunks) || ++waited > 600) {
                        be = placeSnack(level, x, z, baits);
                        clearedEntities = clearLiving(level, be.getBlockPos(), 40);
                        player = fakePlayer(level, 0, be.getBlockPos());
                        benchSnacks.clear();
                        benchSnacks.add(be.getBlockPos());
                        discardSpawned = true;
                        spawnedSpecies.clear();
                        SnackStats.reset();
                        send(src, "micro '" + label + "': snack at " + be.getBlockPos().toShortString() + " (" + biomeAt(level, be.getBlockPos()) + ")");
                        stage = 2;
                    }
                }
                case 2 -> {
                    for (int i = 0; i < PER_TICK && done < WARMUP + iterations; i++, done++) runIteration(done >= WARMUP);
                    if (done >= WARMUP + iterations) stage = 3;
                }
                case 3 -> {
                    // real attempts: the actual Cobblemon code path, including spawning the Pokémon.
                    if (attemptsDone < realAttempts) {
                        long t0 = System.nanoTime();
                        be.attemptSpawn(player);
                        attempt.add(System.nanoTime() - t0);
                        attemptsDone++;
                    } else {
                        finish();
                        return true;
                    }
                }
            }
            return false;
        }

        private void runIteration(boolean record) {
            PokeSnackBlockEntity.Spawner spawner = be.getSpawner();
            SpawnCause cause = new SpawnCause(spawner, player);
            SpawningZoneInput input = spawner.getZoneInput(cause);

            // Whole computation as Cobblemon runs it (includes our optimisations when enabled).
            long t0 = System.nanoTime();
            List<SpawnAction<?>> actions = spawner.calculateSpawnActionsForArea(input, 1);
            long t1 = System.nanoTime();

            // Same steps, one by one, to see where the time goes.
            long p0 = System.nanoTime();
            SpawningZoneInput constrained = spawner.constrainArea(input);
            long p1 = System.nanoTime();
            if (constrained == null) {
                if (record) { full.add(t1 - t0); constrain.add(p1 - p0); }
                return;
            }
            AABB box = AABB.ofSize(constrained.getCenter(), 48.0, 1000.0, 48.0);
            level.getEntitiesOfClass(PokemonEntity.class, box, e -> true).size();
            long p2 = System.nanoTime();
            SpawningZone zone = spawner.getGenerator().generate(spawner, constrained);
            long p3 = System.nanoTime();
            List<AreaSpawnablePosition> resolved = spawner.getResolver().resolve(spawner,
                    SpawnablePositionCalculator.Companion.getPrioritizedAreaCalculators(), zone);
            long p4 = System.nanoTime();
            List<SpawningInfluence> influences = new ArrayList<>(spawner.getInfluences());
            influences.addAll(zone.getUnconditionalInfluences());
            if (FasterSnacksConfig.INSTANCE.optimizeSnacks) fr.coquelicot.fastersnacks.snack.SingleChoice.begin(spawner, level.getServer().getTickCount());
            spawner.getSelector().select(spawner, () -> spawner.chooseBucket(cause, influences), resolved, 1);
            fr.coquelicot.fastersnacks.snack.SingleChoice.end();
            long p5 = System.nanoTime();

            if (!record) return;
            if (runtimeCheck == null && !resolved.isEmpty()) {
                // The lazily completed runtime must expose exactly the functions of an eagerly built one.
                java.util.Set<String> lazy = resolved.get(0).getRuntime().getEnvironment().query.getFunctions().keySet();
                java.util.Set<String> eager = new java.util.HashSet<>(com.cobblemon.mod.common.api.molang.MoLangFunctions.INSTANCE
                        .setup(new com.bedrockk.molang.runtime.MoLangRuntime()).getEnvironment().query.getFunctions().keySet());
                eager.add("spawnable_position");
                runtimeCheck = lazy.equals(eager) ? "identical (" + lazy.size() + " functions)" : "DIFFERENT lazy=" + lazy.size() + " eager=" + eager.size();
            }
            full.add(t1 - t0);
            constrain.add(p1 - p0);
            entityCount.add(p2 - p1);
            generate.add(p3 - p2);
            resolve.add(p4 - p3);
            select.add(p5 - p4);
            positions.add(resolved.size());
            if (!actions.isEmpty()) {
                actionsFound++;
                SpawnDetail detail = actions.get(0).getDetail();
                chosen.merge(actions.get(0).getBucket() + "/" + detail.getId(), 1, Integer::sum);
            }
        }

        private void finish() {
            JsonObject o = baseJson("micro", label, level, be.getBlockPos(), baits);
            o.addProperty("iterations", iterations);
            o.add("fullComputation", full.toJson());
            JsonObject phases = new JsonObject();
            phases.add("constrainArea", constrain.toJson());
            phases.add("entityCount", entityCount.toJson());
            phases.add("generateZone", generate.toJson());
            phases.add("resolvePositions", resolve.toJson());
            phases.add("selectSpawn", select.toJson());
            o.add("phases", phases);
            JsonObject pos = new JsonObject();
            pos.addProperty("mean", positions.count() == 0 ? 0 : positions.totalNanos() / (double) positions.count());
            pos.addProperty("max", positions.maxNanos());
            o.add("spawnablePositions", pos);
            o.addProperty("actionsFound", actionsFound);
            o.addProperty("runtimeCheck", runtimeCheck);
            o.addProperty("clearedEntities", clearedEntities);
            o.add("chosenSpawns", speciesJson(chosen));
            o.add("memo", SnackStats.memoJson());
            o.add("realAttempt", attempt.toJson());
            o.add("realAttemptSpecies", speciesJson(spawnedSpecies));
            Path file = BenchOutput.write("micro", label, o);
            send(src, "micro '" + label + "' done. full: " + full.summary());
            send(src, "  phases mean ms: constrain=" + fmt(constrain) + " entities=" + fmt(entityCount) + " generate=" + fmt(generate)
                    + " resolve=" + fmt(resolve) + " select=" + fmt(select) + " | positions~" + Math.round(positions.count() == 0 ? 0 : positions.totalNanos() / (double) positions.count()));
            send(src, "  real attempts: " + attempt.summary() + " -> " + file);
            send(src, "  " + SnackStats.memoSummary() + " | runtime " + runtimeCheck);
            cleanup();
        }

        @Override public void cancel() { cleanup(); }

        private void cleanup() {
            if (be != null) level.setBlock(be.getBlockPos(), Blocks.AIR.defaultBlockState(), 3);
            benchSnacks.clear();
            force(level, chunks, false);
        }
    }

    private static String fmt(Histogram h) { return String.format("%.3f", h.meanMs()); }

    // ------------------------------------------------------------------ macro

    public static boolean startMacro(CommandSourceStack src, ServerLevel level, int x, int z, int count, int spacing,
                                     int baselineTicks, int activeTicks, int speed, boolean discard, long seed,
                                     String label, String baits) {
        if (current != null) return false;
        current = new MacroTask(src, level, x, z, count, spacing, baselineTicks, activeTicks, speed, discard, seed, label, parseBaits(baits));
        return true;
    }

    private static final class MacroTask implements Task {
        final CommandSourceStack src;
        final ServerLevel level;
        final int count, baselineTicks, activeTicks, speed;
        final boolean discard;
        final String label;
        final List<ResourceLocation> baits;
        final List<BlockPos> columns = new ArrayList<>();
        final List<BlockPos> snacks = new ArrayList<>();
        final List<ServerPlayer> fakePlayers = new ArrayList<>();
        final Set<ChunkPos> chunks;
        final RandomSource rng;
        final BlockPos origin;

        int stage, waited, ticks;
        final Histogram baselineTick = new Histogram(), activeTick = new Histogram(), snackTick = new Histogram();
        int randomTicks;

        MacroTask(CommandSourceStack src, ServerLevel level, int x, int z, int count, int spacing, int baselineTicks,
                  int activeTicks, int speed, boolean discard, long seed, String label, List<ResourceLocation> baits) {
            this.src = src; this.level = level; this.count = count; this.baselineTicks = baselineTicks;
            this.activeTicks = activeTicks; this.speed = speed; this.discard = discard; this.label = label; this.baits = baits;
            this.rng = RandomSource.create(seed);
            this.origin = new BlockPos(x, 0, z);
            int side = (int) Math.ceil(Math.sqrt(count));
            for (int i = 0; i < count; i++) {
                int gx = i % side - side / 2, gz = i / side - side / 2;
                columns.add(new BlockPos(x + gx * spacing, 0, z + gz * spacing));
            }
            this.chunks = chunksAround(columns, 48);
        }

        @Override public String status() {
            return "macro '" + label + "' stage " + stage + " tick " + ticks + " attempts " + SnackStats.ATTEMPTS.count();
        }

        @Override public boolean tick(MinecraftServer server, long unused) {
            long now = System.nanoTime();
            switch (stage) {
                case 0 -> {
                    force(level, chunks, true);
                    send(src, "macro '" + label + "': loading " + chunks.size() + " chunks");
                    stage = 1;
                }
                case 1 -> {
                    if (entitiesLoaded(level, chunks) || ++waited > 1200) {
                        benchSnacks.clear();
                        spawnedSpecies.clear();
                        discardSpawned = discard;
                        for (BlockPos c : columns) clearLiving(level, c, 40);
                        for (BlockPos c : columns) {
                            PokeSnackBlockEntity be = placeSnack(level, c.getX(), c.getZ(), baits);
                            if (be == null) continue;
                            snacks.add(be.getBlockPos());
                            benchSnacks.add(be.getBlockPos());
                            ServerPlayer fp = fakePlayer(level, snacks.size(), be.getBlockPos());
                            fakePlayers.add(fp);
                            level.players().add(fp);
                        }
                        send(src, "macro '" + label + "': " + snacks.size() + " snacks placed, warming up");
                        stage = 2;
                        ticks = 0;
                    }
                }
                case 2 -> { // let chunk loading / lighting settle
                    if (++ticks >= 200) { stage = 3; ticks = 0; }
                }
                case 3 -> { // baseline: same world, snacks present but not ticking
                    baselineTick.add(now - tickStart);
                    if (++ticks >= baselineTicks) {
                        stage = 4; ticks = 0;
                        SnackStats.reset();
                        send(src, "macro '" + label + "': baseline " + baselineTick.summary());
                    }
                }
                case 4 -> {
                    long snackNanos = 0;
                    int threshold = 3 * speed; // vanilla randomTickSpeed = 3 picks per 4096 blocks per tick
                    for (BlockPos pos : snacks) {
                        if (rng.nextInt(4096) >= threshold) continue;
                        BlockState state = level.getBlockState(pos);
                        if (!(state.getBlock() instanceof PokeSnackBlock)) {
                            level.setBlock(pos, CobblemonBlocks.POKE_SNACK.defaultBlockState(), 3);
                            state = level.getBlockState(pos);
                        }
                        long t0 = System.nanoTime();
                        state.randomTick(level, pos, level.random);
                        snackNanos += System.nanoTime() - t0;
                        randomTicks++;
                    }
                    snackTick.add(snackNanos);
                    activeTick.add(System.nanoTime() - tickStart);
                    if (++ticks >= activeTicks) {
                        finish();
                        return true;
                    }
                }
            }
            return false;
        }

        private void finish() {
            JsonObject o = baseJson("macro", label, level, origin, baits);
            o.addProperty("snacks", snacks.size());
            o.addProperty("speedMultiplier", speed);
            o.addProperty("discardSpawned", discard);
            o.addProperty("baselineTicks", baselineTicks);
            o.addProperty("activeTicks", activeTicks);
            o.addProperty("randomTicks", randomTicks);
            o.add("baselineTickTime", baselineTick.toJson());
            o.add("activeTickTime", activeTick.toJson());
            o.add("snackTimePerTick", snackTick.toJson());
            o.add("attempts", SnackStats.ATTEMPTS.toJson());
            o.add("memo", SnackStats.memoJson());
            o.addProperty("spawned", spawnedSpecies.values().stream().mapToInt(Integer::intValue).sum());
            o.addProperty("ticksOver50msBaseline", baselineTick.countAboveMs(50));
            o.addProperty("ticksOver50msActive", activeTick.countAboveMs(50));
            o.addProperty("ticksOver25msActive", activeTick.countAboveMs(25));
            // Average snack cost per tick at the real (x1) random tick rate.
            o.addProperty("snackMsPerTickAtVanillaRate", Math.round(snackTick.meanMs() / speed * 10000.0) / 10000.0);
            o.add("spawnedSpecies", speciesJson(spawnedSpecies));
            Path file = BenchOutput.write("macro", label, o);
            send(src, "macro '" + label + "' done: active " + activeTick.summary());
            send(src, "  snack/tick " + snackTick.summary() + " | attempts " + SnackStats.ATTEMPTS.summary());
            send(src, "  spawned " + o.get("spawned").getAsInt() + ", ticks>50ms " + activeTick.countAboveMs(50) + " -> " + file);
            cleanup();
        }

        @Override public void cancel() { cleanup(); }

        private void cleanup() {
            level.players().removeAll(fakePlayers);
            fakePlayers.clear();
            for (BlockPos p : snacks) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            benchSnacks.clear();
            force(level, chunks, false);
        }
    }
}
