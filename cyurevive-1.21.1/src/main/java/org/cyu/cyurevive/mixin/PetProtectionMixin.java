package org.cyu.cyurevive.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.cyu.cyurevive.PetCare;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PetProtectionMixin {
    @Inject(method = "isInvulnerableTo", at = @At("RETURN"), cancellable = true)
    private void cyurevive$protectRevivedPet(DamageSource source, CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ()
            && PetCare.damagePolicy((LivingEntity) (Object) this, source) == PetCare.DamagePolicy.BLOCK) {
            callback.setReturnValue(true);
        }
    }
}
