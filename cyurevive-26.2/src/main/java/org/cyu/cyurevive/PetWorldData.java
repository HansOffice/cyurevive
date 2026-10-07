package org.cyu.cyurevive;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record FacilityChunk(ResourceKey<Level> dimension, int x, int z) {
}

record BedLoc(ResourceKey<Level> dim, BlockPos pos) {
}

enum ResidenceAvailability { PRESENT, MISSING }

record HomeRecord(BedLoc bed, UUID owner, String name, int slot, ResidenceAvailability availability) {
    HomeRecord(BedLoc bed, UUID owner, String name, int slot) {
        this(bed, owner, name, slot, ResidenceAvailability.PRESENT);
    }

    HomeRecord withAvailability(ResidenceAvailability next) {
        return new HomeRecord(bed, owner, name, slot, next);
    }
}

record PendingRevival(CompoundTag snapshot, UUID owner, BedLoc bed, long dueGameTime, String name, BedLoc diedAt,
                      int slot, ResidenceAvailability availability, PetRevival.Phase phase, PetRevival.Blockage blockage) {
    PendingRevival(CompoundTag snapshot, UUID owner, BedLoc bed, long dueGameTime, String name, BedLoc diedAt,
                   int slot, ResidenceAvailability availability) {
        this(snapshot, owner, bed, dueGameTime, name, diedAt, slot, availability, PetRevival.Phase.WAITING, PetRevival.Blockage.NONE);
    }

    PendingRevival withBlockage(PetRevival.Blockage next) {
        return new PendingRevival(snapshot, owner, bed, dueGameTime, name, diedAt, slot, availability, phase, next);
    }

    PendingRevival withPhase(PetRevival.Phase next) {
        return new PendingRevival(snapshot, owner, bed, dueGameTime, name, diedAt, slot, availability, next, blockage);
    }

    PendingRevival withAvailability(ResidenceAvailability next) {
        return new PendingRevival(snapshot, owner, bed, dueGameTime, name, diedAt, slot, next, phase, blockage);
    }
}

enum RescueNotice { PENDING, DELIVERED }

record DownedPet(long deadline, RescueNotice notice) {
    DownedPet withNotice(RescueNotice next) { return new DownedPet(deadline, next); }
}

record BrokenStructure(BedKind kind, Direction facing, BlockPos dropOrigin) {
}

record Arrival(long at, PetRevival.Destination destination, PetRevival.Origin origin) {
}

public class PetWorldData extends SavedData {
    public final Map<UUID, HomeRecord> homes = new LinkedHashMap<>();
    public final Map<UUID, PendingRevival> pending = new LinkedHashMap<>();
    public final Map<UUID, Arrival> arriving = new LinkedHashMap<>();
    public final Map<UUID, DownedPet> downed = new LinkedHashMap<>();
    public final Map<UUID, Long> protectedUntil = new LinkedHashMap<>();
    public final Set<UUID> doomed = new LinkedHashSet<>();
    public final Set<UUID> bannedOwners = new LinkedHashSet<>();
    public final Map<BedLoc, UUID> bedOwners = new LinkedHashMap<>();
    public final Map<BedLoc, BrokenStructure> brokenStructures = new LinkedHashMap<>();
    public final Map<BedLoc, String> homeNames = new LinkedHashMap<>();
    public final Set<UUID> hintedPlayers = new LinkedHashSet<>();

    public final PetTasks tasks = new PetTasks();
    public final PetSelection selection = new PetSelection();
    private final Map<BedLoc, Set<UUID>> residentsByBed = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> residentsByOwner = new LinkedHashMap<>();

    private final Map<FacilityChunk, Set<BedLoc>> facilitiesByChunk = new LinkedHashMap<>();
    private final Map<FacilityChunk, Set<BedLoc>> dismantlingByChunk = new LinkedHashMap<>();

    public Set<BedLoc> facilitiesInChunk(ResourceKey<Level> dimension, int x, int z) {
        return java.util.Collections.unmodifiableSet(facilitiesByChunk.getOrDefault(new FacilityChunk(dimension, x, z), Set.of()));
    }

    public Set<BedLoc> dismantlingInChunk(ResourceKey<Level> dimension, int x, int z) {
        return java.util.Collections.unmodifiableSet(dismantlingByChunk.getOrDefault(new FacilityChunk(dimension, x, z), Set.of()));
    }

    public boolean beginDismantling(BedLoc bed, BrokenStructure pen) {
        if (brokenStructures.putIfAbsent(bed, pen) != null) return false;
        indexFootprint(dismantlingByChunk, bed);
        setDirty();
        return true;
    }

