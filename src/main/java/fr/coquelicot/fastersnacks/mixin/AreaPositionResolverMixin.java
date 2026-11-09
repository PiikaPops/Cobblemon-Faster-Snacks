package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.spawning.SpawnCause;
import com.cobblemon.mod.common.api.spawning.position.AreaSpawnablePositionResolver;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * For every position of the zone that has a living entity closer than minimumDistanceBetweenEntities, Cobblemon checks
 * {@code !Intrinsics.areEqual(entityPosition, zone.getCause().getEntity())}: it compares a Vec3 with an Entity.
 * Vec3.equals returns false for anything that is not a Vec3 (null included), so the test is always true, whatever
 * getEntity returns. getEntity only looks the cause entity up (player list or level), without side effects, so the
 * lookup is skipped and null is compared instead: same result.
 */
@Mixin(value = AreaSpawnablePositionResolver.class, remap = false)
public interface AreaPositionResolverMixin {
    @Redirect(method = "resolve", at = @At(value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/spawning/SpawnCause;getEntity()Lnet/minecraft/world/entity/Entity;"))
    private Entity fastersnacks$skipCauseLookup(SpawnCause cause) {
        return FasterSnacksConfig.INSTANCE.optimizeSnacks ? null : cause.getEntity();
    }
}
