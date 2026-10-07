package org.cyu.cyurevive;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class PetProgress {
    public enum Timer { COOLDOWN, CEREMONY }
    public enum Wait { QUEUED, AUTOMATIC_DISABLED, OWNER_OFFLINE, CHUNK_UNLOADED, CHUNK_UNLOADED_RECALL_AVAILABLE, SPACE_BLOCKED,
                       ENTITY_PRESENT, INSERTION_REFUSED, INVALID_DATA }
    public sealed interface Status permits Timed, Waiting {
        PetRevival.Destination destination();
    }
    public record Timed(Timer timer, long seconds, PetRevival.Destination destination) implements Status { }
    public record Waiting(Wait reason, PetRevival.Destination destination) implements Status { }

    private PetProgress() { }

    public static long secondsUntil(long deadline, long now) {
        return deadline <= now ? 0 : (deadline - now + 19) / 20;
    }

    public static Status status(MinecraftServer server, PendingRevival pending, Arrival arrival, long now) {
        PetRevival.Destination destination = destination(server, pending, arrival);
        if (pending.phase() == PetRevival.Phase.UNREADABLE) return new Waiting(Wait.INVALID_DATA, destination);
        if (arrival == null && pending.dueGameTime() == Long.MAX_VALUE) return new Waiting(Wait.AUTOMATIC_DISABLED, destination);
        if (arrival == null && pending.dueGameTime() > now) {
            return new Timed(Timer.COOLDOWN, secondsUntil(pending.dueGameTime(), now), destination);
        }
        if (server.getPlayerList().getPlayer(pending.owner()) == null) return new Waiting(Wait.OWNER_OFFLINE, destination);
        if (arrival != null && arrival.at() > now) return new Timed(Timer.CEREMONY, secondsUntil(arrival.at(), now), destination);
        if (destination == PetRevival.Destination.HOME && !homeLoaded(server, pending)) {
            return new Waiting(CyuRevive.config.commandRecall ? Wait.CHUNK_UNLOADED_RECALL_AVAILABLE : Wait.CHUNK_UNLOADED, destination);
        }
        Wait reason = switch (pending.blockage()) {
            case NONE, OWNER_OFFLINE, CHUNK_UNLOADED -> Wait.QUEUED;
            case SPACE_BLOCKED -> Wait.SPACE_BLOCKED;
            case ENTITY_PRESENT -> Wait.ENTITY_PRESENT;
            case INSERTION_REFUSED -> Wait.INSERTION_REFUSED;
        };
        return new Waiting(reason, destination);
    }

    private static boolean homeLoaded(MinecraftServer server, PendingRevival pending) {
        ServerLevel level = server.getLevel(pending.bed().dim());
        if (level == null || !level.hasChunkAt(pending.bed().pos())) return false;
        var state = level.getBlockState(pending.bed().pos());
        return !(state.getBlock() instanceof PetBedBlock block) || block.loaded(level, pending.bed().pos(), state);
    }

    private static PetRevival.Destination destination(MinecraftServer server, PendingRevival pending, Arrival arrival) {
        if (arrival != null && arrival.destination() == PetRevival.Destination.OWNER) return PetRevival.Destination.OWNER;
        if (pending.availability() == ResidenceAvailability.MISSING) return PetRevival.Destination.OWNER;
        ServerLevel level = server.getLevel(pending.bed().dim());
        if (level == null) return PetRevival.Destination.OWNER;
        if (level.hasChunkAt(pending.bed().pos()) && !(level.getBlockState(pending.bed().pos()).getBlock() instanceof PetBedBlock)) {
            return PetRevival.Destination.OWNER;
        }
        return PetRevival.Destination.HOME;
    }
}
