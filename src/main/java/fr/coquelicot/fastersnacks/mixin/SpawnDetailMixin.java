package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.spawning.detail.PokemonSpawnDetail;
import com.cobblemon.mod.common.api.spawning.detail.SpawnAction;
import com.cobblemon.mod.common.api.spawning.detail.SpawnDetail;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import com.cobblemon.mod.common.api.spawning.selection.FlatSpawnablePositionWeightedSelector;
import com.cobblemon.mod.common.api.spawning.selection.SpawnSelectionData;
import fr.coquelicot.fastersnacks.snack.SingleChoice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips the post-pick cleanup of the selection tables when the pick is the only one of its pass (see SingleChoice). */
@Mixin(value = SpawnDetail.class, remap = false)
public abstract class SpawnDetailMixin {

    @Inject(method = "onSelection", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$skipLastCleanup(SpawnablePosition position, SpawnAction<?> action, SpawnSelectionData selectionData, CallbackInfo ci) {
        // Only the base implementation (a plain removal) is skipped; herds and other overrides keep their logic.
        if ((Object) this.getClass() != PokemonSpawnDetail.class) return;
        if (!(selectionData instanceof FlatSpawnablePositionWeightedSelector.SeparatedSelectionData data)) return;
        if (SingleChoice.isLastChoice(data.getSpawner(), position.getWorld().getServer().getTickCount())) ci.cancel();
    }
}
