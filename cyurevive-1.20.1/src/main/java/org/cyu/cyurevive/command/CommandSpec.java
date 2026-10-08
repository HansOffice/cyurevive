package org.cyu.cyurevive.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetMessages;

import java.util.List;

enum CommandSpec {
    HELP("help", Scope.PUBLIC, "overview"),
    ADMIN_HELP(List.of("help", "admin"), Scope.ADMIN_NAVIGATION, "admin"),
    LIST_SELF("list", Scope.PLAYER, "list"),
    HOME("home", Scope.PLAYER, "home", Parameter.NAME),
    UNBIND_SELF("unbind", Scope.PLAYER, "unbind", Parameter.INDEX),
    RECALL("recall", Scope.PLAYER, "recall") {
        @Override
        boolean available(CommandSourceStack source) {
            return CyuRevive.config.commandRecall && super.available(source);
        }
    },
    REVIVE_SELF("revive", Scope.SELF_ADMIN, "admin.revive_self"),
    PURGE_SELF("purge", Scope.SELF_ADMIN, "admin.purge_self"),
    LIST_PLAYER("list", Scope.TARGET_ADMIN, "admin.list", Parameter.PLAYER),
    UNBIND_PLAYER("unbind", Scope.TARGET_ADMIN, "admin.unbind", Parameter.PLAYER, Parameter.INDEX),
    REVIVE_PLAYER("revive", Scope.TARGET_ADMIN, "admin.revive", Parameter.OPTIONAL_PLAYER),
    FORBID("forbid", Scope.TARGET_ADMIN, "admin.forbid", Parameter.PLAYER),
    UNFORBID("unforbid", Scope.TARGET_ADMIN, "admin.unforbid", Parameter.PLAYER),
    PURGE_PLAYER("purge", Scope.TARGET_ADMIN, "admin.purge", Parameter.OPTIONAL_PLAYER),
    STATUS("status", Scope.WORLD_ADMIN, "status"),
    DOCTOR("doctor", Scope.WORLD_ADMIN, "doctor", Parameter.FIX),
    RELOAD("reload", Scope.WORLD_ADMIN, "reload");

    enum Section { PLAYER, PETS, WORLD, NAVIGATION }
    enum Environment {
        SINGLEPLAYER("singleplayer"), MULTIPLAYER("multiplayer"), CONSOLE("console");

        private final String key;

        Environment(String key) { this.key = "cyurevive.help.environment." + key; }

        MutableComponent caption() { return PetMessages.text(key); }

        static Environment of(CommandSourceStack source) {
            if (!(source.getEntity() instanceof ServerPlayer)) return CONSOLE;
            var server = source.getServer();
            return server.isDedicatedServer() || server.isPublished() ? MULTIPLAYER : SINGLEPLAYER;
        }
    }

    enum Parameter {
        NAME("name", "name"), INDEX("index", "index"), PLAYER("player", "player"),
        OPTIONAL_PLAYER("player", "optional_player"), FIX("fix", "fix");

        private final String id;
        private final String key;

        Parameter(String id, String key) {
            this.id = id;
            this.key = "cyurevive.help.argument." + key;
        }

        String id() { return id; }

        MutableComponent caption(CommandSourceStack source) {
            return PetMessages.text(this == OPTIONAL_PLAYER && !(source.getEntity() instanceof ServerPlayer) ? PLAYER.key : key);
        }
    }

    private enum Scope {
        PUBLIC, PLAYER, SELF_ADMIN, TARGET_ADMIN, WORLD_ADMIN, ADMIN_NAVIGATION;

        Section section() {
            return switch (this) {
                case PUBLIC, ADMIN_NAVIGATION -> Section.NAVIGATION;
                case PLAYER -> Section.PLAYER;
                case SELF_ADMIN, TARGET_ADMIN -> Section.PETS;
                case WORLD_ADMIN -> Section.WORLD;
            };
        }

        boolean visible(Environment environment) {
            return switch (this) {
                case SELF_ADMIN -> environment == Environment.SINGLEPLAYER;
                case TARGET_ADMIN -> environment != Environment.SINGLEPLAYER;
                case PUBLIC, PLAYER, WORLD_ADMIN, ADMIN_NAVIGATION -> true;
            };
        }
    }

    private final List<String> path;
    private final Scope scope;
    private final String descriptionKey;
    private final List<Parameter> parameters;
    private final String prefix;

    CommandSpec(String literal, Scope scope, String description, Parameter... parameters) {
        this(List.of(literal), scope, description, parameters);
    }

    CommandSpec(List<String> path, Scope scope, String description, Parameter... parameters) {
        this.path = List.copyOf(path);
        this.scope = scope;
        this.descriptionKey = "cyurevive.help." + description;
        this.parameters = List.of(parameters);
        this.prefix = "/" + CyuRevive.MOD_ID + " " + String.join(" ", path);
    }

    String literal() { return path.get(path.size() - 1); }
    String suggestion() { return prefix + (parameters.isEmpty() ? "" : " "); }
    MutableComponent description() { return PetMessages.text(descriptionKey); }

    MutableComponent usage(CommandSourceStack source) {
        MutableComponent usage = Component.literal(prefix);
        for (Parameter parameter : parameters) usage.append(" ").append(parameter.caption(source));
        return usage;
    }

    boolean available(CommandSourceStack source) {
        return switch (scope) {
            case PUBLIC -> true;
            case PLAYER -> source.getEntity() instanceof ServerPlayer;
            case SELF_ADMIN -> source.getEntity() instanceof ServerPlayer && hasManagementPermission(source);
            case TARGET_ADMIN, WORLD_ADMIN, ADMIN_NAVIGATION -> hasManagementPermission(source);
        };
    }

    boolean shownIn(Section section, Environment environment) {
        return scope.section() == section && scope.visible(environment);
    }

    private static boolean hasManagementPermission(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
