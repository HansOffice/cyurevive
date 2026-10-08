package org.cyu.cyurevive.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.cyu.cyurevive.CyuRevive;
import org.cyu.cyurevive.PetMessages;

import java.util.List;

import static org.cyu.cyurevive.command.CommandSpec.*;

public final class HelpRenderer {
    private static final int BORDER = CyuRevive.TEXT_BORDER;
    private static final int BRIGHT = CyuRevive.TEXT_BRIGHT;
    private static final int DIM = CyuRevive.TEXT_DIM;
    private static final int MUTED = CyuRevive.TEXT_MUTED;
    private static final int GRADIENT_FROM = 0x58C7FF;
    private static final int GRADIENT_TO = 0x7DE2B8;
    private static final String RULE = "┈".repeat(34);
    private static final List<String> HINTS = List.of(
        "cyurevive.help.usage.bind",
        "cyurevive.help.usage.recall",
        "cyurevive.help.usage.info");
    private static final CommandSpec[] COMMANDS = CommandSpec.values();
    private enum Page { PLAYER, ADMIN }

    private HelpRenderer() { }

    public static int send(CommandSourceStack source) {
        return render(source, source.getEntity() instanceof ServerPlayer ? Page.PLAYER : Page.ADMIN);
    }

    public static int sendAdmin(CommandSourceStack source) {
        return render(source, Page.ADMIN);
    }

    private static int render(CommandSourceStack source, Page page) {
        if (page == Page.ADMIN && !ADMIN_HELP.available(source)) {
            source.sendFailure(PetMessages.text("cyurevive.command.no_permission").withStyle(style -> style.withColor(CyuRevive.TEXT_ERROR)));
            return 0;
        }
        Environment environment = Environment.of(source);
        source.sendSystemMessage(border());
        source.sendSystemMessage(title());
        source.sendSystemMessage(PetMessages.text(page == Page.PLAYER ? "cyurevive.help.page.player" : "cyurevive.help.page.admin",
            environment.caption()).withStyle(style -> style.withColor(BRIGHT)));
        switch (page) {
            case PLAYER -> playerPage(source, environment);
            case ADMIN -> adminPage(source, environment);
        }
        source.sendSystemMessage(border());
        return 1;
    }

    private static void playerPage(CommandSourceStack source, Environment environment) {
        for (String key : HINTS) {
            source.sendSystemMessage(Component.literal("· ").withStyle(style -> style.withColor(MUTED))
                .append(PetMessages.text(key).withStyle(style -> style.withColor(DIM))));
        }
        entries(source, Section.PLAYER, environment);
        if (!CyuRevive.config.commandRecall) {
            source.sendSystemMessage(PetMessages.text("cyurevive.help.recall_disabled").withStyle(style -> style.withColor(DIM)));
        }
        if (ADMIN_HELP.available(source)) entry(source, ADMIN_HELP);
    }

    private static void adminPage(CommandSourceStack source, Environment environment) {
        source.sendSystemMessage(PetMessages.text(environment == Environment.SINGLEPLAYER
            ? "cyurevive.help.section.pets" : "cyurevive.help.section.players").withStyle(style -> style.withColor(BRIGHT)));
        entries(source, Section.PETS, environment);
        if (environment == Environment.MULTIPLAYER) {
            source.sendSystemMessage(PetMessages.text("cyurevive.help.self_target_hint").withStyle(style -> style.withColor(DIM)));
        }
        source.sendSystemMessage(PetMessages.text(environment == Environment.SINGLEPLAYER
            ? "cyurevive.help.section.world" : "cyurevive.help.section.server").withStyle(style -> style.withColor(BRIGHT)));
        entries(source, Section.WORLD, environment);
        if (source.getEntity() instanceof ServerPlayer) entry(source, HELP);
    }

    private static void entries(CommandSourceStack source, Section section, Environment environment) {
        for (CommandSpec command : COMMANDS) {
            if (command.shownIn(section, environment) && command.available(source)) entry(source, command);
        }
    }

    private static void entry(CommandSourceStack source, CommandSpec command) {
        MutableComponent line = Component.literal("› ").withStyle(style -> style.withColor(BRIGHT))
            .append(command.usage(source).withStyle(style -> style.withColor(BRIGHT)))
            .append(Component.literal(" "))
            .append(command.description().withStyle(style -> style.withColor(DIM)));
        if (source.getEntity() instanceof ServerPlayer) {
            line.withStyle(style -> style
                .withClickEvent(new ClickEvent.SuggestCommand(command.suggestion()))
                .withHoverEvent(new HoverEvent.ShowText(
                    PetMessages.text("cyurevive.help.hover", command.suggestion()).withStyle(hoverStyle -> hoverStyle.withColor(DIM)))));
        }
        source.sendSystemMessage(line);
    }

    private static MutableComponent border() {
        return Component.literal(RULE).withStyle(style -> style.withColor(BORDER).withStrikethrough(true));
    }

    private static MutableComponent title() {
        return gradient("CyuRevive")
            .append(Component.literal(" · ").withStyle(style -> style.withColor(DIM)))
            .append(PetMessages.text("cyurevive.help.brand").withStyle(style -> style.withColor(BRIGHT)));
    }

    private static MutableComponent gradient(String text) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            double t = text.length() == 1 ? 0 : (double) i / (text.length() - 1);
            out.append(Component.literal(String.valueOf(text.charAt(i)))
                .withStyle(Style.EMPTY.withColor(mix(GRADIENT_FROM, GRADIENT_TO, t)).withBold(true)));
        }
        return out;
    }

    private static int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
