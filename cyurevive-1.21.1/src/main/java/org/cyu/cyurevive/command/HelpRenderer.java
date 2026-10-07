package org.cyu.cyurevive.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.cyu.cyurevive.CyuRevive;

import java.util.List;

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

    private record Entry(String command, String descKey, boolean admin) {
    }

    private static final List<Entry> ENTRIES = List.of(
        new Entry("/cyurevive list", "cyurevive.help.list", false),
        new Entry("/cyurevive home <名称>", "cyurevive.help.home", false),
        new Entry("/cyurevive unbind <序号>", "cyurevive.help.unbind", false),
        new Entry("/cyurevive recall", "cyurevive.help.recall", false),
        new Entry("/cyurevive list <玩家>", "cyurevive.help.admin.list", true),
        new Entry("/cyurevive unbind <玩家> <序号>", "cyurevive.help.admin.unbind", true),
        new Entry("/cyurevive revive <玩家>", "cyurevive.help.admin.revive", true),
        new Entry("/cyurevive forbid <玩家>", "cyurevive.help.admin.forbid", true),
        new Entry("/cyurevive unforbid <玩家>", "cyurevive.help.admin.unforbid", true),
        new Entry("/cyurevive purge <玩家>", "cyurevive.help.admin.purge", true),
        new Entry("/cyurevive status", "cyurevive.help.status", true),
        new Entry("/cyurevive doctor [fix]", "cyurevive.help.doctor", true),
        new Entry("/cyurevive reload", "cyurevive.help.reload", true));

    private HelpRenderer() {
    }

    public static int send(CommandSourceStack source) {
        boolean admin = source.hasPermission(PetCommands.OP_PERMISSION);
        source.sendSystemMessage(border());
        source.sendSystemMessage(title());
        source.sendSystemMessage(Component.translatable("cyurevive.help.usage-header").withColor(BRIGHT));
        for (String key : HINTS) {
            source.sendSystemMessage(Component.literal("· ").withColor(MUTED)
                .append(Component.translatable(key).withColor(DIM)));
        }
        for (Entry entry : ENTRIES) {
            if (entry.admin() && !admin) continue;
            MutableComponent line = Component.literal("› " + entry.command() + " ")
                .withColor(BRIGHT)
                .append(Component.translatable(entry.descKey()).withColor(DIM));
            if (source.getEntity() instanceof ServerPlayer) {
                line.withStyle(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.command()))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.translatable("cyurevive.help.hover", entry.command()).withColor(DIM))));
            }
            source.sendSystemMessage(line);
        }
        source.sendSystemMessage(border());
        return 1;
    }

    private static MutableComponent border() {
        return Component.literal(RULE).withStyle(style -> style.withColor(BORDER).withStrikethrough(true));
    }

    private static MutableComponent title() {
        return gradient("CyuRevive")
            .append(Component.literal(" · ").withColor(DIM))
            .append(Component.translatable("cyurevive.help.brand").withColor(BRIGHT));
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
