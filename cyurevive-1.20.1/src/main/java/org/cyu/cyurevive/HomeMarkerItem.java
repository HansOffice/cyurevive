package org.cyu.cyurevive;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import java.util.List;

public final class HomeMarkerItem extends Item {
    private final Component hint;

    public HomeMarkerItem(Properties properties) {
        super(properties.stacksTo(1));
        hint = Component.translatable("cyurevive.item.home_marker.hint")
            .withStyle(textStyle -> textStyle.withColor(CyuRevive.TEXT_MUTED).withItalic(false));
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(hint);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(player.getItemInHand(hand));
        if (player instanceof ServerPlayer owner) {
            PetSelection selection = PetWorldData.get(owner.level().getServer()).selection;
            if (player.isShiftKeyDown()) selection.cancel(owner); else selection.guide(owner);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }
}
