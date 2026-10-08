package org.cyu.cyurevive.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetMessages;
import org.cyu.cyurevive.PetService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.cyu.cyurevive.command.CommandSpec.*;

public final class PetCommands {
    private static final SimpleCommandExceptionType SINGLE_TARGET = new SimpleCommandExceptionType(
        PetMessages.text("cyurevive.command.single_target"));
    private static final SimpleCommandExceptionType TARGET_REQUIRED = new SimpleCommandExceptionType(
        PetMessages.text("cyurevive.command.target_required"));
    private static final SimpleCommandExceptionType NO_PERMISSION = new SimpleCommandExceptionType(
        PetMessages.text("cyurevive.command.no_permission"));

    private record Owner(UUID id, String name) { }

    private PetCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(CyuRevive.MOD_ID)
            .executes(ctx -> HelpRenderer.send(ctx.getSource()))
            .then(Commands.literal(HELP.literal())
                .executes(ctx -> HelpRenderer.send(ctx.getSource()))
                .then(Commands.literal(ADMIN_HELP.literal())
                    .requires(ADMIN_HELP::available)
                    .executes(ctx -> HelpRenderer.sendAdmin(ctx.getSource()))))
            .then(Commands.literal(LIST_SELF.literal())
                .requires(source -> LIST_SELF.available(source) || LIST_PLAYER.available(source))
                .executes(ctx -> player(ctx.getSource(), LIST_SELF,
                    actor -> PetService.sendPetList(ctx.getSource(), actor.getUUID(), null)))
                .then(Commands.argument(Parameter.PLAYER.id(), GameProfileArgument.gameProfile())
                    .requires(LIST_PLAYER::available)
                    .executes(ctx -> executeTarget(ctx,
                        (source, owner) -> PetService.sendPetList(source, owner.id(), owner.name())))))
            .then(Commands.literal(UNBIND_SELF.literal())
                .requires(source -> UNBIND_SELF.available(source) || UNBIND_PLAYER.available(source))
                .then(Commands.argument(Parameter.INDEX.id(), IntegerArgumentType.integer(1))
                    .requires(UNBIND_SELF::available)
                    .executes(ctx -> player(ctx.getSource(), UNBIND_SELF,
                        actor -> PetService.unbindPet(ctx.getSource(), actor.getUUID(), actor.getName().getString(),
                            IntegerArgumentType.getInteger(ctx, Parameter.INDEX.id()), true))))
                .then(Commands.argument(Parameter.PLAYER.id(), GameProfileArgument.gameProfile())
                    .requires(UNBIND_PLAYER::available)
                    .then(Commands.argument(Parameter.INDEX.id(), IntegerArgumentType.integer(1))
                        .executes(ctx -> executeTarget(ctx,
                            (source, owner) -> PetService.unbindPet(source, owner.id(), owner.name(),
                                IntegerArgumentType.getInteger(ctx, Parameter.INDEX.id()), false))))))
            .then(Commands.literal(HOME.literal())
                .requires(HOME::available)
                .then(Commands.argument(Parameter.NAME.id(), StringArgumentType.greedyString())
                    .executes(ctx -> player(ctx.getSource(), HOME,
                        actor -> PetService.nameHome(actor, StringArgumentType.getString(ctx, Parameter.NAME.id()))))))
            .then(Commands.literal(RECALL.literal())
                .requires(RECALL::available)
                .executes(ctx -> player(ctx.getSource(), RECALL, PetService::recallCommand)))
            .then(selfAndTarget(REVIVE_SELF, REVIVE_PLAYER,
                (source, owner) -> PetService.reviveAll(source, owner.id(), owner.name())))
            .then(targeted(FORBID,
                (source, owner) -> PetService.forbidPlayer(source, owner.id(), owner.name())))
            .then(targeted(UNFORBID,
                (source, owner) -> PetService.unforbidPlayer(source, owner.id(), owner.name())))
            .then(selfAndTarget(PURGE_SELF, PURGE_PLAYER,
                (source, owner) -> PetService.purgePlayer(source, owner.id(), owner.name())))
            .then(Commands.literal(STATUS.literal())
                .requires(STATUS::available)
                .executes(ctx -> {
                    PetService.sendStatus(ctx.getSource());
                    return 1;
                }))
            .then(Commands.literal(DOCTOR.literal())
                .requires(DOCTOR::available)
                .executes(ctx -> {
                    PetService.doctor(ctx.getSource(), false);
                    return 1;
                })
                .then(Commands.literal(Parameter.FIX.id())
                    .executes(ctx -> {
                        PetService.doctor(ctx.getSource(), true);
                        return 1;
                    })))
            .then(Commands.literal(RELOAD.literal())
                .requires(RELOAD::available)
                .executes(ctx -> reload(ctx.getSource()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> targeted(CommandSpec command,
            BiConsumer<CommandSourceStack, Owner> action) {
        return Commands.literal(command.literal())
            .requires(command::available)
            .then(Commands.argument(Parameter.PLAYER.id(), GameProfileArgument.gameProfile())
                .requires(command::available)
                .executes(ctx -> executeTarget(ctx, action)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> selfAndTarget(CommandSpec self, CommandSpec target,
            BiConsumer<CommandSourceStack, Owner> action) {
        return targeted(target, action)
            .executes(ctx -> player(ctx.getSource(), self,
                actor -> action.accept(ctx.getSource(), new Owner(actor.getUUID(), actor.getName().getString()))));
    }

    private static int executeTarget(CommandContext<CommandSourceStack> context,
            BiConsumer<CommandSourceStack, Owner> action) throws CommandSyntaxException {
        var profiles = GameProfileArgument.getGameProfiles(context, Parameter.PLAYER.id());
        if (profiles.size() != 1) throw SINGLE_TARGET.create();
        var profile = profiles.iterator().next();
        action.accept(context.getSource(), new Owner(profile.id(), profile.name()));
        return 1;
    }

    private static int player(CommandSourceStack source, CommandSpec command, Consumer<ServerPlayer> action)
            throws CommandSyntaxException {
        if (!(source.getEntity() instanceof ServerPlayer actor)) throw TARGET_REQUIRED.create();
        if (!command.available(source)) throw NO_PERMISSION.create();
        action.accept(actor);
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        var server = source.getServer();
        source.sendSystemMessage(PetMessages.text("cyurevive.reload.loading")
            .withStyle(style -> style.withColor(CyuRevive.TEXT_DIM)));
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
                for (ServerPlayer player : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(player);
                source.sendSuccess(() -> PetMessages.text("cyurevive.reload.done",
                    replacement.bedCapacity, replacement.revivableTypes.size())
                    .withStyle(style -> style.withColor(CyuRevive.TEXT_SUCCESS)), true);
            }));
        return 1;
    }

    private static void reloadFailure(CommandSourceStack source, Throwable failure) {
        CyuRevive.LOGGER.error("宠物配置重载失败，原配置继续生效", failure);
        source.sendFailure(PetMessages.text("cyurevive.reload.failed")
            .withStyle(style -> style.withColor(CyuRevive.TEXT_ERROR)));
    }
}
