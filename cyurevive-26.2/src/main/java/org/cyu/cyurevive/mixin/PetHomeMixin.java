package org.cyu.cyurevive.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.animal.Animal;
import org.cyu.cyurevive.PetHomeAccess;
import org.cyu.cyurevive.PetHomeGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Mob.class)
public abstract class PetHomeMixin implements PetHomeAccess {
    @Shadow @Final protected GoalSelector goalSelector;
    @Unique private PetHomeGoal cyurevive$homeGoal;

    @Override
    public PetHomeGoal cyurevive$homeGoal() {
        if (cyurevive$homeGoal == null) {
            cyurevive$homeGoal = new PetHomeGoal((Animal) (Object) this);
            goalSelector.addGoal(0, cyurevive$homeGoal);
        }
        return cyurevive$homeGoal;
    }

    @Override
    public void cyurevive$releaseHome() {
        if (cyurevive$homeGoal != null) cyurevive$homeGoal.release();
    }
    @Override
    public void cyurevive$suspendHome() {
        if (cyurevive$homeGoal != null) cyurevive$homeGoal.suspend();
    }
}