    public void finishDismantling(BedLoc bed) {
        brokenStructures.remove(bed);
        unindexFootprint(dismantlingByChunk, bed);
        setDirty();
    }

    private static final int FACILITY_RADIUS = java.util.Arrays.stream(BedKind.values()).mapToInt(kind -> kind.layout().radius()).max().orElseThrow();

    private static void indexFootprint(Map<FacilityChunk, Set<BedLoc>> index, BedLoc bed) {
        for (int x = (bed.pos().getX() - FACILITY_RADIUS) >> 4; x <= (bed.pos().getX() + FACILITY_RADIUS) >> 4; x++) {
            for (int z = (bed.pos().getZ() - FACILITY_RADIUS) >> 4; z <= (bed.pos().getZ() + FACILITY_RADIUS) >> 4; z++) {
                index.computeIfAbsent(new FacilityChunk(bed.dim(), x, z), ignored -> new LinkedHashSet<>()).add(bed);
            }
        }
    }

    private static void unindexFootprint(Map<FacilityChunk, Set<BedLoc>> index, BedLoc bed) {
        for (int x = (bed.pos().getX() - FACILITY_RADIUS) >> 4; x <= (bed.pos().getX() + FACILITY_RADIUS) >> 4; x++) {
            for (int z = (bed.pos().getZ() - FACILITY_RADIUS) >> 4; z <= (bed.pos().getZ() + FACILITY_RADIUS) >> 4; z++) {
                FacilityChunk chunk = new FacilityChunk(bed.dim(), x, z);
                Set<BedLoc> facilities = index.get(chunk);
                if (facilities != null && facilities.remove(bed) && facilities.isEmpty()) index.remove(chunk);
            }
        }
    }

    public Set<UUID> residents(BedLoc bed) {
        return java.util.Collections.unmodifiableSet(residentsByBed.getOrDefault(bed, Set.of()));
    }

    public Set<UUID> owned(UUID owner) {
        return java.util.Collections.unmodifiableSet(residentsByOwner.getOrDefault(owner, Set.of()));
    }

    public void putDowned(UUID pet, long deadline, long now) {
        downed.put(pet, new DownedPet(deadline, RescueNotice.PENDING));
        refreshHomeTracking(pet);
        tasks.schedule(pet, PetTasks.Kind.DOWNED, deadline);
        long warningAt = Math.max(now + 20, deadline - 200);
        if (warningAt < deadline) tasks.schedule(pet, PetTasks.Kind.RESCUE_WARNING, warningAt);
        tasks.activate(pet);
        setDirty();
    }

    public DownedPet removeDowned(UUID pet) {
        DownedPet previous = downed.remove(pet);
        if (previous == null) return null;
        tasks.cancel(pet, PetTasks.Kind.DOWNED);
        tasks.cancel(pet, PetTasks.Kind.RESCUE_WARNING);
        tasks.deactivate(pet);
        refreshHomeTracking(pet);
        setDirty();
        return previous;
    }

    public void protect(UUID pet, long deadline) {
        protectedUntil.put(pet, deadline);
        tasks.schedule(pet, PetTasks.Kind.PROTECTION, deadline);
        setDirty();
    }

    public void removeProtection(UUID pet) {
        if (protectedUntil.remove(pet) == null) return;
        tasks.cancel(pet, PetTasks.Kind.PROTECTION);
        setDirty();
    }

    public void refreshHomeTracking(UUID pet) {
        HomeRecord home = homes.get(pet);
        if (home != null && home.availability() == ResidenceAvailability.PRESENT
            && !downed.containsKey(pet) && !doomed.contains(pet)) tasks.trackHome(pet);
        else tasks.untrackHome(pet);
    }

    public void residenceAvailability(BedLoc bed, ResidenceAvailability availability) {
        for (UUID pet : residents(bed)) {
            HomeRecord home = homes.get(pet);
            PendingRevival waiting = pending.get(pet);
            if (home != null) {
                homes.put(pet, home.withAvailability(availability));
                refreshHomeTracking(pet);
            }
            if (waiting != null) pending.put(pet, waiting.withAvailability(availability));
        }
        setDirty();
    }

    public void retryRevival(UUID pet) {
        tasks.retrySnapshot(pet);
        revivalPhase(pet, PetRevival.Phase.WAITING);
        revivalBlockage(pet, PetRevival.Blockage.NONE);
        setDirty();
    }

    public void revivalBlockage(UUID pet, PetRevival.Blockage blockage) {
        PendingRevival waiting = pending.get(pet);
        if (waiting == null || waiting.blockage() == blockage) return;
        pending.put(pet, waiting.withBlockage(blockage));
        setDirty();
    }

