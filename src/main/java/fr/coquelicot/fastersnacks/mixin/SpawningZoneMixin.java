package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.spawning.SpawningZone;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.snack.LazyNearbyBlocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Hands out lazy nearby-block lists (see LazyNearbyBlocks). */
@Mixin(value = SpawningZone.class, remap = false)
public abstract class SpawningZoneMixin {

    @Inject(method = "nearbyBlocks(IIIII)Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void fastersnacks$lazyNearbyBlocks(int centerX, int centerY, int centerZ, int maxHorizontalRadius, int maxVerticalRadius,
                                                CallbackInfoReturnable<List<BlockState>> cir) {
        if (LazyNearbyBlocks.building || !FasterSnacksConfig.INSTANCE.optimizeSnacks || !FasterSnacksConfig.INSTANCE.lazyNearbyBlocks) return;
        SpawningZone zone = (SpawningZone) (Object) this;
        if (!zone.getWorld().getServer().isSameThread()) return;
        cir.setReturnValue(new LazyNearbyBlocks(zone, centerX, centerY, centerZ, maxHorizontalRadius, maxVerticalRadius));
    }
}
