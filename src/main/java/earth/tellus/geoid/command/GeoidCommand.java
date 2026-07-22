package earth.tellus.geoid.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import earth.tellus.geoid.config.GeoidConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * {@code /geoid} — OP-only (permission level 2, same bar as {@code /gamerule}) runtime configuration
 * for the spherical-earth engine. Lets {@link GeoidConfig} toggles/tunables be changed live from in
 * game instead of requiring a source edit and a server restart.
 *
 * <p>Targets 1.21.1: {@code hasPermissionLevel(int)} lives on {@code AbstractServerCommandSource},
 * which {@link ServerCommandSource} extends (confirmed against the 1.21.1 Yarn mappings). 1.21.11
 * later replaced this with a structured permission-requirement system — if this mod is ever re-bumped
 * past 1.21.1, swap this back to {@code CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK)}.
 */
public final class GeoidCommand {

    private GeoidCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("geoid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("status").executes(GeoidCommand::status))
                .then(boolToggle("circumnavigation", "enableCircumnavigation",
                        cfg -> cfg.enableCircumnavigation, (cfg, v) -> cfg.enableCircumnavigation = v))
                .then(boolToggle("gravity", "enableSphericalGravity",
                        cfg -> cfg.enableSphericalGravity, (cfg, v) -> cfg.enableSphericalGravity = v))
                .then(boolToggle("coreExitAssist", "coreExitAssist",
                        cfg -> cfg.coreExitAssist, (cfg, v) -> cfg.coreExitAssist = v))
                .then(boolToggle("debugFold", "debugFoldFeedback",
                        cfg -> cfg.debugFoldFeedback, (cfg, v) -> cfg.debugFoldFeedback = v))
                .then(CommandManager.literal("seaLevel")
                        .then(CommandManager.argument("y", DoubleArgumentType.doubleArg())
                                .executes(ctx -> {
                                    GeoidConfig.get().seaLevelY = DoubleArgumentType.getDouble(ctx, "y");
                                    return feedback(ctx.getSource(), "seaLevelY", GeoidConfig.get().seaLevelY);
                                })))
                .then(CommandManager.literal("coreEntryDepth")
                        .then(CommandManager.argument("blocks", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> {
                                    GeoidConfig.get().coreEntryDepth = DoubleArgumentType.getDouble(ctx, "blocks");
                                    return feedback(ctx.getSource(), "coreEntryDepth", GeoidConfig.get().coreEntryDepth);
                                })))
                .then(CommandManager.literal("seamOverlapChunks")
                        .then(CommandManager.argument("chunks", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    GeoidConfig.get().seamOverlapChunks = IntegerArgumentType.getInteger(ctx, "chunks");
                                    return feedback(ctx.getSource(), "seamOverlapChunks", GeoidConfig.get().seamOverlapChunks);
                                })))
                .then(CommandManager.literal("antipodePreloadRadius")
                        .then(CommandManager.argument("chunks", IntegerArgumentType.integer(0))
                                .executes(ctx -> {
                                    GeoidConfig.get().antipodePreloadRadius = IntegerArgumentType.getInteger(ctx, "chunks");
                                    return feedback(ctx.getSource(), "antipodePreloadRadius", GeoidConfig.get().antipodePreloadRadius);
                                })))
                .then(CommandManager.literal("chunkBudgetPerTick")
                        .then(CommandManager.argument("n", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    GeoidConfig.get().chunkBudgetPerTick = IntegerArgumentType.getInteger(ctx, "n");
                                    return feedback(ctx.getSource(), "chunkBudgetPerTick", GeoidConfig.get().chunkBudgetPerTick);
                                })))
        );
    }

    private interface Getter {
        boolean get(GeoidConfig cfg);
    }

    private interface Setter {
        void set(GeoidConfig cfg, boolean value);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> boolToggle(
            String literal, String fieldName, Getter getter, Setter setter) {
        return CommandManager.literal(literal)
                .executes(ctx -> {
                    // No argument: report the current value without changing it.
                    return feedback(ctx.getSource(), fieldName, getter.get(GeoidConfig.get()));
                })
                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> {
                            boolean value = BoolArgumentType.getBool(ctx, "enabled");
                            setter.set(GeoidConfig.get(), value);
                            return feedback(ctx.getSource(), fieldName, value);
                        }));
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        GeoidConfig cfg = GeoidConfig.get();
        ctx.getSource().sendFeedback(() -> Text.literal(
                "Geoid config:"
                        + "\n circumnavigation=" + cfg.enableCircumnavigation
                        + " gravity=" + cfg.enableSphericalGravity
                        + " coreExitAssist=" + cfg.coreExitAssist
                        + " debugFold=" + cfg.debugFoldFeedback
                        + "\n seaLevelY=" + cfg.seaLevelY
                        + " coreEntryDepth=" + cfg.coreEntryDepth
                        + "\n seamOverlapChunks=" + cfg.seamOverlapChunks
                        + " antipodePreloadRadius=" + cfg.antipodePreloadRadius
                        + " chunkBudgetPerTick=" + cfg.chunkBudgetPerTick), false);
        return 1;
    }

    private static int feedback(ServerCommandSource source, String field, Object value) {
        source.sendFeedback(() -> Text.literal("Geoid: " + field + " = " + value), true);
        return 1;
    }
}