    public void revivalPhase(UUID pet, PetRevival.Phase phase) {
        PendingRevival waiting = pending.get(pet);
        if (waiting != null) pending.put(pet, waiting.withPhase(phase));
    }

    public void putHome(UUID pet, HomeRecord home) {
        removeHome(pet);
        removePending(pet);
        arriving.remove(pet);
        homes.put(pet, home);
        tasks.retrySnapshot(pet);
        refreshHomeTracking(pet);
        if (!downed.containsKey(pet)) tasks.deactivate(pet);
        link(pet, home.bed(), home.owner());
        setDirty();
    }

    public HomeRecord removeHome(UUID pet) {
        HomeRecord home = homes.remove(pet);
        if (home != null) {
            unlink(pet, home.bed(), home.owner());
            tasks.untrackHome(pet);
            setDirty();
        }
        return home;
    }

    public void putPending(UUID pet, PendingRevival waiting) {
        removeHome(pet);
        removePending(pet);
        pending.put(pet, waiting);
        link(pet, waiting.bed(), waiting.owner());
        tasks.schedule(pet, PetTasks.Kind.REVIVE, waiting.dueGameTime());
        setDirty();
    }

    public PendingRevival removePending(UUID pet) {
        PendingRevival waiting = pending.remove(pet);
        if (waiting != null) {
            unlink(pet, waiting.bed(), waiting.owner());
            tasks.retrySnapshot(pet);
            tasks.cancel(pet, PetTasks.Kind.REVIVE);
            tasks.cancel(pet, PetTasks.Kind.ARRIVE);
            if (!downed.containsKey(pet)) tasks.deactivate(pet);
            setDirty();
        }
        return waiting;
    }

    private void link(UUID pet, BedLoc bed, UUID owner) {
        if (!residentsByBed.containsKey(bed)) indexFootprint(facilitiesByChunk, bed);
        residentsByBed.computeIfAbsent(bed, ignored -> new LinkedHashSet<>()).add(pet);
        residentsByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(pet);
    }

    private void unlink(UUID pet, BedLoc bed, UUID owner) {
        Set<UUID> atBed = residentsByBed.get(bed);
        Set<UUID> ofOwner = residentsByOwner.get(owner);
        if (atBed != null && atBed.remove(pet) && atBed.isEmpty()) {
            residentsByBed.remove(bed);
            unindexFootprint(facilitiesByChunk, bed);
        }
        if (ofOwner != null && ofOwner.remove(pet) && ofOwner.isEmpty()) residentsByOwner.remove(owner);
    }

    private void restoreRuntime() {
        homes.forEach((pet, home) -> {
            link(pet, home.bed(), home.owner());
        });
        pending.forEach((pet, waiting) -> {
            if (homes.containsKey(pet)) throw new IllegalStateException("Pet has conflicting home and revival states: " + pet);
            link(pet, waiting.bed(), waiting.owner());
            if (waiting.phase() == PetRevival.Phase.UNREADABLE) tasks.invalidSnapshot(pet);
            tasks.schedule(pet, PetTasks.Kind.REVIVE, waiting.dueGameTime());
        });
        brokenStructures.keySet().forEach(bed -> indexFootprint(dismantlingByChunk, bed));
        arriving.forEach((pet, arrival) -> tasks.schedule(pet, PetTasks.Kind.ARRIVE, arrival.at()));
        downed.forEach((pet, injury) -> {
            tasks.schedule(pet, PetTasks.Kind.DOWNED, injury.deadline());
            if (injury.notice() == RescueNotice.PENDING) tasks.schedule(pet, PetTasks.Kind.RESCUE_WARNING, injury.deadline() - 200);
        });
        protectedUntil.forEach((pet, deadline) -> tasks.schedule(pet, PetTasks.Kind.PROTECTION, deadline));
    }

    public static final Codec<PetWorldData> CODEC = CompoundTag.CODEC
        .fieldOf("data")
        .xmap(PetWorldData::fromTag, PetWorldData::toTag)
        .codec();

    public static final SavedDataType<PetWorldData> TYPE =
        new SavedDataType<>(CyuRevive.id("pets"), PetWorldData::new, CODEC, null);

