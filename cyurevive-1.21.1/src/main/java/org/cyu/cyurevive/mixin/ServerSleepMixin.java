package org.cyu.cyurevive.mixin;

import net.minecraft.server.level.ServerLevel;
import org.cyu.cyurevive.PetService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerSleepMixin {
    @Inject(method = "wakeUpAllPlayers", at = @At("HEAD"))
    private void cyurevive$completedSleep(CallbackInfo callback) {
        PetService.onSleptThrough((ServerLevel) (Object) this);
    }
}
