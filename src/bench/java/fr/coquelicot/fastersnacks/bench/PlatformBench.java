package fr.coquelicot.fastersnacks.bench;

import com.cobblemon.mod.common.CobblemonBlocks;
import com.cobblemon.mod.common.block.PokeSnackBlock;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.components.BaitEffectsComponent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.bench.EntityStats;
import fr.coquelicot.fastersnacks.perf.Histogram;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The "sky platform" a lot of players build: a 3x3-chunk floor high in the sky, up to 128 lure snacks on the
 * centre chunk, one player standing in the middle who barely kills anything, and a clear-lag that removes the
 * wild Pokémon every few minutes. Random ticks are emulated at a chosen speed (servers that speed snacks up).
 * Measures the whole server tick, the time inside snack bites and the time spent ticking Pokémon.
 */
final class PlatformBench implements SnackBench.Task {
    private final CommandSourceStack src;
    private final ServerLevel level;
    private final int x0, z0, y, snackCount, ticks, speed, clearEvery, warmup;
    private final String label;
    private final List<ResourceLocation> baits;
    private final RandomSource rng;
    private final Set<ChunkPos> chunks = new LinkedHashSet<>();
    private final List<BlockPos> snacks = new ArrayList<>();
    private ServerPlayer player;

    private int stage, waited, tick;
    private final Histogram tickTime = new Histogram(), snackTime = new Histogram(), entityTime = new Histogram();
    private final JsonArray series = new JsonArray();
    private long windowTick, windowSnack, windowEntity, windowMax;
    private int windowCount, cleared, clears, maxPokemon;
    private long sumPokemon;
    private int samples;

    // interleaved A/B: the optimisations are switched off and on every `alternate` ticks within the same run
    private final int alternate;
    private boolean modeOn;
    private boolean savedSnacks, savedEntities;
    // per-attempt A/B (AttemptAlternation): optimizeSnacks as it was before the run
    private boolean attemptAb, savedSnacksAttempts;
    private final Histogram[] abTick = {new Histogram(), new Histogram()}, abSnack = {new Histogram(), new Histogram()},
            abEntity = {new Histogram(), new Histogram()};
    private final long[] abPokemonTicks = new long[2], abAttempts = new long[2];

    PlatformBench(CommandSourceStack src, ServerLevel level, int x, int z, int y, int snackCount, int ticks, int speed,
                  int clearEvery, long seed, String label, List<ResourceLocation> baits) {
        this(src, level, x, z, y, snackCount, ticks, speed, clearEvery, seed, label, baits, 0);
    }

    PlatformBench(CommandSourceStack src, ServerLevel level, int x, int z, int y, int snackCount, int ticks, int speed,
                  int clearEvery, long seed, String label, List<ResourceLocation> baits, int alternate) {
        this.alternate = alternate;
        this.src = src; this.level = level; this.y = y; this.snackCount = Math.min(128, snackCount);
        this.ticks = ticks; this.speed = speed; this.clearEvery = clearEvery; this.label = label; this.baits = baits;
        this.rng = RandomSource.create(seed);
        this.warmup = 200;
        int cx = (x >> 4) - 1, cz = (z >> 4) - 1;  // the platform covers chunks cx..cx+2, cz..cz+2
        this.x0 = cx << 4;
        this.z0 = cz << 4;
        for (int i = cx - 2; i <= cx + 4; i++) for (int j = cz - 2; j <= cz + 4; j++) chunks.add(new ChunkPos(i, j));
    }

    @Override public String status() {
        return "platform '" + label + "' stage " + stage + " tick " + tick + "/" + ticks + " attempts " + SnackStats.ATTEMPTS.count();
    }

    private void send(String msg) {
        CobblemonFasterSnacks.LOGGER.info("[bench] {}", msg);
        if (src != null) src.sendSystemMessage(Component.literal(msg));
    }

    private AABB area() {
        return new AABB(x0 - 32, level.getMinBuildHeight(), z0 - 32, x0 + 80, level.getMaxBuildHeight(), z0 + 80);
    }

    private List<PokemonEntity> wildPokemon() {
        return level.getEntitiesOfClass(PokemonEntity.class, area(), p -> p.getOwnerUUID() == null);
    }

