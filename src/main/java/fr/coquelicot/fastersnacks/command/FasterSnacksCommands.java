package fr.coquelicot.fastersnacks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.coquelicot.fastersnacks.config.FasterSnacksConfig;
import fr.coquelicot.fastersnacks.perf.SnackStats;
import fr.coquelicot.fastersnacks.snack.SnackScheduler;
import fr.coquelicot.fastersnacks.snack.SpawnMatchMemo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Admin commands (permission level 3):
 * <pre>
 * /fastersnacks stats [reset]
 * /fastersnacks optimize [true|false]
 * /fastersnacks snacks optimize [true|false]
 * /fastersnacks snacks verify [true|false]
 * /fastersnacks debug timings &lt;true|false&gt;
 * </pre>
 * Switches are saved to config/cobblemon_faster_snacks.json.
 */
public final class FasterSnacksCommands {
    private FasterSnacksCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("fastersnacks").requires(s -> s.hasPermission(3))
                .then(literal("stats")
                        .executes(FasterSnacksCommands::stats)
                        .then(literal("reset").executes(c -> { SnackStats.reset(); return reply(c, "Snack stats reset."); })))
                .then(literal("optimize")
                        .executes(c -> reply(c, "Snacks: " + config().optimizeSnacks + ", Pokémon: " + config().optimizeEntities))
                        .then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                            boolean on = BoolArgumentType.getBool(c, "enabled");
                            config().optimizeSnacks = on;
                            config().optimizeEntities = on;
                            FasterSnacksConfig.save();
                            return reply(c, "All optimisations: " + on);
                        })))
                .then(literal("snacks")
                        .then(literal("optimize")
                                .executes(c -> reply(c, "Snack optimisations: " + config().optimizeSnacks))
                                .then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                                    config().optimizeSnacks = BoolArgumentType.getBool(c, "enabled");
                                    FasterSnacksConfig.save();
                                    return reply(c, "Snack optimisations: " + config().optimizeSnacks);
                                })))
                        .then(literal("verify")
                                .executes(c -> reply(c, "Verification: " + config().verifySnackOptimizations))
                                .then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                                    config().verifySnackOptimizations = BoolArgumentType.getBool(c, "enabled");
                                    FasterSnacksConfig.save();
                                    return reply(c, "Verification: " + config().verifySnackOptimizations
                                            + (config().verifySnackOptimizations ? " (slower: every result is computed twice)" : ""));
                                }))))
                .then(literal("debug").then(literal("timings").then(argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                    SpawnMatchMemo.debugTimings = BoolArgumentType.getBool(ctx, "enabled");
                    return reply(ctx, "Debug timings: " + SpawnMatchMemo.debugTimings);
                })))));
    }

    private static FasterSnacksConfig config() { return FasterSnacksConfig.INSTANCE; }

    public static int reply(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        return reply(c, "Snack attempts: " + SnackStats.ATTEMPTS.summary() + " | spawned " + SnackStats.spawned
                + " | optimize=" + config().optimizeSnacks + " | deferred " + SnackScheduler.deferred
                + "\n" + SnackStats.memoSummary());
    }
}
