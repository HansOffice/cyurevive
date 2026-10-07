package org.cyu.cyurevive;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

public final class PetCare {
    public enum DeathOutcome { ORDINARY, RECORDED }
    public enum DamagePolicy { ALLOW, BLOCK }
    private static final int FOCUS_RANGE = 6;

    private PetCare() { }

    public static Optional<ItemStack> rescueFood(Player player, Animal pet, InteractionHand hand) {
        ItemStack selected = player.getItemInHand(hand);
        if (acceptsFood(pet, selected)) return Optional.of(selected);
        ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
        return hand == InteractionHand.MAIN_HAND && acceptsFood(pet, offhand) ? Optional.of(offhand) : Optional.empty();
    }

    private static boolean acceptsFood(Animal pet, ItemStack food) {
        return !food.isEmpty() && (CyuRevive.config.downedItemSet.contains(food.getItem()) || pet.isFood(food));
    }

    public static DamagePolicy damagePolicy(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Animal pet) || entity.level().isClientSide() || PetRevival.ownerOf(pet) == null
            || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return DamagePolicy.ALLOW;
        MinecraftServer server = entity.level().getServer();
        if (server == null) return DamagePolicy.ALLOW;
        Long deadline = PetWorldData.get(server).protectedUntil.get(entity.getUUID());
        return deadline != null && server.overworld().getGameTime() < deadline ? DamagePolicy.BLOCK : DamagePolicy.ALLOW;
    }

    public static void protect(MinecraftServer server, PetWorldData data, Animal pet) {
        int seconds = CyuRevive.config.respawnProtectionSeconds;
        if (seconds > 0) data.protect(pet.getUUID(), server.overworld().getGameTime() + seconds * 20L);
    }

    public static void showRescueStatus(ServerPlayer owner, Animal pet, DownedPet injury, long now) {
        if (owner == null || owner.level() != pet.level() || owner.distanceToSqr(pet) > FOCUS_RANGE * FOCUS_RANGE
            || !owner.hasLineOfSight(pet)) return;
        var eyes = owner.getEyePosition();
        var direction = owner.getViewVector(1).scale(FOCUS_RANGE);
        var end = eyes.add(direction);
        var volume = owner.getBoundingBox().expandTowards(direction).inflate(1);
        double reach = owner.pick(FOCUS_RANGE, 1, false).getLocation().distanceToSqr(eyes);
        var hit = ProjectileUtil.getEntityHitResult(owner, eyes, end, volume,
            target -> !target.isSpectator() && target.isPickable(), reach);
        if (hit == null || hit.getEntity() != pet) return;
        long remaining = PetProgress.secondsUntil(injury.deadline(), now);
        owner.sendSystemMessage(Component.translatable(remaining > 0 ? "cyurevive.downed.focus" : "cyurevive.downed.focus_expired",
            pet.getDisplayName(), remaining).withColor(CyuRevive.TEXT_AMBER), true);
    }
}
