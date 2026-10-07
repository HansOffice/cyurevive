package org.cyu.cyurevive.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.Permissions;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetService;

import net.minecraft.util.Util;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class PetCommands {
    static final Permission OP_PERMISSION = Permissions.COMMANDS_GAMEMASTER;

    private PetCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        Predicate<CommandSourceStack> admin = source -> source.permissions().hasPermission(OP_PERMISSION);
        dispatcher.register(Commands.literal("cyurevive")
            .executes(ctx -> HelpRenderer.send(ctx.getSource()))
            .then(Commands.literal("help")
                .executes(ctx -> HelpRenderer.send(ctx.getSource())))
            .then(Commands.literal("list")
                .executes(ctx -> player(ctx.getSource(),
                    p -> PetService.sendPetList(ctx.getSource(), p.getUUID(), null)))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .requires(admin)
                    .executes(ctx -> {
                        NameAndId target = target(ctx);
                        PetService.sendPetList(ctx.getSource(), target.id(), target.name());
                        return 1;
                    })))
            .then(Commands.literal("unbind")
                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                    .executes(ctx -> player(ctx.getSource(),
                        p -> PetService.unbindPet(ctx.getSource(), p.getUUID(), p.getName().getString(),
                            IntegerArgumentType.getInteger(ctx, "index"), true))))
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .requires(admin)
                    .then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            NameAndId target = target(ctx);
                            PetService.unbindPet(ctx.getSource(), target.id(), target.name(),
                                IntegerArgumentType.getInteger(ctx, "index"), false);
                            return 1;
                        }))))
            .then(Commands.literal("home")
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(ctx -> player(ctx.getSource(),
                        p -> PetService.nameHome(p, StringArgumentType.getString(ctx, "name"))))))
            .then(Commands.literal("recall")
                .executes(ctx -> player(ctx.getSource(), PetService::recallCommand)))
            .then(Commands.literal("revive")
                .requires(admin)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(ctx -> {
                        NameAndId target = target(ctx);
                        PetService.reviveAll(ctx.getSource(), target.id(), target.name());
                        return 1;
                    })))
            .then(Commands.literal("forbid")
                .requires(admin)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(ctx -> {
                        NameAndId target = target(ctx);
                        PetService.forbidPlayer(ctx.getSource(), target.id(), target.name());
                        return 1;
                    })))
            .then(Commands.literal("unforbid")
                .requires(admin)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(ctx -> {
                        NameAndId target = target(ctx);
                        PetService.unforbidPlayer(ctx.getSource(), target.id(), target.name());
                        return 1;
                    })))
            .then(Commands.literal("purge")
                .requires(admin)
                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                    .executes(ctx -> {
                        NameAndId target = target(ctx);
                        PetService.purgePlayer(ctx.getSource(), target.id(), target.name());
                        return 1;
                    })))
            .then(Commands.literal("status")
                .requires(admin)
                .executes(ctx -> {
                    PetService.sendStatus(ctx.getSource());
                    return 1;
                }))
            .then(Commands.literal("doctor")
                .requires(admin)
                .executes(ctx -> {
                    PetService.doctor(ctx.getSource(), false);
                    return 1;
                })
                .then(Commands.literal("fix")
                    .executes(ctx -> {
                        PetService.doctor(ctx.getSource(), true);
                        return 1;
                    })))
            .then(Commands.literal("reload")
                .requires(admin)
                .executes(ctx -> reload(ctx.getSource()))));
    }

    private static NameAndId target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return GameProfileArgument.getGameProfiles(ctx, "player").iterator().next();
    }

    private static int player(CommandSourceStack source, Consumer<ServerPlayer> action) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.translatable("cyurevive.command.player_only")
                .withStyle(style -> style.withColor(CyuRevive.TEXT_ERROR)));
            return 0;
        }
        action.accept(player);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        var server = source.getServer();
        source.sendSystemMessage(Component.translatable("cyurevive.reload.loading").withColor(CyuRevive.TEXT_DIM));
        CompletableFuture.supplyAsync(CyuRevive::readConfig, Util.ioPool()).whenComplete((replacement, failure) ->
            server.execute(() -> {
                if (!server.isRunning()) return;
                if (failure != null) {
                    reloadFailure(source, failure);
                    return;
                }
                try {
                    CyuRevive.applyConfig(replacement);
                } catch (RuntimeException invalidConfig) {
                    reloadFailure(source, invalidConfig);
                    return;
                }
                source.sendSuccess(() -> Component.translatable("cyurevive.reload.done",
                    replacement.bedCapacity, replacement.revivableTypes.size()).withColor(CyuRevive.TEXT_SUCCESS), true);
            }));
        return 1;
    }

    private static void reloadFailure(CommandSourceStack source, Throwable failure) {
        CyuRevive.LOGGER.error("宠物配置重载失败，原配置继续生效", failure);
        source.sendFailure(Component.translatable("cyurevive.reload.failed").withColor(CyuRevive.TEXT_ERROR));
    }
}
