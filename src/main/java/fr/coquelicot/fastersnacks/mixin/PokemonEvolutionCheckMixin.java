package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.pokemon.evolution.Evolution;
import com.cobblemon.mod.common.api.pokemon.requirement.Requirement;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.requirements.BlocksTraveledRequirement;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * PokemonEntity.travel asks this every tick for every Pokémon. Cobblemon answers by sorting the form's
 * evolutions and copying every requirement into two new lists, only to check whether one of them is a
 * blocks-traveled requirement. Whether such a requirement exists does not depend on the order, so the
 * same answer comes from walking the form's evolutions directly.
 */
@Mixin(value = Pokemon.class, remap = false)
public abstract class PokemonEvolutionCheckMixin {

    @Inject(method = "hasBlocksTraveledRequirement", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$hasBlocksTraveledRequirement(CallbackInfoReturnable<Boolean> cir) {
        if (!FasterSnacksConfig.INSTANCE.optimizeEntities) return;
        for (Evolution evolution : ((Pokemon) (Object) this).getForm().getEvolutions()) {
            for (Requirement requirement : evolution.getRequirements()) {
                if (requirement instanceof BlocksTraveledRequirement) {
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
        cir.setReturnValue(false);
    }
}
