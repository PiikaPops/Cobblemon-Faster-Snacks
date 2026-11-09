package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import kotlin.Pair;
import net.minecraft.world.entity.ai.memory.NearestVisibleLivingEntities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every 60 ticks, every wild Pokémon looks through all the entities it can see for a herd leader to follow,
 * and they all do it on the same tick. For a Pokémon whose species tolerates no herd leader (most of them),
 * HerdBehaviour.bestMatchLeader returns null for every candidate, so the search can only answer null:
 * we answer it directly. The filter it runs on each candidate has no side effect, so nothing else changes.
 */
@Mixin(targets = "com.cobblemon.mod.common.api.ai.config.task.FindHerdLeaderTaskConfig$Companion", remap = false)
public abstract class FindHerdLeaderMixin {

    @Inject(method = "getBestHerdLeader", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$noHerdForThisSpecies(Pair<?, ?> currentLeader, NearestVisibleLivingEntities nearbyEntities, PokemonEntity entity,
                                                  CallbackInfoReturnable<PokemonEntity> cir) {
        if (!FasterSnacksConfig.INSTANCE.optimizeEntities || currentLeader != null) return;
        if (entity.getBehaviour().getHerd().getToleratedLeaders().isEmpty()) cir.setReturnValue(null);
    }
}
