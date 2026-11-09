package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.spawning.detail.SpawnAction;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.spawner.SpawningZoneInput;
import fr.coquelicot.fastersnacks.snack.SingleChoice;
import net.minecraft.server.MinecraftServer;
import com.cobblemon.mod.common.api.spawning.influence.SpawningInfluence;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.spawner.BasicSpawner;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import fr.coquelicot.fastersnacks.CobblemonFasterSnacks;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.snack.SpawnMatchMemo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = BasicSpawner.class, remap = false)
public abstract class BasicSpawnerMixin {

    @Inject(method = "getMatchingSpawns", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$memoisedMatchingSpawns(String bucket, SpawnablePosition position,
                                                      CallbackInfoReturnable<List<SpawnDetail>> cir) {
        FasterSnacksConfig config = FasterSnacksConfig.INSTANCE;
        if (!config.optimizeSnacks) return;
        Object self = this;
        if (!(self instanceof PokeSnackBlockEntity.Spawner) && !config.optimizeAllAreaSpawners) return;
        Spawner spawner = (Spawner) self;
        MinecraftServer server = position.getWorld().getServer();
        if (!server.isSameThread()) return; // the memo is single-threaded: leave any off-thread spawning to Cobblemon
        long tick = server.getTickCount();

        boolean timing = SpawnMatchMemo.debugTimings;
        long t0 = timing ? System.nanoTime() : 0;
        List<SpawnDetail> fast = SpawnMatchMemo.getMatchingSpawns(spawner, bucket, position, tick);
        if (timing) SpawnMatchMemo.matchNanos += System.nanoTime() - t0;
        if (fast == null) return; // unsupported position type: let Cobblemon do it

        if (config.verifySnackOptimizations) {
            List<SpawnDetail> reference = new ArrayList<>();
            for (SpawnDetail d : spawner.getSpawnPool().retrieve(bucket, position)) if (d.isSatisfiedBy(position)) reference.add(d);
            for (SpawningInfluence i : position.getInfluences()) {
                List<SpawnDetail> injected = i.injectSpawns(bucket, position);
                if (injected != null) reference.addAll(injected);
            }
            if (!sameList(fast, reference)) {
                if (SpawnMatchMemo.mismatches++ < 5) {
                    CobblemonFasterSnacks.LOGGER.warn("[verify] memoised result differs at {} ({}): {} vs {}",
                            position.getPosition(), position.getClass().getSimpleName(), fast.size(), reference.size());
                }
                cir.setReturnValue(reference);
                return;
            }
        }
        cir.setReturnValue(fast);
    }

    @Inject(method = "calculateSpawnActionsForArea", at = @At("HEAD"))
    private void fastersnacks$beginSingleChoice(SpawningZoneInput zoneInput, Integer maxSpawns, CallbackInfoReturnable<List<SpawnAction<?>>> cir) {
        FasterSnacksConfig config = FasterSnacksConfig.INSTANCE;
        if (!config.optimizeSnacks || maxSpawns == null || maxSpawns != 1) return;
        Object self = this;
        if (!(self instanceof PokeSnackBlockEntity.Spawner) && !config.optimizeAllAreaSpawners) return;
        if (!zoneInput.getWorld().getServer().isSameThread()) return;
        SingleChoice.begin((Spawner) self, zoneInput.getWorld().getServer().getTickCount());
    }

    @Inject(method = "calculateSpawnActionsForArea", at = @At("RETURN"))
    private void fastersnacks$endSingleChoice(SpawningZoneInput zoneInput, Integer maxSpawns, CallbackInfoReturnable<List<SpawnAction<?>>> cir) {
        SingleChoice.end();
    }

    private static boolean sameList(List<SpawnDetail> a, List<SpawnDetail> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
}
