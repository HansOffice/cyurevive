package org.cyu.cyurevive.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.cyu.cyurevive.PetService;
import org.cyu.cyurevive.PetCare;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class PetDeathMixin {
    @Unique
    private PetCare.DeathOutcome cyurevive$deathOutcome = PetCare.DeathOutcome.ORDINARY;

    @Inject(method = "die", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/damagesource/CombatTracker;recheckStatus()V", shift = At.Shift.AFTER))
    private void cyurevive$captureConfirmedDeath(DamageSource source, CallbackInfo callback) {
        cyurevive$deathOutcome = PetService.captureDeath((LivingEntity) (Object) this);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), cancellable = true)
    private void cyurevive$preserveReturningPet(ServerLevel level, DamageSource source, CallbackInfo callback) {
        if (cyurevive$deathOutcome == PetCare.DeathOutcome.RECORDED) callback.cancel();
    }
}
