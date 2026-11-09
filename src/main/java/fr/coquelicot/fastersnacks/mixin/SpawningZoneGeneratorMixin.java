package fr.coquelicot.fastersnacks.mixin;

import com.cobblemon.mod.common.api.spawning.CobblemonSpawningZoneGenerator;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * For every block of a spawning zone, Cobblemon reads the sky light, then calls getMaxLocalRawBrightness, which reads
 * the sky light of the same position a second time. Minecraft 1.21.1 computes that brightness as
 * {@code max(blockLight, skyLight - skyDarken)}, or 15 outside the world limits, so the sky light read just before is
 * reused. Without a sky light engine the first read returns 0 and {@code max(blockLight, -skyDarken)} is still
 * {@code blockLight}, as in Minecraft (light values and skyDarken are never negative).
 * The remembered value is only used on the server thread, for the exact position it was read for.
 */
@Mixin(value = CobblemonSpawningZoneGenerator.class, remap = false)
public abstract class SpawningZoneGeneratorMixin {
    @Unique private static int fastersnacks$sky;
    @Unique private static long fastersnacks$skyPos;
    @Unique private static boolean fastersnacks$skyValid;
    @Unique private static Thread fastersnacks$serverThread;

    @Redirect(method = "generate", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/level/lighting/LayerLightEventListener;getLightValue(Lnet/minecraft/core/BlockPos;)I"))
    private int fastersnacks$rememberSkyLight(LayerLightEventListener sky, BlockPos pos) {
        int value = sky.getLightValue(pos);
        if (Thread.currentThread() == fastersnacks$serverThread) {
            fastersnacks$sky = value;
            fastersnacks$skyPos = pos.asLong();
            fastersnacks$skyValid = true;
        }
        return value;
    }

    @Redirect(method = "generate", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/server/level/ServerLevel;getMaxLocalRawBrightness(Lnet/minecraft/core/BlockPos;)I"))
    private int fastersnacks$brightness(ServerLevel world, BlockPos pos) {
        if (!world.getServer().isSameThread()) return world.getMaxLocalRawBrightness(pos);
        fastersnacks$serverThread = Thread.currentThread();
        boolean reuse = fastersnacks$skyValid && fastersnacks$skyPos == pos.asLong() && FasterSnacksConfig.INSTANCE.optimizeSnacks;
        fastersnacks$skyValid = false;
        if (!reuse) return world.getMaxLocalRawBrightness(pos);
        int x = pos.getX(), z = pos.getZ();
        if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) return 15;
        int block = world.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(pos);
        int brightness = Math.max(block, fastersnacks$sky - world.getSkyDarken());
        if (FasterSnacksConfig.INSTANCE.verifySnackOptimizations) {
            SnackStats.lightChecks++;
            if (brightness != world.getMaxLocalRawBrightness(pos)) SnackStats.lightMismatches++;
        }
        return brightness;
    }
}
