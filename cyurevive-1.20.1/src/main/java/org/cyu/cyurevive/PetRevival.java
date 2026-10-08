package org.cyu.cyurevive;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class PetRevival {
    public enum Destination { HOME, OWNER }
    public enum Origin { MANUAL, SLEEP, ADMIN }
    public enum Charge { REQUIRED, ALREADY_PAID }
    public enum Phase { WAITING, INSERTING, UNREADABLE }
    public enum Result {
        COMPLETED, RETRY, DORMANT, INVALID
    }

    public sealed interface Snapshot permits Ready, Invalid { }
    public record Ready(Entity entity) implements Snapshot { }
    public record Invalid(String reason) implements Snapshot, Plan { }
    public enum Landing { HOME, OWNER_RECALL, OWNER_FALLBACK }
    public enum Delay { OWNER_OFFLINE, CHUNK_UNLOADED, SPACE_BLOCKED }
    public enum Blockage { NONE, OWNER_OFFLINE, CHUNK_UNLOADED, SPACE_BLOCKED, ENTITY_PRESENT, INSERTION_REFUSED }
    public sealed interface Plan permits Prepared, Delayed, Invalid { }
    public record Prepared(Animal pet, ServerLevel level, Vec3 position, Landing landing) implements Plan { }
    public record Delayed(Delay reason) implements Plan { }

    private PetRevival() { }

    public static void restoreVitals(Animal pet) {
        pet.setNoAi(false);
        pet.setInvulnerable(false);
        pet.setHealth(net.minecraft.util.Mth.clamp((float) (pet.getMaxHealth() * CyuRevive.config.respawnHealthPercent), 1f, pet.getMaxHealth()));
        pet.invulnerableTime = 0;
        if (pet instanceof net.minecraft.world.entity.TamableAnimal tame) tame.setOrderedToSit(true);
    }

    public static UUID ownerOf(Animal pet) {
        return pet instanceof OwnableEntity own ? own.getOwnerUUID() : null;
    }

    public static Plan prepare(PendingRevival pending, MinecraftServer server, Destination destination) {
        var owner = server.getPlayerList().getPlayer(pending.owner());
        if (owner == null) return new Delayed(Delay.OWNER_OFFLINE);
        ServerLevel home = server.getLevel(pending.bed().dim());
        if (destination == Destination.HOME && pending.availability() == ResidenceAvailability.PRESENT && home != null) {
            if (!home.hasChunkAt(pending.bed().pos())) return new Delayed(Delay.CHUNK_UNLOADED);
            var state = home.getBlockState(pending.bed().pos());
            if (state.getBlock() instanceof PetBedBlock block && !block.loaded(home, pending.bed().pos(), state)) {
                return new Delayed(Delay.CHUNK_UNLOADED);
            }
        }
        Landing landing = destination == Destination.OWNER ? Landing.OWNER_RECALL
            : pending.availability() == ResidenceAvailability.MISSING || home == null
            || !(home.getBlockState(pending.bed().pos()).getBlock() instanceof PetBedBlock)
            ? Landing.OWNER_FALLBACK : Landing.HOME;
        ServerLevel level = landing == Landing.HOME ? home : (ServerLevel) owner.level();
        Snapshot snapshot = read(pending, level);
        if (snapshot instanceof Invalid invalid) return invalid;
        if (snapshot instanceof Ready ready) return prepareEntity(ready.entity(), pending, level, owner.blockPosition(), landing);
        throw new IllegalStateException("Unsupported pet snapshot: " + snapshot);
    }

    private static Plan prepareEntity(Entity entity, PendingRevival pending, ServerLevel level,
                                      net.minecraft.core.BlockPos owner, Landing landing) {
        if (!(entity instanceof Animal pet) || !pending.owner().equals(ownerOf(pet))) {
            return new Invalid("Pet owner or entity type does not match");
        }
        Vec3 position = switch (landing) {
            case OWNER_RECALL, OWNER_FALLBACK -> PetResidences.ownerPosition(level, pet, owner);
            case HOME -> homePosition(pending, level, pet);
        };
        return position != null && PetResidences.safe(level, pet, position)
            ? new Prepared(pet, level, position, landing) : new Delayed(Delay.SPACE_BLOCKED);
    }

    private static Vec3 homePosition(PendingRevival pending, ServerLevel level, Animal pet) {
        PetBedBlock block = (PetBedBlock) level.getBlockState(pending.bed().pos()).getBlock();
        return block.accepts(pet) && block.kind().fits(pet)
            ? PetResidences.position(level, pending.bed(), pending.slot()) : null;
    }

    public static Snapshot read(PendingRevival pending, ServerLevel level) {
        try {
            Entity entity = EntityType.create(pending.snapshot().copy(), level).orElse(null);
            return entity == null ? new Invalid("Entity type could not be decoded") : new Ready(entity);
        } catch (RuntimeException failure) {
            return new Invalid(failure.toString());
        }
    }
}
