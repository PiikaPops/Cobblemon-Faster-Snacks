package fr.coquelicot.fastersnacks.mixin;

import com.bedrockk.molang.runtime.MoLangRuntime;
import com.bedrockk.molang.runtime.MoParams;
import com.bedrockk.molang.runtime.struct.QueryStruct;
import com.cobblemon.mod.common.api.molang.MoLangFunctions;
import com.cobblemon.mod.common.api.spawning.position.SpawnablePosition;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/**
 * Every spawnable position builds a full MoLang runtime in its constructor (all the standard query
 * functions are copied into a fresh map). A Poké Snack computation creates 300 to 1800 positions and
 * almost none of them is ever asked for its runtime (only MoLang-based spawn rules and NPC spawns use it).
 *
 * The standard functions are now added the first time the runtime is requested. The runtime object is
 * the same and ends with the same function map as before: standard functions, then "spawnable_position"
 * added last, exactly like the original constructor.
 */
@Mixin(value = SpawnablePosition.class, remap = false)
public abstract class SpawnablePositionMixin {
    @Shadow @Final private MoLangRuntime runtime;

    @Unique private boolean fastersnacks$runtimeReady;

    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/cobblemon/mod/common/api/molang/MoLangFunctions;setup(Lcom/bedrockk/molang/runtime/MoLangRuntime;)Lcom/bedrockk/molang/runtime/MoLangRuntime;"))
    private MoLangRuntime fastersnacks$deferSetup(MoLangFunctions functions, MoLangRuntime fresh) {
        if (FasterSnacksConfig.INSTANCE.optimizeSnacks && FasterSnacksConfig.INSTANCE.lazyPositionRuntime) return fresh;
        fastersnacks$runtimeReady = true;
        return functions.setup(fresh);
    }

    @Inject(method = "getRuntime", at = @At("HEAD"))
    private void fastersnacks$completeSetup(CallbackInfoReturnable<MoLangRuntime> cir) {
        if (fastersnacks$runtimeReady) return;
        fastersnacks$runtimeReady = true;
        QueryStruct query = runtime.getEnvironment().query;
        Function<MoParams, Object> own = query.getFunctions().get("spawnable_position");
        MoLangFunctions.INSTANCE.setup(runtime);
        if (own != null) query.addFunction("spawnable_position", own);
    }
}