    public static PetWorldData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag homesTag = new ListTag();
        for (Map.Entry<UUID, HomeRecord> e : homes.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, e.getKey());
            entry.store("owner", UUIDUtil.CODEC, e.getValue().owner());
            entry.putString("name", e.getValue().name());
            entry.putInt("slot", e.getValue().slot());
            entry.putString("availability", e.getValue().availability().name());
            putBed(entry, e.getValue().bed());
            homesTag.add(entry);
        }
        tag.put("homes", homesTag);
        ListTag pendingTag = new ListTag();
        for (Map.Entry<UUID, PendingRevival> e : pending.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, e.getKey());
            entry.store("owner", UUIDUtil.CODEC, e.getValue().owner());
            entry.putString("name", e.getValue().name());
            entry.putInt("slot", e.getValue().slot());
            entry.putString("availability", e.getValue().availability().name());
            entry.put("snapshot", e.getValue().snapshot());
            entry.putString("blocked-by", e.getValue().blockage().name());
            entry.putString("phase", e.getValue().phase() == PetRevival.Phase.INSERTING
                ? PetRevival.Phase.WAITING.name() : e.getValue().phase().name());
            putBed(entry, e.getValue().bed());
            entry.putLong("due", e.getValue().dueGameTime());
            entry.putString("ddim", e.getValue().diedAt().dim().identifier().toString());
            entry.putLong("dpos", e.getValue().diedAt().pos().asLong());
            pendingTag.add(entry);
        }
        tag.put("pending", pendingTag);
        ListTag arrivingTag = new ListTag();
        for (Map.Entry<UUID, Arrival> e : arriving.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, e.getKey());
            entry.putLong("at", e.getValue().at());
            entry.putString("destination", e.getValue().destination().name());
            entry.putString("origin", e.getValue().origin().name());
            arrivingTag.add(entry);
        }
        tag.put("arriving", arrivingTag);
        ListTag downedTag = new ListTag();
        for (Map.Entry<UUID, DownedPet> e : downed.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, e.getKey());
            entry.putLong("deadline", e.getValue().deadline());
            entry.putString("notice", e.getValue().notice().name());
            downedTag.add(entry);
        }
        tag.put("downed", downedTag);
        ListTag protectionTag = new ListTag();
        for (Map.Entry<UUID, Long> e : protectedUntil.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, e.getKey());
            entry.putLong("until", e.getValue());
            protectionTag.add(entry);
        }
        tag.put("protectedUntil", protectionTag);
        ListTag doomedTag = new ListTag();
        for (UUID pet : doomed) {
            CompoundTag entry = new CompoundTag();
            entry.store("pet", UUIDUtil.CODEC, pet);
            doomedTag.add(entry);
        }
        tag.put("doomed", doomedTag);
        ListTag bannedTag = new ListTag();
        for (UUID owner : bannedOwners) {
            CompoundTag entry = new CompoundTag();
            entry.store("owner", UUIDUtil.CODEC, owner);
            bannedTag.add(entry);
        }
        tag.put("banned", bannedTag);
        ListTag ownersTag = new ListTag();
        for (Map.Entry<BedLoc, UUID> e : bedOwners.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.store("owner", UUIDUtil.CODEC, e.getValue());
            putBed(entry, e.getKey());
            ownersTag.add(entry);
        }
        tag.put("bedOwners", ownersTag);
        ListTag hintedTag = new ListTag();
        for (UUID player : hintedPlayers) {
            CompoundTag entry = new CompoundTag();
            entry.store("player", UUIDUtil.CODEC, player);
            hintedTag.add(entry);
        }
        tag.put("hinted", hintedTag);
        ListTag brokenTag = new ListTag();
        for (Map.Entry<BedLoc, BrokenStructure> entry : brokenStructures.entrySet()) {
            CompoundTag compound = new CompoundTag();
            putBed(compound, entry.getKey());
            compound.putString("kind", entry.getValue().kind().name());
            compound.putString("facing", entry.getValue().facing().getName());
            compound.putLong("dropOrigin", entry.getValue().dropOrigin().asLong());
            brokenTag.add(compound);
        }
        tag.put("brokenStructures", brokenTag);
        ListTag namedTag = new ListTag();
        for (Map.Entry<BedLoc, String> entry : homeNames.entrySet()) {
            CompoundTag compound = new CompoundTag();
            putBed(compound, entry.getKey());
            compound.putString("home", entry.getValue());
            namedTag.add(compound);
        }
        tag.put("homeNames", namedTag);
        return tag;
    }

    private static PetWorldData fromTag(CompoundTag tag) {
        PetWorldData data = new PetWorldData();
        for (Tag entry : tag.getListOrEmpty("homes")) {
            if (entry instanceof CompoundTag compound) {
                data.homes.put(compound.read("pet", UUIDUtil.CODEC).orElseThrow(), new HomeRecord(
                    readBed(compound), compound.read("owner", UUIDUtil.CODEC).orElseThrow(),
                    compound.getStringOr("name", ""), compound.getIntOr("slot", 0), ResidenceAvailability.valueOf(compound.getStringOr("availability", "PRESENT"))));
            }
        }
        for (Tag entry : tag.getListOrEmpty("pending")) {
            if (entry instanceof CompoundTag compound) {
                BedLoc bed = readBed(compound);
                BedLoc diedAt = compound.contains("dpos")
                    ? new BedLoc(
                        ResourceKey.create(Registries.DIMENSION,
                            Identifier.parse(compound.getStringOr("ddim", "minecraft:overworld"))),
                        BlockPos.of(compound.getLongOr("dpos", 0)))
                    : bed;
                data.pending.put(compound.read("pet", UUIDUtil.CODEC).orElseThrow(), new PendingRevival(
                    compound.getCompoundOrEmpty("snapshot"),
                    compound.read("owner", UUIDUtil.CODEC).orElseThrow(),
                    bed,
                    compound.getLongOr("due", 0),
                    compound.getStringOr("name", ""),
                    diedAt, compound.getIntOr("slot", 0), ResidenceAvailability.valueOf(compound.getStringOr("availability", "PRESENT")),
                    PetRevival.Phase.valueOf(compound.getStringOr("phase", "WAITING")),
                    PetRevival.Blockage.valueOf(compound.getStringOr("blocked-by", "NONE"))));
            }
        }
        for (Tag entry : tag.getListOrEmpty("arriving")) {
            if (entry instanceof CompoundTag compound) {
                compound.read("pet", UUIDUtil.CODEC).ifPresent(pet ->
                    data.arriving.put(pet, new Arrival(
                        compound.getLongOr("at", 0),
                        PetRevival.Destination.valueOf(compound.getStringOr("destination", "HOME")),
                        PetRevival.Origin.valueOf(compound.getStringOr("origin", "MANUAL")))));
            }
        }
        for (Tag entry : tag.getListOrEmpty("downed")) {
            if (entry instanceof CompoundTag compound) {
                data.downed.put(compound.read("pet", UUIDUtil.CODEC).orElseThrow(), new DownedPet(
                    compound.getLongOr("deadline", 0), RescueNotice.valueOf(compound.getStringOr("notice", "PENDING"))));
            }
        }
        for (Tag entry : tag.getListOrEmpty("doomed")) {
            if (entry instanceof CompoundTag compound) {
                compound.read("pet", UUIDUtil.CODEC).ifPresent(data.doomed::add);
            }
        }
        for (Tag entry : tag.getListOrEmpty("banned")) {
            if (entry instanceof CompoundTag compound) {
                compound.read("owner", UUIDUtil.CODEC).ifPresent(data.bannedOwners::add);
            }
        }
        for (Tag entry : tag.getListOrEmpty("bedOwners")) {
            if (entry instanceof CompoundTag compound) {
                compound.read("owner", UUIDUtil.CODEC).ifPresent(owner ->
                    data.bedOwners.put(readBed(compound), owner));
            }
        }
        for (Tag entry : tag.getListOrEmpty("hinted")) {
            if (entry instanceof CompoundTag compound) {
                compound.read("player", UUIDUtil.CODEC).ifPresent(data.hintedPlayers::add);
            }
        }
        for (Tag entry : tag.getListOrEmpty("brokenStructures")) {
            if (entry instanceof CompoundTag compound) {
                data.brokenStructures.put(readBed(compound), new BrokenStructure(BedKind.valueOf(compound.getString("kind").orElseThrow()), java.util.Objects.requireNonNull(
                    Direction.byName(compound.getString("facing").orElseThrow())), BlockPos.of(compound.getLong("dropOrigin").orElseThrow())));
            }
        }
        for (Tag entry : tag.getListOrEmpty("homeNames")) {
            if (entry instanceof CompoundTag compound) data.homeNames.put(readBed(compound), compound.getStringOr("home", ""));
        }
        for (Tag entry : tag.getListOrEmpty("protectedUntil")) {
            if (entry instanceof CompoundTag compound) {
                data.protectedUntil.put(compound.read("pet", UUIDUtil.CODEC).orElseThrow(), compound.getLongOr("until", 0));
            }
        }
        data.restoreRuntime();
        return data;
    }

    private static void putBed(CompoundTag tag, BedLoc bed) {
        tag.putString("dim", bed.dim().identifier().toString());
        tag.putLong("pos", bed.pos().asLong());
    }

    private static BedLoc readBed(CompoundTag tag) {
        return new BedLoc(
            ResourceKey.create(Registries.DIMENSION, Identifier.parse(tag.getStringOr("dim", "minecraft:overworld"))),
            BlockPos.of(tag.getLongOr("pos", 0)));
    }
}
