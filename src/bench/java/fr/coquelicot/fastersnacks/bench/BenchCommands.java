package fr.coquelicot.fastersnacks.bench;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;

import static fr.coquelicot.fastersnacks.command.FasterSnacksCommands.reply;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Benchmark commands, only in the benchmark build (they place blocks and remove entities: test worlds only).
 * <pre>
 * /fastersnacks bench status|cancel
 * /fastersnacks bench alternateattempts &lt;true|false&gt;
 * /fastersnacks bench micro &lt;x&gt; &lt;z&gt; &lt;iterations&gt; &lt;realAttempts&gt; &lt;label&gt; &lt;baits|none&gt;
 * /fastersnacks bench macro &lt;x&gt; &lt;z&gt; &lt;count&gt; &lt;spacing&gt; &lt;baselineTicks&gt; &lt;activeTicks&gt; &lt;speed&gt; &lt;discard&gt; &lt;seed&gt; &lt;label&gt; &lt;baits|none&gt;
 * /fastersnacks bench platform &lt;x&gt; &lt;z&gt; &lt;y&gt; &lt;snacks&gt; &lt;ticks&gt; &lt;speed&gt; &lt;clearEvery&gt; &lt;seed&gt; &lt;label&gt; &lt;baits|none&gt;
 * /fastersnacks bench platformab ... &lt;clearEvery&gt; &lt;block&gt; &lt;seed&gt; &lt;label&gt; &lt;baits|none&gt;
 * </pre>
 * Bait ids are the file names of data/cobblemon/spawn_bait_effects without their folder, e.g. cobblemon:wacan_berry.
 */
public final class BenchCommands {
    private BenchCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(literal("fastersnacks").requires(s -> s.hasPermission(3))
                .then(literal("bench")
                        .then(literal("status").executes(c -> reply(c, SnackBench.status())))
                        .then(literal("cancel").executes(c -> { SnackBench.cancel(); return reply(c, "Bench cancelled."); }))
                        .then(literal("alternateattempts").then(argument("enabled", BoolArgumentType.bool()).executes(c -> {
                            AttemptAlternation.enabled = BoolArgumentType.getBool(c, "enabled");
                            return reply(c, "Per-attempt A/B " + (AttemptAlternation.enabled ? "on" : "off") + ".");
                        })))
                        .then(literal("micro")
                                .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .then(argument("iterations", IntegerArgumentType.integer(1, 100000))
                                .then(argument("realAttempts", IntegerArgumentType.integer(0, 100000))
                                .then(argument("label", StringArgumentType.word())
                                .then(argument("baits", StringArgumentType.greedyString()).executes(BenchCommands::micro))))))))
                        .then(literal("platformab")
                                .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer(-64, 300))
                                .then(argument("snacks", IntegerArgumentType.integer(1, 128))
                                .then(argument("ticks", IntegerArgumentType.integer(1, 1000000))
                                .then(argument("speed", IntegerArgumentType.integer(1, 1365))
                                .then(argument("clearEvery", IntegerArgumentType.integer(0, 1000000))
                                .then(argument("block", IntegerArgumentType.integer(1, 100000))
                                .then(argument("seed", LongArgumentType.longArg())
                                .then(argument("label", StringArgumentType.word())
                                .then(argument("baits", StringArgumentType.greedyString()).executes(BenchCommands::platformAb)))))))))))))
                        .then(literal("platform")
                                .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer(-64, 300))
                                .then(argument("snacks", IntegerArgumentType.integer(1, 128))
                                .then(argument("ticks", IntegerArgumentType.integer(1, 1000000))
                                .then(argument("speed", IntegerArgumentType.integer(1, 1365))
                                .then(argument("clearEvery", IntegerArgumentType.integer(0, 1000000))
                                .then(argument("seed", LongArgumentType.longArg())
                                .then(argument("label", StringArgumentType.word())
                                .then(argument("baits", StringArgumentType.greedyString()).executes(BenchCommands::platform))))))))))))
                        .then(literal("macro")
                                .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                .then(argument("count", IntegerArgumentType.integer(1, 2000))
                                .then(argument("spacing", IntegerArgumentType.integer(1, 256))
                                .then(argument("baselineTicks", IntegerArgumentType.integer(1, 1000000))
                                .then(argument("activeTicks", IntegerArgumentType.integer(1, 1000000))
                                .then(argument("speed", IntegerArgumentType.integer(1, 1365))
                                .then(argument("discard", BoolArgumentType.bool())
                                .then(argument("seed", LongArgumentType.longArg())
                                .then(argument("label", StringArgumentType.word())
                                .then(argument("baits", StringArgumentType.greedyString()).executes(BenchCommands::macro)))))))))))))));
    }

    private static int micro(CommandContext<CommandSourceStack> c) {
        boolean ok = SnackBench.startMicro(c.getSource(), c.getSource().getLevel(),
                IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"),
                IntegerArgumentType.getInteger(c, "iterations"), IntegerArgumentType.getInteger(c, "realAttempts"),
                StringArgumentType.getString(c, "label"), StringArgumentType.getString(c, "baits"));
        return reply(c, ok ? "Micro bench started." : "A bench is already running: " + SnackBench.status());
    }

    private static int platform(CommandContext<CommandSourceStack> c) {
        boolean ok = SnackBench.startPlatform(c.getSource(), c.getSource().getLevel(),
                IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "y"),
                IntegerArgumentType.getInteger(c, "snacks"), IntegerArgumentType.getInteger(c, "ticks"),
                IntegerArgumentType.getInteger(c, "speed"), IntegerArgumentType.getInteger(c, "clearEvery"),
                LongArgumentType.getLong(c, "seed"), StringArgumentType.getString(c, "label"), StringArgumentType.getString(c, "baits"));
        return reply(c, ok ? "Platform bench started." : "A bench is already running: " + SnackBench.status());
    }

    private static int platformAb(CommandContext<CommandSourceStack> c) {
        boolean ok = SnackBench.startPlatform(c.getSource(), c.getSource().getLevel(),
                IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"), IntegerArgumentType.getInteger(c, "y"),
                IntegerArgumentType.getInteger(c, "snacks"), IntegerArgumentType.getInteger(c, "ticks"),
                IntegerArgumentType.getInteger(c, "speed"), IntegerArgumentType.getInteger(c, "clearEvery"),
                LongArgumentType.getLong(c, "seed"), StringArgumentType.getString(c, "label"), StringArgumentType.getString(c, "baits"),
                IntegerArgumentType.getInteger(c, "block"));
        return reply(c, ok ? "Interleaved platform bench started." : "A bench is already running: " + SnackBench.status());
    }

    private static int macro(CommandContext<CommandSourceStack> c) {
        boolean ok = SnackBench.startMacro(c.getSource(), c.getSource().getLevel(),
                IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "z"),
                IntegerArgumentType.getInteger(c, "count"), IntegerArgumentType.getInteger(c, "spacing"),
                IntegerArgumentType.getInteger(c, "baselineTicks"), IntegerArgumentType.getInteger(c, "activeTicks"),
                IntegerArgumentType.getInteger(c, "speed"), BoolArgumentType.getBool(c, "discard"),
                LongArgumentType.getLong(c, "seed"), StringArgumentType.getString(c, "label"),
                StringArgumentType.getString(c, "baits"));
        return reply(c, ok ? "Macro bench started." : "A bench is already running: " + SnackBench.status());
    }
}
