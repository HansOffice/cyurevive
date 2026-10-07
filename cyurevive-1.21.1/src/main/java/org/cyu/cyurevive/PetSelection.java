package org.cyu.cyurevive;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PetSelection {
    private static final int SELECTION_TICKS = 60 * 20;
    private static final int CLICK_COOLDOWN_TICKS = 5;
    private static final int FEEDBACK_BUDGET = 4;
    private sealed interface Selection permits Picked, Choosing {
        UUID pet();
        long expiresAt();
    }
    private record Picked(UUID pet, long expiresAt) implements Selection { }
    private record Choosing(UUID pet, BedLoc bed, long expiresAt) implements Selection { }
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, Long> clicks = new HashMap<>();
    private final ArrayDeque<UUID> feedback = new ArrayDeque<>();

    public boolean has(UUID owner) { return selections.containsKey(owner); }

    public void pick(ServerPlayer player, Animal pet) {
        PetWorldData data = PetWorldData.get(player.level().getServer());
        long now = player.level().getServer().overworld().getGameTime();
        if (!acceptClick(player.getUUID(), now)) return;
        if (problem(player, pet, data)) return;
        Selection previous = selections.get(player.getUUID());
        if (previous != null && previous.pet().equals(pet.getUUID())) {
            cancel(player);
            return;
        }
        data.tasks.entityLoaded(pet);
        put(player.getUUID(), new Picked(pet.getUUID(), now + SELECTION_TICKS));
        player.sendSystemMessage(message("cyurevive.selection.picked", pet.getDisplayName()));
        ((ServerLevel) pet.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER,
            pet.getX(), pet.getY() + pet.getBbHeight(), pet.getZ(), 4, 0.25, 0.1, 0.25, 0);
    }

    public void choose(ServerPlayer player, BedLoc bed, PetBedBlock block) {
        long now = player.level().getServer().overworld().getGameTime();
        if (!acceptClick(player.getUUID(), now)) return;
        PetWorldData data = PetWorldData.get(player.level().getServer());
        Selection selected = selections.get(player.getUUID());
        if (selected == null || now >= selected.expiresAt()) {
            forget(player.getUUID());
            player.sendSystemMessage(message("cyurevive.selection.first"));
            return;
        }
        Entity loaded = data.tasks.loadedEntity(player.level().getServer(), selected.pet());
        if (!(loaded instanceof Animal pet) || pet.level() != player.level()) {
            forget(player.getUUID());
            player.sendSystemMessage(message("cyurevive.selection.lost"));
            return;
        }
        if (problem(player, pet, data)) return;
        if (player.distanceToSqr(pet) > CyuRevive.config.bindRadius * CyuRevive.config.bindRadius) {
            player.sendSystemMessage(message("cyurevive.selection.too_far"));
            return;
        }
        if (!block.accepts(pet)) {
            player.sendSystemMessage(message("cyurevive.bind.wrong_species", Component.translatable("cyurevive.species." + block.species())));
            return;
        }
        UUID placer = data.bedOwners.get(bed);
        if (CyuRevive.config.bedPrivate && placer != null && !placer.equals(player.getUUID())) {
            player.sendSystemMessage(message("cyurevive.bed.private"));
            return;
        }
        int slot = PetResidences.freeSlot(data, bed, pet.getUUID(), block);
        if (slot < 0) {
            player.sendSystemMessage(message("cyurevive.selection.full"));
            return;
        }
        HomeRecord previous = data.homes.get(pet.getUUID());
        if ((previous == null || !previous.owner().equals(player.getUUID())) && CyuRevive.config.maxPetsPerPlayer >= 0
            && data.owned(player.getUUID()).size() >= CyuRevive.config.maxPetsPerPlayer) {
            player.sendSystemMessage(message("cyurevive.bind.limit"));
            return;
        }
        if (!block.kind().fits(pet)) {
            player.sendSystemMessage(message("cyurevive.bind.no_space"));
            return;
        }
        if (!(selected instanceof Choosing choice) || !choice.bed().equals(bed)) {
            put(player.getUUID(), new Choosing(pet.getUUID(), bed, now + SELECTION_TICKS));
            player.sendSystemMessage(message("cyurevive.selection.confirm", pet.getDisplayName(),
                Component.translatable("block.cyurevive." + block.bedName()), bed.pos().getX(), bed.pos().getY(), bed.pos().getZ()));
            return;
        }
        PetHomeGoal.Outcome outcome = ((PetHomeAccess) pet).cyurevive$homeGoal().request(bed, slot, PetHomeGoal.Intent.CALL);
        if (outcome != PetHomeGoal.Outcome.ARRIVED) {
            player.sendSystemMessage(message(outcome == PetHomeGoal.Outcome.BUSY ? "cyurevive.selection.busy" : "cyurevive.bind.no_space"));
            return;
        }
        data.putHome(pet.getUUID(), new HomeRecord(bed, player.getUUID(), pet.getName().getString(), slot));
        forget(player.getUUID());
        ((ServerLevel) pet.level()).sendParticles(ParticleTypes.HEART, pet.getX(), pet.getY() + pet.getBbHeight(), pet.getZ(), 4, 0.25, 0.1, 0.25, 0);
        player.sendSystemMessage(message(previous == null ? "cyurevive.bind.success" : "cyurevive.bind.moved", pet.getDisplayName()));
    }

    private boolean problem(ServerPlayer player, Animal pet, PetWorldData data) {
        if (!player.getUUID().equals(PetService.petOwnerId(pet)) || !pet.isAlive()) {
            player.sendSystemMessage(message("cyurevive.selection.not_owned"));
            return true;
        }
        if (data.bannedOwners.contains(player.getUUID())) {
            player.sendSystemMessage(message("cyurevive.admin.banned_you"));
            return true;
        }
        if (!CyuRevive.config.revivableTypes.contains(BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()))) {
            player.sendSystemMessage(message("cyurevive.selection.unsupported"));
            return true;
        }
        if (data.downed.containsKey(pet.getUUID()) || data.doomed.contains(pet.getUUID()) || data.pending.containsKey(pet.getUUID())) {
            player.sendSystemMessage(message("cyurevive.selection.downed"));
            return true;
        }
        return false;
    }

    public void guide(ServerPlayer player) {
        if (!acceptClick(player.getUUID(), player.level().getServer().overworld().getGameTime())) return;
        Selection selected = selections.get(player.getUUID());
        if (selected != null && player.level().getServer().overworld().getGameTime() < selected.expiresAt()) {
            player.sendSystemMessage(message(selected instanceof Choosing ? "cyurevive.selection.guide_confirm" : "cyurevive.selection.guide_home"));
            return;
        }
        forget(player.getUUID());
        player.sendSystemMessage(message("cyurevive.selection.first"));
    }

    public void cancel(ServerPlayer player) {
        if (!selections.containsKey(player.getUUID())) return;
        forget(player.getUUID());
        player.sendSystemMessage(message("cyurevive.selection.cancelled"));
    }

    public void forget(UUID owner) {
        selections.remove(owner);
        clicks.remove(owner);
        feedback.remove(owner);
    }

    public void clear() {
        selections.clear();
        clicks.clear();
        feedback.clear();
    }

    private boolean acceptClick(UUID owner, long now) {
        Long previous = clicks.get(owner);
        if (previous != null && now - previous < CLICK_COOLDOWN_TICKS) return false;
        clicks.put(owner, now);
        return true;
    }

    private void put(UUID owner, Selection selected) {
        if (selections.put(owner, selected) == null) feedback.addLast(owner);
    }

    public void tick(MinecraftServer server, PetWorldData data, long now) {
        int count = Math.min(FEEDBACK_BUDGET, feedback.size());
        for (int index = 0; index < count; index++) update(server, data, feedback.removeFirst(), now);
    }

    private void update(MinecraftServer server, PetWorldData data, UUID owner, long now) {
        Selection selected = selections.get(owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        Entity loaded = selected == null ? null : data.tasks.loadedEntity(server, selected.pet());
        if (selected == null || player == null || now >= selected.expiresAt()
            || !(loaded instanceof Animal pet) || !pet.isAlive() || pet.level() != player.level()
            || !owner.equals(PetService.petOwnerId(pet)) || data.downed.containsKey(pet.getUUID())
            || data.doomed.contains(pet.getUUID()) || data.pending.containsKey(pet.getUUID())) {
            forget(owner);
            return;
        }
        if (player.getMainHandItem().getItem() instanceof HomeMarkerItem) {
            player.sendSystemMessage(message(selected instanceof Choosing ? "cyurevive.selection.focus_confirm" : "cyurevive.selection.focus_home", pet.getDisplayName()), true);
        }
        feedback.addLast(owner);
    }

    private static Component message(String key, Object... values) {
        return Component.translatable(key, values).withColor(CyuRevive.TEXT_BRIGHT);
    }
}
