package fr.coquelicot.fastersnacks.bench.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import fr.coquelicot.fastersnacks.bench.EntityStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bench-only timer around a Pokémon's whole tick (AI, movement, collisions). Off unless a bench runs. */
@Mixin(PokemonEntity.class)
public abstract class PokemonEntityTimingMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void fastersnacks$beginTick(CallbackInfo ci) {
        if (EntityStats.enabled) EntityStats.begin();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void fastersnacks$endTick(CallbackInfo ci) {
        if (EntityStats.enabled) EntityStats.end();
    }
}
