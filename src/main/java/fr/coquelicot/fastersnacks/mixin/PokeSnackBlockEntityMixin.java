package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.block.entity.PokeSnackBlockEntity;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import fr.coquelicot.fastersnacks.snack.SnackScheduler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PokeSnackBlockEntity.class, remap = false)
public abstract class PokeSnackBlockEntityMixin {

    @Inject(method = "attemptSpawn", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$beginAttempt(Player player, CallbackInfo ci) {
        if (SnackScheduler.deferIfOverBudget((PokeSnackBlockEntity) (Object) this)) {
            ci.cancel(); // runs at the start of the next tick instead (see SnackScheduler)
            return;
        }
        SnackStats.beginAttempt();
    }

    @Inject(method = "attemptSpawn", at = @At("RETURN"))
    private void fastersnacks$endAttempt(Player player, CallbackInfo ci) {
        SnackScheduler.addUsed(SnackStats.endAttempt());
    }
}
