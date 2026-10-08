package org.cyu.cyurevive;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class PetTasks {
    public static final int MAX_DUE_TASKS_PER_TICK = 4;
    private static final int MAX_EFFECT_PETS_PER_SECOND = 16;
    private static final int MAX_NIGHT_PETS_PER_STEP = 4;
    public static final int NIGHT_CHECK_INTERVAL_TICKS = 5;
    public enum Kind { DOWNED, PROTECTION, RESCUE_WARNING, ARRIVE, REVIVE }
    public record Task(UUID pet, Kind kind, long at) implements Comparable<Task> {
        @Override
        public int compareTo(Task other) {
            int timeOrder = Long.compare(at, other.at);
            if (timeOrder != 0) return timeOrder;
            int petOrder = pet.compareTo(other.pet);
            return petOrder != 0 ? petOrder : kind.compareTo(other.kind);
        }
    }

    private static final Kind[] DEADLINE_PRIORITY = Kind.values();
    private final EnumMap<Kind, TreeSet<Task>> deadlines = new EnumMap<>(Kind.class);
    private final EnumMap<Kind, Map<UUID, Task>> byKind = new EnumMap<>(Kind.class);
    private final Map<UUID, ResourceKey<Level>> loadedEntities = new HashMap<>();
    private final Map<UUID, Long> rescueFeedbackAt = new HashMap<>();
    private final Set<UUID> invalidSnapshots = new HashSet<>();
    private final Rotation active = new Rotation();
    private final Rotation living = new Rotation();

    public PetTasks() {
        for (Kind kind : DEADLINE_PRIORITY) {
            byKind.put(kind, new HashMap<>());
            deadlines.put(kind, new TreeSet<>());
        }
    }

    public void schedule(UUID pet, Kind kind, long at) {
        cancel(pet, kind);
        if (at == Long.MAX_VALUE || (kind == Kind.REVIVE || kind == Kind.ARRIVE) && invalidSnapshots.contains(pet)) return;
        Task task = new Task(pet, kind, at);
        byKind.get(kind).put(pet, task);
        deadlines.get(kind).add(task);
    }

    public void cancel(UUID pet, Kind kind) {
        Task previous = byKind.get(kind).remove(pet);
        if (previous != null) deadlines.get(kind).remove(previous);
    }

    public Task poll(long now) {
        for (Kind kind : DEADLINE_PRIORITY) {
            TreeSet<Task> queue = deadlines.get(kind);
            if (queue.isEmpty() || queue.first().at() > now) continue;
            Task task = queue.pollFirst();
            byKind.get(kind).remove(task.pet());
            return task;
        }
        return null;
    }

    public boolean rescueFeedback(UUID owner, long now) {
        Long previous = rescueFeedbackAt.get(owner);
        if (previous != null && now >= previous && now - previous < 20) return false;
        rescueFeedbackAt.put(owner, now);
        return true;
    }

    public void clearRescueFeedback(UUID owner) { rescueFeedbackAt.remove(owner); }

    public boolean invalidSnapshot(UUID pet) { return invalidSnapshots.add(pet); }
    public void retrySnapshot(UUID pet) { invalidSnapshots.remove(pet); }
    public void activate(UUID pet) { active.add(pet); }
    public void deactivate(UUID pet) { active.remove(pet); }
    public void trackHome(UUID pet) { if (loadedEntities.containsKey(pet)) living.add(pet); }
    public void untrackHome(UUID pet) { living.remove(pet); }

    public void entityLoaded(Entity entity) {
        loadedEntities.put(entity.getUUID(), entity.level().dimension());
    }

    public void entityUnloaded(Entity entity) {
        if (loadedEntities.remove(entity.getUUID(), entity.level().dimension())) living.remove(entity.getUUID());
    }

    public Entity loadedEntity(MinecraftServer server, UUID pet) {
        ResourceKey<Level> dimension = loadedEntities.get(pet);
        var level = dimension == null ? null : server.getLevel(dimension);
        return level == null ? null : level.getEntity(pet);
    }

    public void ambience(MinecraftServer server, PetWorldData data, long now, int tickCount) {
        int count = Math.min(active.size(), MAX_EFFECT_PETS_PER_SECOND);
        for (int index = 0; index < count; index++) {
            UUID pet = active.next();
            PetService.ambience(server, data, pet, now, tickCount);
        }
    }

    public void nightReturn(MinecraftServer server, PetWorldData data) {
        int count = Math.min(living.size(), MAX_NIGHT_PETS_PER_STEP);
        for (int index = 0; index < count; index++) {
            PetService.nightReturn(server, data, living.next());
        }
    }

    private static final class Rotation {
        private final Map<UUID, Node> nodes = new HashMap<>();
        private Node next;

        private static final class Node {
            final UUID pet;
            Node before;
            Node after;
            Node(UUID pet) { this.pet = pet; }
        }

        int size() { return nodes.size(); }

        void add(UUID pet) {
            if (nodes.containsKey(pet)) return;
            Node node = new Node(pet);
            if (next == null) {
                node.before = node;
                node.after = node;
                next = node;
            } else {
                node.before = next.before;
                node.after = next;
                next.before.after = node;
                next.before = node;
            }
            nodes.put(pet, node);
        }

        void remove(UUID pet) {
            Node node = nodes.remove(pet);
            if (node == null) return;
            if (node.after == node) {
                next = null;
                return;
            }
            node.before.after = node.after;
            node.after.before = node.before;
            if (next == node) next = node.after;
        }

        UUID next() {
            if (next == null) throw new IllegalStateException("No active pet tasks");
            UUID pet = next.pet;
            next = next.after;
            return pet;
        }
    }
}
