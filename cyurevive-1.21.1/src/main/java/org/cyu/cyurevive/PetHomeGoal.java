package org.cyu.cyurevive;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public final class PetHomeGoal extends Goal {
    public enum Intent { CALL, NIGHT, RESUME }
    public enum Outcome { ARRIVED, WALKING, BUSY, BLOCKED }
    private enum RestAction { QUIET, WATCH, GRAZE }
    private sealed interface State permits Free, Returning, Resting, Released { }
    private record Free() implements State { }
    private record Returning(BedLoc bed, int slot, Direction facing, Vec3 target, Vec3 entrance, long expiresAt) implements State { }
    private record Resting(BedLoc bed, int slot, Direction facing, Vec3 target, RestAction action, long actionUntil) implements State { }
    private record Released(long retryAt) implements State { }
    private static final Free FREE = new Free();
    private final Animal pet;
    private State state = FREE;
    private long nextPathAt;
    private long nextCheckAt;
    private Vec3 gaze;
    private int lastInjury;

    public PetHomeGoal(Animal pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    public Outcome request(BedLoc bed, int slot, Intent intent) {
        if (!(pet.level() instanceof ServerLevel level) || !available()) return Outcome.BUSY;
        if (!level.dimension().equals(bed.dim())) return Outcome.BLOCKED;
        long now = level.getGameTime();
        if (intent == Intent.NIGHT && state instanceof Released released && now < released.retryAt()) return Outcome.BUSY;
        Vec3 target = PetResidences.position(level, bed, slot);
        if (target == null || !PetResidences.safe(level, pet, target)) return Outcome.BLOCKED;
        var anchor = level.getBlockState(bed.pos());
        PetBedBlock block = (PetBedBlock) anchor.getBlock();
        if (!block.accepts(pet) || !block.kind().fits(pet)) return Outcome.BLOCKED;
        Direction facing = anchor.getValue(PetBedBlock.FACING);
        if (intent == Intent.RESUME) {
            if (pet.distanceToSqr(target) > 0.36) return Outcome.BLOCKED;
            restAt(bed, slot, facing, target, now);
            return Outcome.ARRIVED;
        }
        if (intent == Intent.CALL) {
            land(bed, slot, facing, target, now);
            return Outcome.ARRIVED;
        }
        if (state instanceof Returning returning && returning.bed().equals(bed) && returning.slot() == slot) return Outcome.WALKING;
        if (state instanceof Resting resting && resting.bed().equals(bed) && resting.slot() == slot) return Outcome.ARRIVED;
        if (pet.distanceToSqr(target) <= 0.36) {
            restAt(bed, slot, facing, target, now);
            return Outcome.ARRIVED;
        }
        double approach = block.kind().layout().radius() + 1.0;
        Vec3 entrance = new Vec3(bed.pos().getX() + 0.5 + facing.getStepX() * approach,
            bed.pos().getY(), bed.pos().getZ() + 0.5 + facing.getStepZ() * approach);
        stopRestAction();
        pose(false);
        stopMovement();
        lastInjury = pet.getLastHurtByMobTimestamp();
        state = new Returning(bed, slot, facing, target, entrance, now + 240);
        nextPathAt = now;
        nextCheckAt = now;
        return Outcome.WALKING;
    }

    private boolean available() {
        return pet.isAlive() && !pet.isNoAi() && !pet.isVehicle() && !pet.isPassenger() && !pet.isLeashed()
            && !pet.isInWater() && !pet.isOnFire() && !pet.isInLove() && pet.hurtTime == 0;
    }

    @Override
    public boolean canUse() {
        if (!(state instanceof Returning || state instanceof Resting)) return false;
        if (!available() || pet.getLastHurtByMobTimestamp() != lastInjury
            || state instanceof Resting && pet instanceof TamableAnimal tame && !tame.isOrderedToSit()) {
            release();
            return false;
        }
        return true;
    }

    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        stopMovement();
        if (state instanceof Resting) pose(true);
    }
    @Override public void stop() { release(); }

    public void release() {
        if (state instanceof Returning || state instanceof Resting) {
            stopRestAction();
            stopMovement();
            pose(false);
        }
        state = new Released(pet.level().getGameTime() + 200);
        gaze = null;
    }

    public void suspend() {
        stopRestAction();
        pet.getNavigation().stop();
        state = FREE;
        gaze = null;
    }

    @Override
    public void tick() {
        long now = pet.level().getGameTime();
        switch (state) {
            case Returning returning -> walk(returning, now);
            case Resting resting -> rest(resting, now);
            case Free ignored -> { }
            case Released ignored -> { }
        }
    }

    private boolean residenceValid(BedLoc bed, int slot, Direction facing, long now) {
        if (now < nextCheckAt) return true;
        nextCheckAt = now + 20;
        ServerLevel level = (ServerLevel) pet.level();
        PetWorldData data = PetWorldData.get(level.getServer());
        HomeRecord home = data.homes.get(pet.getUUID());
        if (home == null || !home.bed().equals(bed) || home.slot() != slot
            || !home.owner().equals(PetService.petOwnerId(pet)) || !level.dimension().equals(bed.dim())
            || data.downed.containsKey(pet.getUUID()) || data.doomed.contains(pet.getUUID())
            || home.availability() != ResidenceAvailability.PRESENT || !level.hasChunkAt(bed.pos())) return false;
        var anchor = level.getBlockState(bed.pos());
        return anchor.getBlock() instanceof PetBedBlock block && block.part(anchor) == PetBedBlock.CENTER
            && anchor.getValue(PetBedBlock.FACING) == facing;
    }

    private void walk(Returning returning, long now) {
        if (now >= returning.expiresAt() || !residenceValid(returning.bed(), returning.slot(), returning.facing(), now)) {
            release();
            return;
        }
        if (pet.distanceToSqr(returning.entrance()) <= 1.0
            && PetResidences.safeApproach((ServerLevel) pet.level(), pet, returning.target())) {
            land(returning.bed(), returning.slot(), returning.facing(), returning.target(), now);
            return;
        }
        if (now < nextPathAt || !pet.getNavigation().isDone()) return;
        nextPathAt = now + 40;
        Vec3 entrance = returning.entrance();
        if (!pet.getNavigation().moveTo(entrance.x, entrance.y, entrance.z, 0.8)) release();
    }

    private void land(BedLoc bed, int slot, Direction facing, Vec3 target, long now) {
        stopRestAction();
        stopMovement();
        pet.teleportTo(target.x, target.y, target.z);
        pet.setDeltaMovement(Vec3.ZERO);
        float yaw = facing.toYRot();
        pet.setYRot(yaw);
        pet.setYHeadRot(yaw);
        pet.yBodyRot = yaw;
        restAt(bed, slot, facing, target, now);
    }

    private void restAt(BedLoc bed, int slot, Direction facing, Vec3 target, long now) {
        stopMovement();
        pose(true);
        lastInjury = pet.getLastHurtByMobTimestamp();
        nextCheckAt = now;
        gaze = null;
        state = new Resting(bed, slot, facing, target, RestAction.QUIET, now + 60);
    }

    private void rest(Resting resting, long now) {
        if (!residenceValid(resting.bed(), resting.slot(), resting.facing(), now) || pet.distanceToSqr(resting.target()) > 1.0) {
            release();
            return;
        }
        if (now >= resting.actionUntil()) {
            stopRestAction();
            RestAction action = nextAction(resting.action());
            long duration = action == RestAction.QUIET ? 100 + pet.getRandom().nextInt(100) : 50;
            state = new Resting(resting.bed(), resting.slot(), resting.facing(), resting.target(), action, now + duration);
            gaze = action == RestAction.WATCH ? watchPoint(resting) : null;
            if (action == RestAction.GRAZE && pet instanceof AbstractHorse horse) horse.setEating(true);
        }
        if (gaze != null) pet.getLookControl().setLookAt(gaze.x, gaze.y, gaze.z, 3, 2);
    }

    private RestAction nextAction(RestAction previous) {
        if (previous != RestAction.QUIET) return RestAction.QUIET;
        return pet instanceof AbstractHorse && pet.getRandom().nextInt(3) == 0 ? RestAction.GRAZE : RestAction.WATCH;
    }

    private Vec3 watchPoint(Resting resting) {
        Direction facing = resting.facing();
        var owner = pet.level().getServer().getPlayerList().getPlayer(PetService.petOwnerId(pet));
        if (owner != null && owner.level() == pet.level() && owner.distanceToSqr(pet) <= 36
            && (owner.getX() - pet.getX()) * facing.getStepX() + (owner.getZ() - pet.getZ()) * facing.getStepZ() > 0) {
            return owner.getEyePosition();
        }
        double sideways = (pet.getRandom().nextDouble() - 0.5) * 2;
        return new Vec3(pet.getX() + facing.getStepX() * 4 + facing.getStepZ() * sideways, pet.getEyeY() - 0.15,
            pet.getZ() + facing.getStepZ() * 4 - facing.getStepX() * sideways);
    }

    private void stopRestAction() {
        if (state instanceof Resting resting && resting.action() == RestAction.GRAZE && pet instanceof AbstractHorse horse) horse.setEating(false);
    }

    private void stopMovement() {
        pet.getNavigation().stop();
        pet.getMoveControl().setWantedPosition(pet.getX(), pet.getY(), pet.getZ(), 0);
        pet.setSpeed(0);
        pet.setZza(0);
    }

    private void pose(boolean resting) {
        if (!(pet instanceof TamableAnimal tame)) return;
        tame.setOrderedToSit(resting);
        tame.setInSittingPose(resting);
    }
}