    @Override public boolean tick(MinecraftServer server, long tickStart) {
        switch (stage) {
            case 0 -> {
                for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, true);
                send("platform '" + label + "': loading " + chunks.size() + " chunks");
                stage = 1;
            }
            case 1 -> {
                boolean loaded = chunks.stream().allMatch(c -> level.areEntitiesLoaded(c.toLong()));
                if (loaded || ++waited > 1200) { build(); stage = 2; tick = 0; }
            }
            case 2 -> {
                if (++tick >= warmup) {
                    stage = 3; tick = 0;
                    SnackStats.reset();
                    EntityStats.enabled = true;
                    EntityStats.resetTick();
                    AttemptAlternation.reset();
                    attemptAb = AttemptAlternation.enabled;
                    savedSnacksAttempts = FasterSnacksConfig.INSTANCE.optimizeSnacks;
                    if (alternate > 0) {
                        savedSnacks = FasterSnacksConfig.INSTANCE.optimizeSnacks;
                        savedEntities = FasterSnacksConfig.INSTANCE.optimizeEntities;
                        setMode(false);
                    }
                    send("platform '" + label + "': running " + ticks + " ticks, " + snacks.size() + " snacks, speed x" + speed);
                }
            }
            case 3 -> {
                // the entity time of this tick was accumulated during the level tick that just ran
                long entityNanos = EntityStats.tickNanos;
                long pokemonTicks = EntityStats.ticked;
                EntityStats.resetTick();
                int attemptsBefore = SnackStats.ATTEMPTS.count();
                long snackNanos = 0;
                int threshold = 3 * speed; // vanilla randomTickSpeed = 3 picks per 4096 blocks per tick
                for (BlockPos pos : snacks) {
                    if (rng.nextInt(4096) >= threshold) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!(state.getBlock() instanceof PokeSnackBlock)) continue;
                    long t0 = System.nanoTime();
                    state.randomTick(level, pos, level.random);
                    snackNanos += System.nanoTime() - t0;
                }
                if (clearEvery > 0 && tick > 0 && tick % clearEvery == 0) {
                    List<PokemonEntity> wild = wildPokemon();
                    wild.forEach(Entity::discard);
                    cleared += wild.size();
                    clears++;
                }
                long tickNanos = System.nanoTime() - tickStart;
                tickTime.add(tickNanos);
                snackTime.add(snackNanos);
                entityTime.add(entityNanos);
                windowTick += tickNanos; windowSnack += snackNanos; windowEntity += entityNanos; windowCount++;
                windowMax = Math.max(windowMax, tickNanos);
                if (windowCount == 100) flushWindow();
                if (alternate > 0) {
                    int m = modeOn ? 1 : 0;   // the mode that was active for this whole tick
                    abTick[m].add(tickNanos);
                    abSnack[m].add(snackNanos);
                    abEntity[m].add(entityNanos);
                    abPokemonTicks[m] += pokemonTicks;
                    abAttempts[m] += SnackStats.ATTEMPTS.count() - attemptsBefore;
                    if ((tick + 1) % alternate == 0) setMode(!modeOn);
                }
                if (++tick >= ticks) { finish(); return true; }
            }
        }
        return false;
    }

    private void setMode(boolean on) {
        modeOn = on;
        FasterSnacksConfig.INSTANCE.optimizeSnacks = on;
        FasterSnacksConfig.INSTANCE.optimizeEntities = on;
    }

    private JsonObject abJson(int m) {
        JsonObject o = new JsonObject();
        o.add("tickTime", abTick[m].toJson());
        o.add("snackTimePerTick", abSnack[m].toJson());
        o.add("pokemonTickTimePerTick", abEntity[m].toJson());
        o.addProperty("pokemonTicks", abPokemonTicks[m]);
        o.addProperty("usPerPokemonTick", abPokemonTicks[m] == 0 ? 0 : r3(abEntity[m].totalNanos() / 1e3 / abPokemonTicks[m]));
        o.addProperty("attempts", abAttempts[m]);
        o.addProperty("snackMsPerAttempt", abAttempts[m] == 0 ? 0 : r3(abSnack[m].totalNanos() / 1e6 / abAttempts[m]));
        o.addProperty("ticksOver50ms", abTick[m].countAboveMs(50));
        return o;
    }

    private void flushWindow() {
        int pokemon = wildPokemon().size();
        maxPokemon = Math.max(maxPokemon, pokemon);
        sumPokemon += pokemon;
        samples++;
        JsonObject w = new JsonObject();
        w.addProperty("tick", tick);
        w.addProperty("tickMeanMs", r3(windowTick / 1e6 / windowCount));
        w.addProperty("tickMaxMs", r3(windowMax / 1e6));
        w.addProperty("snackMeanMs", r3(windowSnack / 1e6 / windowCount));
        w.addProperty("pokemonTickMeanMs", r3(windowEntity / 1e6 / windowCount));
        w.addProperty("pokemon", pokemon);
        series.add(w);
        windowTick = windowSnack = windowEntity = windowMax = 0;
        windowCount = 0;
    }

    private static double r3(double v) { return Math.round(v * 1000.0) / 1000.0; }

    private double meanPokemonForCost() { return samples == 0 ? 0 : sumPokemon / (double) samples; }

    private void build() {
        clearArea();
        BlockState floor = Blocks.GRASS_BLOCK.defaultBlockState();
        for (int bx = 0; bx < 48; bx++) {
            for (int bz = 0; bz < 48; bz++) {
                level.setBlock(new BlockPos(x0 + bx, y, z0 + bz), floor, 2);
                for (int dy = 1; dy <= 6; dy++) level.setBlock(new BlockPos(x0 + bx, y + dy, z0 + bz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        // snacks in a checkerboard on the centre chunk: 128 of its 256 cells
        for (int bx = 16; bx < 32 && snacks.size() < snackCount; bx++) {
            for (int bz = 16; bz < 32 && snacks.size() < snackCount; bz++) {
                if (((bx + bz) & 1) != 0) continue;
                BlockPos pos = new BlockPos(x0 + bx, y + 1, z0 + bz);
                level.setBlock(pos, CobblemonBlocks.POKE_SNACK.defaultBlockState(), 3);
                if (level.getBlockEntity(pos) instanceof PokeSnackBlockEntity be && !baits.isEmpty()) {
                    be.setBaitEffectsComponent(new BaitEffectsComponent(baits));
                }
                snacks.add(pos);
            }
        }
        SnackBench.trackSnacks(snacks, false);
        String name = "cfplatform";
        FakePlayer fp = FakePlayer.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        fp.setPos(x0 + 24.5, y + 1, z0 + 24.5);
        player = fp;
        level.players().add(fp);
        send("platform '" + label + "': built at " + x0 + " " + y + " " + z0 + " (" + snacks.size() + " snacks, snacks=" + FasterSnacksConfig.INSTANCE.optimizeSnacks
                + ", pokemon=" + FasterSnacksConfig.INSTANCE.optimizeEntities + ")");
    }

    private void clearArea() {
        level.getEntitiesOfClass(LivingEntity.class, area(), e -> !(e instanceof Player)).forEach(Entity::discard);
    }

    private void finish() {
        EntityStats.enabled = false;
        JsonObject o = new JsonObject();
        o.addProperty("type", "platform");
        o.addProperty("label", label);
        o.addProperty("optimizeSnacks", FasterSnacksConfig.INSTANCE.optimizeSnacks);
        o.addProperty("optimizeEntities", FasterSnacksConfig.INSTANCE.optimizeEntities);
        o.addProperty("snackTickBudgetMs", FasterSnacksConfig.INSTANCE.snackTickBudgetMs);
        o.addProperty("deferredBites", fr.coquelicot.fastersnacks.snack.SnackScheduler.deferred);
        o.addProperty("replayedBites", fr.coquelicot.fastersnacks.snack.SnackScheduler.replayed);
        o.addProperty("preloadedClasses", fr.coquelicot.fastersnacks.perf.ClassPreloader.loaded);
        o.addProperty("preloadMs", fr.coquelicot.fastersnacks.perf.ClassPreloader.millis);
        o.addProperty("pokemonTickMicrosPerPokemon", meanPokemonForCost() == 0 ? 0 : r3(entityTime.meanMs() * 1000 / meanPokemonForCost()));
        o.addProperty("cobblemonVersion", CobblemonFasterSnacks.cobblemonVersion());
        o.addProperty("snacks", snacks.size());
        o.addProperty("ticks", ticks);
        o.addProperty("speedMultiplier", speed);
        o.addProperty("clearEvery", clearEvery);
        o.addProperty("clears", clears);
        o.addProperty("clearedPokemon", cleared);
        o.addProperty("maxPokemon", maxPokemon);
        o.addProperty("meanPokemon", samples == 0 ? 0 : r3(sumPokemon / (double) samples));
        o.add("tickTime", tickTime.toJson());
        o.add("snackTimePerTick", snackTime.toJson());
        o.add("pokemonTickTimePerTick", entityTime.toJson());
        o.add("attempts", SnackStats.ATTEMPTS.toJson());
        o.addProperty("spawned", SnackStats.spawned);
        o.addProperty("ticksOver50ms", tickTime.countAboveMs(50));
        o.addProperty("ticksOver25ms", tickTime.countAboveMs(25));
        o.add("memo", SnackStats.memoJson());
        o.add("series", series);
        if (attemptAb) {
            JsonObject am = new JsonObject();
            am.add("off", AttemptAlternation.BY_MODE[0].toJson());
            am.add("on", AttemptAlternation.BY_MODE[1].toJson());
            o.add("attemptsByMode", am);
        }
        if (alternate > 0) {
            o.addProperty("alternateEvery", alternate);
            JsonObject ab = new JsonObject();
            ab.add("off", abJson(0));
            ab.add("on", abJson(1));
            o.add("interleaved", ab);
            FasterSnacksConfig.INSTANCE.optimizeSnacks = savedSnacks;
            FasterSnacksConfig.INSTANCE.optimizeEntities = savedEntities;
        }
        java.nio.file.Path file = BenchOutput.write("platform", label, o);
        send("platform '" + label + "' done: tick " + tickTime.summary());
        if (alternate > 0) {
            send("  OFF: tick " + abTick[0].summary() + " | snack/tick " + String.format("%.3f", abSnack[0].meanMs()) + " ms | "
                    + abJson(0).get("usPerPokemonTick").getAsDouble() + " us/pokemon tick | attempts " + abAttempts[0]);
            send("  ON:  tick " + abTick[1].summary() + " | snack/tick " + String.format("%.3f", abSnack[1].meanMs()) + " ms | "
                    + abJson(1).get("usPerPokemonTick").getAsDouble() + " us/pokemon tick | attempts " + abAttempts[1]);
        }
        send("  snacks/tick " + snackTime.summary() + " | pokemon/tick " + entityTime.summary());
        if (attemptAb) {
            send("  attempts OFF: " + AttemptAlternation.BY_MODE[0].summary());
            send("  attempts ON:  " + AttemptAlternation.BY_MODE[1].summary());
        }
        send("  attempts " + SnackStats.ATTEMPTS.summary() + " | spawned " + SnackStats.spawned + " | pokemon max " + maxPokemon + " mean "
                + o.get("meanPokemon").getAsDouble() + " | " + o.get("pokemonTickMicrosPerPokemon").getAsDouble() + " us/pokemon/tick | deferred "
                + fr.coquelicot.fastersnacks.snack.SnackScheduler.deferred + " -> " + file);
        cleanup();
    }

    @Override public void cancel() { cleanup(); }

    private void cleanup() {
        EntityStats.enabled = false;
        if (attemptAb) FasterSnacksConfig.INSTANCE.optimizeSnacks = savedSnacksAttempts;
        if (alternate > 0 && stage >= 3) {
            FasterSnacksConfig.INSTANCE.optimizeSnacks = savedSnacks;
            FasterSnacksConfig.INSTANCE.optimizeEntities = savedEntities;
        }
        if (player != null) level.players().remove(player);
        clearArea();
        for (BlockPos p : snacks) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        SnackBench.trackSnacks(List.of(), true);
        for (ChunkPos c : chunks) level.setChunkForced(c.x, c.z, false);
    }
}
