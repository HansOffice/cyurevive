package org.cyu.cyurevive;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PetService {
    private static final int REVIVAL_RETRY_TICKS = 100;
    private PetService() {
    }

    private static Component info(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(CyuRevive.TEXT_DIM));
    }

    private static Component ok(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(CyuRevive.TEXT_SUCCESS));
    }

    private static Component err(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(CyuRevive.TEXT_ERROR));
    }

    private static Component alert(String key, Object... args) {
        return Component.translatable(key, args).withStyle(style -> style.withColor(CyuRevive.TEXT_AMBER));
    }

    private static Component name(Object target) {
        return (target instanceof Component component ? component.copy() : Component.literal(String.valueOf(target)))
            .withStyle(style -> style.withColor(CyuRevive.TEXT_AMBER));
    }

    private static Component value(Object target) {
        return (target instanceof Component component ? component.copy() : Component.literal(String.valueOf(target)))
            .withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT));
    }

    private static Component line(Component lead, Component tail) {
        return Component.literal("› ").withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT))
            .append(lead)
            .append(Component.literal(" · ").withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED)))
            .append(tail);
    }

    private static Component header(String key, Object... args) {
        return Component.literal("--- ").withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED))
            .append(Component.translatable(key, args).withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT)))
            .append(Component.literal(" ---").withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED)));
    }

    private static Component status(String key, Object arg) {
        return Component.literal("› ").withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT))
            .append(info(key, value(arg)));
    }

    public static boolean onUseBlock(Player player, Level level, BlockPos pos, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return false;
        if (!(level.getBlockState(pos).getBlock() instanceof PetBedBlock bedBlock)) return false;
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) return false;
        if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) return true;
        pos = bedBlock.center(pos, level.getBlockState(pos));
        if (!bedBlock.complete(level, pos)) {
            player.sendSystemMessage(err("cyurevive.selection.incomplete"));
            return true;
        }
        PetWorldData data = PetWorldData.get(serverLevel.getServer());
        BedLoc bed = new BedLoc(serverLevel.dimension(), pos.immutable());
        if (player.isShiftKeyDown()) {
            return player.getItemInHand(hand).isEmpty() && recall(player, serverLevel, bed, data);
        }
        return useBed(player, serverLevel, bed, bedBlock, data);
    }

    public static boolean onPetInteract(Player player, Entity entity, InteractionHand hand) {
        if (!(entity instanceof Animal pet)) return false;
        if (hand == InteractionHand.MAIN_HAND && player.getMainHandItem().getItem() instanceof HomeMarkerItem) {
            if (player instanceof ServerPlayer owner) PetWorldData.get(owner.level().getServer()).selection.pick(owner, pet);
            return true;
        }
        if (entity.level().isClientSide()) return false;
        MinecraftServer server = entity.getServer();
        if (server == null) return false;
        PetWorldData data = PetWorldData.get(server);
        if (data.downed.containsKey(pet.getUUID())) {
            if (!isOwner(pet, player)) return true;
            DownedPet injury = data.downed.get(pet.getUUID());
            long now = server.overworld().getGameTime();
            if (injury.deadline() <= now) {
                if (data.tasks.rescueFeedback(player.getUUID(), now)) player.sendSystemMessage(info("cyurevive.downed.expired"));
                return true;
            }
            var food = PetCare.rescueFood(player, pet, hand);
            if (food.isEmpty()) {
                if (data.tasks.rescueFeedback(player.getUUID(), now)) {
                    player.sendSystemMessage(info("cyurevive.downed.wrong_food", value(rescueItemName(pet))));
                }
                return true;
            }
            if (!player.isCreative()) food.orElseThrow().shrink(1);
            pet.setNoAi(false);
            pet.setInvulnerable(false);
            if (pet instanceof TamableAnimal tame) {
                tame.setOrderedToSit(false);
                tame.setInSittingPose(false);
            }
            pet.setHealth(Mth.clamp((float) (pet.getMaxHealth() * CyuRevive.config.downedHealPercent), 1f, pet.getMaxHealth()));
            if (CyuRevive.config.rescueRegenSeconds > 0) {
                pet.addEffect(new MobEffectInstance(MobEffects.REGENERATION, CyuRevive.config.rescueRegenSeconds * 20, 0));
            }
            data.removeDowned(pet.getUUID());
            if (pet.level() instanceof ServerLevel level) {
                hearts(level, pet);
                level.playSound(null, pet.blockPosition(), reviveSound(pet), SoundSource.NEUTRAL, 0.6f, 1.2f);
            }
            player.sendSystemMessage(ok("cyurevive.downed.saved", name(pet.getDisplayName())));
            return true;
        }
        if (player.isShiftKeyDown() && isOwner(pet, player) && !(pet instanceof AbstractHorse)) {
            HomeRecord home = data.homes.get(pet.getUUID());
            player.sendSystemMessage(home == null
                ? info("cyurevive.info.none", name(pet.getDisplayName()))
                : info("cyurevive.info.home", name(pet.getDisplayName()), value(posText(home.bed()))));
            return true;
        }
        return false;
    }

    public static boolean beforeDeath(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof Animal pet)) return false;
        if (!CyuRevive.config.downedEnabled || !isRevivable(pet)) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        MinecraftServer server = entity.getServer();
        if (server == null) return false;
        PetWorldData data = PetWorldData.get(server);
        UUID ownerId = petOwnerId(pet);
        if (ownerId == null || data.bannedOwners.contains(ownerId)) return false;
        if (data.downed.containsKey(pet.getUUID()) || data.doomed.contains(pet.getUUID())) return false;
        long now = server.overworld().getGameTime();
        data.putDowned(pet.getUUID(), now + CyuRevive.config.downedSeconds * 20L, now);
        data.tasks.entityLoaded(pet);
        pet.setHealth(1f);
        pet.setInvulnerable(true);
        if (pet instanceof TamableAnimal tame) {
            tame.setOrderedToSit(true);
            tame.setInSittingPose(true);
        }
        pet.setNoAi(true);
        pet.getNavigation().stop();
        pet.setDeltaMovement(Vec3.ZERO);
        if (pet.level() instanceof ServerLevel level) {
            level.playSound(null, pet.blockPosition(), downedSound(pet), SoundSource.NEUTRAL, 1f, 0.7f);
        }
        Component itemName = rescueItemName(pet);
        BedLoc location = new BedLoc(pet.level().dimension(), pet.blockPosition());
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner != null) {
            HomeRecord home = data.homes.get(pet.getUUID());
            String notice = home != null && home.owner().equals(ownerId) ? "cyurevive.downed.start" : "cyurevive.downed.start_unbound";
            owner.sendSystemMessage(
                alert(notice, name(pet.getDisplayName()), value(CyuRevive.config.downedSeconds), value(itemName),
                    value(posText(location)), value(dimensionLabel(location))));
        }
        return true;
    }

    public static PetCare.DeathOutcome captureDeath(LivingEntity entity) {
        if (!(entity instanceof Animal pet)) return PetCare.DeathOutcome.ORDINARY;
        MinecraftServer server = entity.getServer();
        if (server == null) return PetCare.DeathOutcome.ORDINARY;
        PetWorldData data = PetWorldData.get(server);
        data.removeDowned(pet.getUUID());
        data.removeProtection(pet.getUUID());
        if (data.doomed.remove(pet.getUUID())) data.setDirty();
        if (data.pending.containsKey(pet.getUUID())) return PetCare.DeathOutcome.RECORDED;
        HomeRecord home = data.homes.get(pet.getUUID());
        if (home == null) return PetCare.DeathOutcome.ORDINARY;
        if (!isRevivable(pet) || data.bannedOwners.contains(home.owner()) || !home.owner().equals(petOwnerId(pet))) {
            data.removeHome(pet.getUUID());
            return PetCare.DeathOutcome.ORDINARY;
        }
        CompoundTag snapshot = pet.saveWithoutId(new CompoundTag());
        if (!snapshot.contains("id")) {
            snapshot.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString());
        }
        snapshot.remove("UUID");
        snapshot.remove("Pos");
        snapshot.remove("Motion");
        snapshot.remove(net.minecraft.world.entity.Leashable.LEASH_TAG);
        snapshot.remove("Passengers");
        snapshot.remove("Rotation");
        snapshot.remove("Invulnerable");
        snapshot.remove("DeathTime");
        snapshot.remove("HurtTime");
        snapshot.remove("Health");
        snapshot.remove("NoAI");
        snapshot.remove("Sitting");
        snapshot.remove("FallFlying");
        snapshot.remove("SleepingX");
        snapshot.remove("SleepingY");
        snapshot.remove("SleepingZ");
        int cooldown = CyuRevive.config.reviveCooldownSeconds;
        long due = cooldown <= 0 ? Long.MAX_VALUE : server.overworld().getGameTime() + cooldown * 20L;
        BedLoc diedAt = new BedLoc(pet.level().dimension(), pet.blockPosition());
        data.putPending(pet.getUUID(), new PendingRevival(snapshot, home.owner(), home.bed(), due, pet.getName().getString(), diedAt, home.slot(), home.availability()));
        activateWaiting(server, data, pet.getUUID());
        data.setDirty();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            pet.setItemSlot(slot, ItemStack.EMPTY);
        }
        if (pet instanceof AbstractHorse horse) {
            for (int i = 0; i < horse.getInventorySize(); i++) {
                horse.getSlot(AbstractHorse.INVENTORY_SLOT_OFFSET + i).set(ItemStack.EMPTY);
            }
        }
        ServerLevel bedLevel = server.getLevel(home.bed().dim());
        if (bedLevel != null && bedLevel.isLoaded(home.bed().pos())) {
            BlockPos bedPos = home.bed().pos();
            bedLevel.sendParticles(ParticleTypes.LARGE_SMOKE, bedPos.getX() + 0.5, bedPos.getY() + 0.6, bedPos.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0.02);
            bedLevel.sendParticles(ParticleTypes.SOUL, bedPos.getX() + 0.5, bedPos.getY() + 0.7, bedPos.getZ() + 0.5, 4, 0.2, 0.15, 0.2, 0.01);
        }
        ServerPlayer owner = server.getPlayerList().getPlayer(home.owner());
        if (owner != null) {
            owner.sendSystemMessage(info("cyurevive.revive.waiting", name(pet.getDisplayName())));
        }
        if (CyuRevive.config.broadcastDeathMessage) {
            server.getPlayerList().broadcastSystemMessage(pet.getCombatTracker().getDeathMessage(), false);
        }
        return PetCare.DeathOutcome.RECORDED;
    }

    public static void onBedPlaced(ServerPlayer player, ServerLevel level, BlockPos pos) {
        PetWorldData data = PetWorldData.get(level.getServer());
        BedLoc bed = new BedLoc(level.dimension(), pos.immutable());
        data.bedOwners.put(bed, player.getUUID());
        data.residenceAvailability(bed, ResidenceAvailability.PRESENT);
        if (data.hintedPlayers.add(player.getUUID())) {
            player.sendSystemMessage(info("cyurevive.hint.place"));
        }
        data.setDirty();
    }

    public static void onBedRemoved(ServerLevel level, BlockPos pos) {
        PetWorldData data = PetWorldData.get(level.getServer());
        BedLoc bed = new BedLoc(level.dimension(), pos.immutable());
        data.bedOwners.remove(bed);
        data.homeNames.remove(bed);
        data.residenceAvailability(bed, ResidenceAvailability.MISSING);
        for (UUID petId : data.residents(bed)) {
            HomeRecord home = data.homes.get(petId);
            PendingRevival pending = data.pending.get(petId);
            if (pending != null) {
                data.tasks.deactivate(petId);
                activateWaiting(level.getServer(), data, petId);
            }
            UUID ownerId = home != null ? home.owner() : pending.owner();
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
            if (owner == null) continue;
            Entity pet = findPet(level.getServer(), petId);
            Component petName = name(pet != null ? pet.getDisplayName() : nameText(home != null ? home.name() : pending.name()));
            owner.sendSystemMessage(info(pending != null ? "cyurevive.bed.destroyed_waiting" : "cyurevive.bed.destroyed", petName));
        }
        data.setDirty();
    }

    public static void onEntityLoad(Entity entity) {
        if (entity.level().isClientSide() || !(entity instanceof Animal animal) || petOwnerId(animal) == null) return;
        MinecraftServer server = entity.getServer();
        if (server == null) return;
        PetWorldData data = PetWorldData.get(server);
        Entity loaded = data.tasks.loadedEntity(server, entity.getUUID());
        if (loaded != null && loaded != entity && !loaded.isRemoved()) {
            CyuRevive.LOGGER.error("发现重复宠物身份 {}，保留已加载的宠物并移除重复实体", entity.getUUID());
            entity.discard();
            return;
        }
        data.tasks.entityLoaded(entity);
        HomeRecord residence = data.homes.get(entity.getUUID());
        if (residence != null && entity.level() instanceof ServerLevel residenceLevel) {
            Vec3 position = PetResidences.position(residenceLevel, residence.bed(), residence.slot());
            if (position != null && entity.distanceToSqr(position) <= 0.16) ((PetHomeAccess) animal).cyurevive$homeGoal().request(residence.bed(), residence.slot(), PetHomeGoal.Intent.RESUME);
        }
        if (data.downed.containsKey(entity.getUUID())) data.tasks.activate(entity.getUUID());
        data.refreshHomeTracking(entity.getUUID());
        PendingRevival waiting = data.pending.get(entity.getUUID());
        if (waiting != null && entity instanceof Animal pet && pet.isAlive()
            && waiting.phase() != PetRevival.Phase.INSERTING
            && waiting.owner().equals(petOwnerId(pet)) && !data.downed.containsKey(pet.getUUID())) {
            PetRevival.restoreVitals(pet);
            data.putHome(pet.getUUID(), new HomeRecord(waiting.bed(), waiting.owner(), waiting.name(), waiting.slot(), waiting.availability()));
            PetCare.protect(server, data, pet);
        }
        if (data.doomed.contains(entity.getUUID()) && entity instanceof Animal pet) {
            data.doomed.remove(entity.getUUID());
            data.setDirty();
            killDowned(pet);
        }
    }

    public static void onEntityUnload(Entity entity) {
        if (!(entity instanceof Animal)) return;
        ((PetHomeAccess) entity).cyurevive$suspendHome();
        MinecraftServer server = entity.level().getServer();
        if (server != null && !entity.level().isClientSide()) {
            PetWorldData data = PetWorldData.get(server);
            data.tasks.entityUnloaded(entity);
            if (data.downed.containsKey(entity.getUUID())) data.tasks.deactivate(entity.getUUID());
        }
    }

    public static void onFacilityChunkLoaded(ServerLevel level, PetWorldData data, int x, int z) {
        long now = level.getServer().overworld().getGameTime();
        for (BedLoc bed : data.facilitiesInChunk(level.dimension(), x, z)) {
            for (UUID pet : data.residents(bed)) {
                PendingRevival pending = data.pending.get(pet);
                if (pending == null) continue;
                activateWaiting(level.getServer(), data, pet);
                if (level.getServer().getPlayerList().getPlayer(pending.owner()) == null) continue;
                Arrival arrival = data.arriving.get(pet);
                if (arrival != null) data.tasks.schedule(pet, PetTasks.Kind.ARRIVE, Math.max(now, arrival.at()));
                else if (pending.dueGameTime() <= now) data.tasks.schedule(pet, PetTasks.Kind.REVIVE, now);
            }
        }
    }

    public static void onFacilityChunkUnloaded(ServerLevel level, int x, int z) {
        PetWorldData data = PetWorldData.get(level.getServer());
        for (BedLoc bed : data.facilitiesInChunk(level.dimension(), x, z)) {
            for (UUID pet : data.residents(bed)) {
                PendingRevival pending = data.pending.get(pet);
                if (pending != null && !ownerArrival(data, pet, pending)) data.tasks.deactivate(pet);
            }
        }
    }

    public static void onStop(MinecraftServer server) {
        PetWorldData.get(server).selection.clear();
    }

    public static void onQuit(MinecraftServer server, UUID owner) {
        PetWorldData data = PetWorldData.get(server);
        data.tasks.clearRescueFeedback(owner);
        data.selection.forget(owner);
        for (UUID pet : data.owned(owner)) {
            PendingRevival pending = data.pending.get(pet);
            if (pending != null && ownerArrival(data, pet, pending)) data.tasks.deactivate(pet);
        }
    }

    private static boolean ownerArrival(PetWorldData data, UUID pet, PendingRevival pending) {
        Arrival arrival = data.arriving.get(pet);
        return arrival != null && (arrival.destination() == PetRevival.Destination.OWNER
            || pending.availability() == ResidenceAvailability.MISSING);
    }

    private static void activateWaiting(MinecraftServer server, PetWorldData data, UUID pet) {
        PendingRevival pending = data.pending.get(pet);
        if (pending == null) return;
        if (ownerArrival(data, pet, pending)) {
            if (server.getPlayerList().getPlayer(pending.owner()) != null) data.tasks.activate(pet);
            return;
        }
        if (pending.availability() == ResidenceAvailability.MISSING) return;
        ServerLevel level = server.getLevel(pending.bed().dim());
        if (level != null && PetResidences.position(level, pending.bed(), pending.slot()) != null) data.tasks.activate(pet);
    }

    public static void onJoin(ServerPlayer player) {
        PetWorldData data = PetWorldData.get(player.getServer());
        long now = player.level().getServer().overworld().getGameTime();
        long waiting = 0;
        for (UUID pet : data.owned(player.getUUID())) {
            PendingRevival pending = data.pending.get(pet);
            if (pending == null) continue;
            waiting++;
            activateWaiting(player.level().getServer(), data, pet);
            Arrival arrival = data.arriving.get(pet);
            if (arrival != null) data.tasks.schedule(pet, PetTasks.Kind.ARRIVE, Math.max(now, arrival.at()));
            if (pending.dueGameTime() <= now && arrival == null) {
                data.tasks.schedule(pet, PetTasks.Kind.REVIVE, now);
            }
        }
        if (waiting > 0) {
            player.sendSystemMessage(info("cyurevive.revive.waiting_count", value(waiting)));
        }
    }

    public static void onSleptThrough(ServerLevel level) {
        if (!CyuRevive.config.reviveOnSleep) return;
        PetWorldData data = PetWorldData.get(level.getServer());
        long now = level.getServer().overworld().getGameTime();
        int requested = 0;
        for (ServerPlayer player : level.players()) {
            if (player.isSleeping()) requested += requestSleepingOwner(level.getServer(), data, player.getUUID(), now);
        }
        if (requested > 0) data.setDirty();
    }

    private static int requestSleepingOwner(MinecraftServer server, PetWorldData data, UUID owner, long now) {
        int requested = 0;
        for (UUID pet : data.owned(owner)) {
            if (!data.pending.containsKey(pet) || data.arriving.containsKey(pet)) continue;
            data.arriving.put(pet, new Arrival(now, PetRevival.Destination.HOME, PetRevival.Origin.SLEEP));
            data.tasks.schedule(pet, PetTasks.Kind.ARRIVE, now);
            activateWaiting(server, data, pet);
            requested++;
        }
        return requested;
    }

    private static void warnRescue(MinecraftServer server, PetWorldData data, UUID petId, long now) {
        DownedPet injury = data.downed.get(petId);
        if (injury == null || injury.notice() == RescueNotice.DELIVERED || injury.deadline() <= now) return;
        Entity entity = findPet(server, petId);
        Animal pet = entity instanceof Animal animal ? animal : null;
        UUID ownerId = pet == null ? null : petOwnerId(pet);
        ServerPlayer owner = ownerId == null ? null : server.getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            if (now + 20 < injury.deadline()) data.tasks.schedule(petId, PetTasks.Kind.RESCUE_WARNING, now + 20);
            return;
        }
        BedLoc location = new BedLoc(pet.level().dimension(), pet.blockPosition());
        owner.sendSystemMessage(alert("cyurevive.downed.urgent", name(pet.getDisplayName()),
            value(PetProgress.secondsUntil(injury.deadline(), now)), value(rescueItemName(pet)),
            value(posText(location)), value(dimensionLabel(location))));
        data.downed.put(petId, injury.withNotice(RescueNotice.DELIVERED));
        data.setDirty();
    }

    public static void tick(MinecraftServer server) {
        PetWorldData data = PetWorldData.get(server);
        int tickCount = server.getTickCount();
        long now = server.overworld().getGameTime();
        for (int processed = 0; processed < PetTasks.MAX_DUE_TASKS_PER_TICK; processed++) {
            PetTasks.Task task = data.tasks.poll(now);
            if (task == null) break;
            switch (task.kind()) {
                case REVIVE -> {
                    if (data.pending.containsKey(task.pet()) && !data.arriving.containsKey(task.pet())
                        && server.getPlayerList().getPlayer(data.pending.get(task.pet()).owner()) != null) {
                        if (revive(data, server, task.pet(), PetRevival.Destination.HOME) == PetRevival.Result.RETRY) {
                            data.tasks.schedule(task.pet(), PetTasks.Kind.REVIVE, now + REVIVAL_RETRY_TICKS);
                        }
                    }
                }
                case ARRIVE -> {
                    Arrival arrival = data.arriving.get(task.pet());
                    PendingRevival waiting = data.pending.get(task.pet());
                    if (arrival != null && waiting != null && server.getPlayerList().getPlayer(waiting.owner()) != null) {
                        PetRevival.Result result = revive(data, server, task.pet(), arrival.destination());
                        switch (result) {
                            case COMPLETED -> data.arriving.remove(task.pet());
                            case RETRY -> data.tasks.schedule(task.pet(), PetTasks.Kind.ARRIVE, now + REVIVAL_RETRY_TICKS);
                            case DORMANT, INVALID -> { }
                        }
                        data.setDirty();
                    }
                }
                case RESCUE_WARNING -> warnRescue(server, data, task.pet(), now);
                case PROTECTION -> {
                    Long deadline = data.protectedUntil.get(task.pet());
                    if (deadline != null && deadline <= now) data.removeProtection(task.pet());
                }
                case DOWNED -> {
                    DownedPet injury = data.downed.get(task.pet());
                    if (injury == null || injury.deadline() > now) continue;
                    Entity pet = findPet(server, task.pet());
                    data.removeDowned(task.pet());
                    if (pet instanceof Animal animal) killDowned(animal); else data.doomed.add(task.pet());
                    data.setDirty();
                }
            }
        }
        if (tickCount % 10 == 0) data.selection.tick(server, data, now);
        if (tickCount % 20 == 0) {
            data.tasks.ambience(server, data, now, tickCount);
        }
        if (CyuRevive.config.nightReturn && tickCount % PetTasks.NIGHT_CHECK_INTERVAL_TICKS == 0) {
            data.tasks.nightReturn(server, data);
        }
    }

    static void ambience(MinecraftServer server, PetWorldData data, UUID petId, long now, int tickCount) {
        DownedPet injury = data.downed.get(petId);
        if (injury != null) {
            Entity entity = findPet(server, petId);
            if (!(entity instanceof Animal pet) || !(pet.level() instanceof ServerLevel level)
                || !watched(level, pet.position())) return;
            UUID ownerId = petOwnerId(pet);
            PetCare.showRescueStatus(ownerId == null ? null : server.getPlayerList().getPlayer(ownerId), pet, injury, now);
            boolean urgent = injury.deadline() - now <= 200;
            if (!urgent && tickCount % 40 != 0) return;
            level.sendParticles(urgent ? ParticleTypes.SCULK_SOUL : ParticleTypes.SMOKE,
                pet.getX(), pet.getY() + 0.6, pet.getZ(), urgent ? 3 : 2, 0.15, 0.15, 0.15, 0.01);
            level.playSound(null, pet.blockPosition(), downedSound(pet), SoundSource.NEUTRAL, 0.6f, urgent ? 0.55f : 0.9f);
            return;
        }
        PendingRevival pending = data.pending.get(petId);
        if (pending == null) return;
        Arrival arrival = data.arriving.get(petId);
        if (arrival != null) {
            SpawnPoint target = arrivalTarget(server, pending, arrival);
            if (target != null && watched(target.level(), new Vec3(target.x(), target.y(), target.z()))) {
                target.level().sendParticles(ParticleTypes.SOUL, target.x(), target.y() + 0.4, target.z(), 4, 0.5, 0.35, 0.5, 0.01);
            }
            return;
        }
        if (!CyuRevive.config.bedAura) return;
        ServerLevel level = server.getLevel(pending.bed().dim());
        if (level == null) return;
        Vec3 position = PetResidences.position(level, pending.bed(), pending.slot());
        if (position != null && watched(level, position)) {
            level.sendParticles(ParticleTypes.SOUL, position.x, position.y + 0.55, position.z, 1, 0.25, 0.12, 0.25, 0);
        }
    }

    static boolean watched(ServerLevel level, Vec3 position) {
        List<ServerPlayer> viewers = level.players();
        for (int index = 0; index < viewers.size(); index++) {
            if (viewers.get(index).distanceToSqr(position) <= 32 * 32) return true;
        }
        return false;
    }

    static void nightReturn(MinecraftServer server, PetWorldData data, UUID petId) {
        HomeRecord home = data.homes.get(petId);
        if (home == null || data.downed.containsKey(petId) || data.doomed.contains(petId)) return;
        ServerLevel level = server.getLevel(home.bed().dim());
        if (level == null) return;
        long dayTime = level.getDayTime() % 24000L;
        if (dayTime < 13000 || dayTime > 23000) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(home.owner());
        if (owner != null && owner.level() == level) return;
        Entity entity = findPet(server, petId);
        if (!(entity instanceof Animal pet) || !pet.isAlive() || pet.level() != level) return;
        if (pet instanceof TamableAnimal tame && tame.isOrderedToSit()) return;
        Vec3 target = PetResidences.position(level, home.bed(), home.slot());
        if (target == null || !PetResidences.safe(level, pet, target)) return;
        double distance = pet.distanceToSqr(target);
        if (distance > 144) return;
        ((PetHomeAccess) pet).cyurevive$homeGoal().request(home.bed(), home.slot(), PetHomeGoal.Intent.NIGHT);
    }

    private record SpawnPoint(ServerLevel level, double x, double y, double z) {
    }

    private static SpawnPoint arrivalTarget(MinecraftServer server, PendingRevival pending, Arrival arrival) {
        if (arrival.destination() == PetRevival.Destination.HOME) {
            ServerLevel level = server.getLevel(pending.bed().dim());
            if (level != null && level.isLoaded(pending.bed().pos())
                && level.getBlockState(pending.bed().pos()).getBlock() instanceof PetBedBlock block) {
                Vec3 position = PetResidences.position(level, pending.bed(), pending.slot());
                return position == null ? null : new SpawnPoint(level, position.x, position.y, position.z);
            }
        }
        return ownerSpot(server, pending.owner());
    }

    private static SpawnPoint ownerSpot(MinecraftServer server, UUID ownerId) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || !(owner.level() instanceof ServerLevel level)) return null;
        BlockPos pos = owner.blockPosition();
        return new SpawnPoint(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static long occupied(PetWorldData data, BedLoc bed) {
        return data.residents(bed).size();
    }

    private static boolean useBed(Player player, ServerLevel level, BedLoc bed, PetBedBlock bedBlock, PetWorldData data) {
        if (player.getMainHandItem().getItem() instanceof HomeMarkerItem
            || player.getMainHandItem().isEmpty() && data.selection.has(player.getUUID())) {
            data.selection.choose((ServerPlayer) player, bed, bedBlock);
            return true;
        }
        sendBedResidents(player, level, data, bed);
        if (!player.getMainHandItem().isEmpty()) player.sendSystemMessage(info("cyurevive.selection.first"));
        return true;
    }

    private static void sendBedResidents(Player player, ServerLevel level, PetWorldData data, BedLoc bed) {
        List<Component> lines = new ArrayList<>();
        long waiting = 0;
        for (UUID pet : data.residents(bed)) {
            HomeRecord home = data.homes.get(pet);
            PendingRevival pending = data.pending.get(pet);
            if (home != null) {
                Entity live = findPet(level.getServer(), pet);
                lines.add(line(name(live != null ? live.getName() : nameText(home.name())), info("cyurevive.bed.resident_home")));
            } else if (pending != null) {
                waiting++;
                lines.add(line(name(nameText(pending.name())), waitingStatus(level.getServer(), data, pet, pending,
                    level.getServer().overworld().getGameTime())));
            }
        }
        player.sendSystemMessage(header("cyurevive.bed.residents"));
        String homeName = data.homeNames.get(bed);
        if (homeName != null) player.sendSystemMessage(info("cyurevive.home.label", value(homeName)));
        if (lines.isEmpty()) {
            player.sendSystemMessage(info("cyurevive.bed.empty"));
        } else {
            lines.forEach(player::sendSystemMessage);
        }
        player.sendSystemMessage(info("cyurevive.bed.status", value(occupied(data, bed)), value(Math.min(CyuRevive.config.bedCapacity, ((PetBedBlock) level.getBlockState(bed.pos()).getBlock()).kind().capacity())), value(waiting)));
    }

    private static boolean recall(Player player, ServerLevel level, BedLoc bed, PetWorldData data) {
        PetConfig config = CyuRevive.config;
        if (data.bannedOwners.contains(player.getUUID())) {
            player.sendSystemMessage(err("cyurevive.admin.banned_you"));
            return true;
        }
        long coming = 0;
        if (config.recallEnabled) {
            List<UUID> waiting = new ArrayList<>();
            for (UUID petId : data.residents(bed)) {
                PendingRevival pending = data.pending.get(petId);
                if (pending == null || !pending.owner().equals(player.getUUID())) continue;
                Arrival arrival = data.arriving.get(petId);
                if (arrival != null && arrival.destination() == PetRevival.Destination.HOME
                    && arrival.origin() == PetRevival.Origin.MANUAL && pending.phase() != PetRevival.Phase.UNREADABLE) coming++;
                else if (canReviveNow(level.getServer(), pending)) waiting.add(petId);
            }
            if (!waiting.isEmpty()) {
                requestRecall((ServerPlayer) player, data, waiting, PetRevival.Destination.HOME);
                return true;
            }
        }
        int called = 0;
        for (UUID petId : data.residents(bed)) {
            HomeRecord home = data.homes.get(petId);
            if (home == null || !home.owner().equals(player.getUUID()) || data.downed.containsKey(petId)) continue;
            if (!(findPet(level.getServer(), petId) instanceof Animal pet) || !pet.isAlive() || pet.level() != level) continue;
            if (PetResidences.settle(pet, bed, home.slot())) called++;
        }
        if (called > 0) {
            player.sendSystemMessage(ok("cyurevive.recall.home", value(called)));
        } else if (coming > 0) {
            player.sendSystemMessage(info("cyurevive.recall.coming_already"));
        } else {
            player.sendSystemMessage(info("cyurevive.recall.none"));
        }
        return true;
    }

    private static PetRevival.Result revive(PetWorldData data, MinecraftServer server, UUID petId,
                                             PetRevival.Destination destination) {
        PendingRevival pending = data.pending.get(petId);
        if (pending == null || data.bannedOwners.contains(pending.owner())) return PetRevival.Result.RETRY;
        ServerPlayer owner = server.getPlayerList().getPlayer(pending.owner());
        if (owner == null) {
            data.revivalBlockage(petId, PetRevival.Blockage.OWNER_OFFLINE);
            return PetRevival.Result.DORMANT;
        }
        Entity existing = findPet(server, petId);
        if (existing != null) {
            if (!existing.isAlive() || !(existing instanceof Animal pet) || !pending.owner().equals(petOwnerId(pet))) {
                data.revivalBlockage(petId, PetRevival.Blockage.ENTITY_PRESENT);
                return PetRevival.Result.RETRY;
            }
            PetRevival.restoreVitals(pet);
            data.putHome(petId, new HomeRecord(pending.bed(), pending.owner(), pending.name(), pending.slot(), pending.availability()));
            PetCare.protect(server, data, pet);
            return PetRevival.Result.COMPLETED;
        }
        return switch (PetRevival.prepare(pending, server, destination)) {
            case PetRevival.Invalid invalid -> {
                snapshotFailure(data, petId, pending, owner, invalid.reason());
                yield PetRevival.Result.INVALID;
            }
            case PetRevival.Delayed delayed -> {
                data.revivalBlockage(petId, blockage(delayed.reason()));
                yield switch (delayed.reason()) {
                    case OWNER_OFFLINE, CHUNK_UNLOADED -> PetRevival.Result.DORMANT;
                    case SPACE_BLOCKED -> PetRevival.Result.RETRY;
                };
            }
            case PetRevival.Prepared prepared -> commitRevival(data, petId, pending, owner, prepared);
        };
    }

    private static PetRevival.Result commitRevival(PetWorldData data, UUID petId, PendingRevival pending,
                                                   ServerPlayer owner, PetRevival.Prepared prepared) {
        Animal pet = prepared.pet();
        ServerLevel level = prepared.level();
        Vec3 position = prepared.position();
        pet.setUUID(petId);
        pet.moveTo(position.x, position.y, position.z, pet.getYRot(), 0f);
        PetRevival.restoreVitals(pet);
        data.revivalPhase(petId, PetRevival.Phase.INSERTING);
        try {
            if (!level.addFreshEntity(pet)) {
                data.revivalBlockage(petId, PetRevival.Blockage.INSERTION_REFUSED);
                return PetRevival.Result.RETRY;
            }
        } finally {
            data.revivalPhase(petId, PetRevival.Phase.WAITING);
        }
        data.putHome(petId, new HomeRecord(pending.bed(), pending.owner(), pending.name(), pending.slot(), pending.availability()));
        PetCare.protect(level.getServer(), data, pet);
        if (prepared.landing() == PetRevival.Landing.HOME) PetResidences.settle(pet, pending.bed(), pending.slot());
        hearts(level, pet);
        level.playSound(null, pet.blockPosition(), reviveSound(pet), SoundSource.NEUTRAL, 1f, 1f);
        String message = switch (prepared.landing()) {
            case HOME -> "cyurevive.revive.home";
            case OWNER_RECALL -> "cyurevive.revive.recalled";
            case OWNER_FALLBACK -> "cyurevive.revive.fallback";
        };
        owner.sendSystemMessage(ok(message, name(pet.getDisplayName())));
        return PetRevival.Result.COMPLETED;
    }

    private static void snapshotFailure(PetWorldData data, UUID petId, PendingRevival pending,
                                        ServerPlayer owner, String reason) {
        data.revivalPhase(petId, PetRevival.Phase.UNREADABLE);
        data.setDirty();
        if (!data.tasks.invalidSnapshot(petId)) return;
        CyuRevive.LOGGER.error("宠物 {} 的复活快照无效，原始数据已保留：{}", petId, reason);
        owner.sendSystemMessage(err("cyurevive.revive.invalid_snapshot", name(nameText(pending.name()))));
    }

    public static void recallCommand(ServerPlayer player) {
        PetConfig config = CyuRevive.config;
        if (!config.commandRecall) {
            player.sendSystemMessage(info("cyurevive.recall.disabled"));
            return;
        }
        MinecraftServer server = player.getServer();
        PetWorldData data = PetWorldData.get(server);
        if (data.bannedOwners.contains(player.getUUID())) {
            player.sendSystemMessage(err("cyurevive.admin.banned_you"));
            return;
        }
        List<UUID> waiting = new ArrayList<>();
        for (UUID petId : data.owned(player.getUUID())) {
            if (data.pending.containsKey(petId)) waiting.add(petId);
        }
        if (waiting.isEmpty()) {
            player.sendSystemMessage(info("cyurevive.recall.empty"));
            return;
        }
        List<UUID> callable = new ArrayList<>();
        for (UUID petId : waiting) {
            Arrival arrival = data.arriving.get(petId);
            if (arrival == null || arrival.destination() != PetRevival.Destination.OWNER
                || arrival.origin() != PetRevival.Origin.MANUAL
                || data.pending.get(petId).phase() == PetRevival.Phase.UNREADABLE) callable.add(petId);
        }
        if (callable.isEmpty()) {
            player.sendSystemMessage(info("cyurevive.recall.coming_already"));
            return;
        }
        requestRecall(player, data, callable, PetRevival.Destination.OWNER);
    }

    private static void requestRecall(ServerPlayer player, PetWorldData data, List<UUID> pets,
                                      PetRevival.Destination destination) {
        MinecraftServer server = player.level().getServer();
        List<UUID> ready = new ArrayList<>();
        for (UUID pet : pets) {
            data.retryRevival(pet);
            if (recallReady(data, server, pet, destination)) ready.add(pet);
        }
        if (ready.isEmpty()) {
            player.sendSystemMessage(err("cyurevive.recall.blocked"));
            return;
        }
        PetRevival.Charge charge = ready.stream().anyMatch(pet -> {
            Arrival previous = data.arriving.get(pet);
            return previous == null || previous.origin() != PetRevival.Origin.MANUAL;
        }) ? PetRevival.Charge.REQUIRED : PetRevival.Charge.ALREADY_PAID;
        if (charge == PetRevival.Charge.REQUIRED && !payRecallCost(player, CyuRevive.config)) return;
        long now = server.overworld().getGameTime();
        long due = now + (CyuRevive.config.reviveCeremony ? 40 : 0);
        for (UUID pet : ready) {
            data.arriving.put(pet, new Arrival(due, destination, PetRevival.Origin.MANUAL));
            data.tasks.schedule(pet, PetTasks.Kind.ARRIVE, due);
            activateWaiting(server, data, pet);
        }
        data.setDirty();
        player.sendSystemMessage(ok("cyurevive.recall.coming", value(ready.size())));
    }

    private static boolean recallReady(PetWorldData data, MinecraftServer server, UUID petId,
                                       PetRevival.Destination destination) {
        PendingRevival pending = data.pending.get(petId);
        if (pending == null || data.bannedOwners.contains(pending.owner())) return false;
        if (findPet(server, petId) != null) {
            data.revivalBlockage(petId, PetRevival.Blockage.ENTITY_PRESENT);
            return false;
        }
        return switch (PetRevival.prepare(pending, server, destination)) {
            case PetRevival.Invalid invalid -> {
                snapshotFailure(data, petId, pending, server.getPlayerList().getPlayer(pending.owner()), invalid.reason());
                yield false;
            }
            case PetRevival.Delayed delayed -> {
                data.revivalBlockage(petId, blockage(delayed.reason()));
                yield false;
            }
            case PetRevival.Prepared prepared -> true;
        };
    }

    public static void nameHome(ServerPlayer player, String homeName) {
        String residenceName = homeName.strip();
        if (residenceName.isBlank() || residenceName.codePointCount(0, residenceName.length()) > 32
            || residenceName.codePoints().anyMatch(Character::isISOControl)) {
            player.sendSystemMessage(err("cyurevive.home.bad_name"));
            return;
        }
        HitResult hit = player.pick(6, 0, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            player.sendSystemMessage(err("cyurevive.home.look_at_bed"));
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        var state = level.getBlockState(blockHit.getBlockPos());
        if (!(state.getBlock() instanceof PetBedBlock block)) {
            player.sendSystemMessage(err("cyurevive.home.look_at_bed"));
            return;
        }
        BedLoc bed = new BedLoc(level.dimension(), block.center(blockHit.getBlockPos(), state));
        PetWorldData data = PetWorldData.get(level.getServer());
        if (!player.getUUID().equals(data.bedOwners.get(bed)) || !block.complete(level, bed.pos())) {
            player.sendSystemMessage(err("cyurevive.home.not_owner"));
            return;
        }
        data.homeNames.put(bed, residenceName);
        data.setDirty();
        player.sendSystemMessage(ok("cyurevive.home.named", value(residenceName)));
    }

    private static PetRevival.Blockage blockage(PetRevival.Delay delay) {
        return switch (delay) {
            case OWNER_OFFLINE -> PetRevival.Blockage.OWNER_OFFLINE;
            case CHUNK_UNLOADED -> PetRevival.Blockage.CHUNK_UNLOADED;
            case SPACE_BLOCKED -> PetRevival.Blockage.SPACE_BLOCKED;
        };
    }

    private static Component waitingStatus(MinecraftServer server, PetWorldData data, UUID pet,
                                           PendingRevival pending, long now) {
        PetProgress.Status progress = PetProgress.status(server, pending, data.arriving.get(pet), now);
        Component destination = info(progress.destination() == PetRevival.Destination.HOME
            ? "cyurevive.progress.destination.home" : "cyurevive.progress.destination.owner");
        return switch (progress) {
            case PetProgress.Timed timed -> info(switch (timed.timer()) {
                case COOLDOWN -> "cyurevive.progress.cooldown";
                case CEREMONY -> "cyurevive.progress.ceremony";
            }, value(timed.seconds()), destination);
            case PetProgress.Waiting waiting -> info(switch (waiting.reason()) {
                case QUEUED -> "cyurevive.progress.queued";
                case AUTOMATIC_DISABLED -> "cyurevive.progress.automatic_disabled";
                case OWNER_OFFLINE -> "cyurevive.progress.owner_offline";
                case CHUNK_UNLOADED -> "cyurevive.progress.chunk_unloaded";
                case CHUNK_UNLOADED_RECALL_AVAILABLE -> "cyurevive.progress.chunk_unloaded_recall";
                case SPACE_BLOCKED -> "cyurevive.progress.space_blocked";
                case ENTITY_PRESENT -> "cyurevive.progress.entity_present";
                case INSERTION_REFUSED -> "cyurevive.progress.insertion_refused";
                case INVALID_DATA -> "cyurevive.progress.invalid_data";
            }, destination);
        };
    }

    public static void sendPetList(CommandSourceStack source, UUID owner, String ownerName) {
        MinecraftServer server = source.getServer();
        PetWorldData data = PetWorldData.get(server);
        long now = server.overworld().getGameTime();
        List<Component> lines = new ArrayList<>();
        int index = 1;
        for (UUID petId : ownedPetIds(data, server, owner)) {
            PendingRevival pending = data.pending.get(petId);
            HomeRecord home = data.homes.get(petId);
            Entity live = findPet(server, petId);
            Component petName = name(live != null ? live.getName() : nameText(petLabel(data, petId)));
            Component tail;
            BedLoc spot;
            Component hover;
            if (pending != null) {
                tail = waitingStatus(server, data, petId, pending, now);
                spot = pending.diedAt();
                hover = Component.empty()
                    .append(info("cyurevive.list.hover.last_full", value(posText(pending.diedAt())), value(dimensionLabel(pending.diedAt()))))
                    .append(Component.literal("\n"))
                    .append(info("cyurevive.list.hover.recorded_home", value(posText(pending.bed())), value(dimensionLabel(pending.bed()))));
            } else if (data.downed.containsKey(petId)) {
                DownedPet injury = data.downed.get(petId);
                tail = info("cyurevive.list.downed", value(PetProgress.secondsUntil(injury.deadline(), now)));
                spot = live != null ? new BedLoc(live.level().dimension(), live.blockPosition()) : null;
                hover = spot != null
                    ? info("cyurevive.list.hover.downed", value(posText(spot)), dimSuffix(spot))
                    : info("cyurevive.list.downed", value(PetProgress.secondsUntil(injury.deadline(), now)));
            } else {
                tail = info(home.availability() == ResidenceAvailability.PRESENT ? "cyurevive.list.home" : "cyurevive.list.missing_home",
                    value(posText(home.bed())), dimSuffix(home.bed()));
                spot = home.bed();
                hover = info("cyurevive.list.hover.home", value(posText(home.bed())), dimSuffix(home.bed()));
            }
            MutableComponent lead = Component.literal(index + ". ")
                .withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT)).append(petName);
            lead.withStyle(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
            if (spot != null) {
                lead.withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                    "/tp @s " + spot.pos().getX() + " " + spot.pos().getY() + " " + spot.pos().getZ())));
            }
            BedLoc residence = home != null ? home.bed() : pending != null ? pending.bed() : null;
            String group = residence == null ? null : data.homeNames.get(residence);
            if (group != null) tail = Component.empty().append(tail).append(info("cyurevive.home.suffix", value(group)));
            lines.add(line(lead, tail));
            index++;
        }
        if (lines.isEmpty()) {
            source.sendSystemMessage(ownerName == null
                ? info("cyurevive.list.empty")
                : info("cyurevive.admin.no_pets", value(ownerName)));
            return;
        }
        source.sendSystemMessage(ownerName == null
            ? header("cyurevive.list.header")
            : header("cyurevive.admin.list_header", value(ownerName)));
        lines.forEach(source::sendSystemMessage);
        source.sendSystemMessage(info("cyurevive.list.footer", value(lines.size())));
    }

    public static void unbindPet(CommandSourceStack source, UUID owner, String ownerName, int index, boolean self) {
        PetWorldData data = PetWorldData.get(source.getServer());
        List<UUID> owned = ownedPetIds(data, source.getServer(), owner);
        if (owned.isEmpty()) {
            source.sendFailure(self
                ? info("cyurevive.list.empty")
                : err("cyurevive.admin.no_pets", value(ownerName)));
            return;
        }
        if (index > owned.size()) {
            source.sendFailure(err("cyurevive.admin.unbind_bad_index", value(owned.size())));
            return;
        }
        UUID petId = owned.get(index - 1);
        HomeRecord home = data.homes.get(petId);
        PendingRevival pending = data.pending.get(petId);
        if (home == null && pending == null) {
            source.sendFailure(err("cyurevive.unbind.downed"));
            return;
        }
        data.removeHome(petId);
        data.removePending(petId);
        data.arriving.remove(petId);
        data.setDirty();
        String petName = home != null ? home.name() : pending.name();
        String key = self
            ? (pending != null ? "cyurevive.unbind.self_pending" : "cyurevive.unbind.self")
            : (pending != null ? "cyurevive.admin.unbind_pending" : "cyurevive.admin.unbind_done");
        if (self) {
            source.sendSuccess(() -> ok(key, name(nameText(petName))), true);
        } else {
            audit(source, "unbind", ownerName + " 的宠物 " + petName);
            source.sendSuccess(() -> ok(key, value(ownerName), name(nameText(petName))), true);
        }
    }

    public static void forbidPlayer(CommandSourceStack source, UUID owner, String ownerName) {
        PetWorldData data = PetWorldData.get(source.getServer());
        if (!data.bannedOwners.add(owner)) {
            source.sendFailure(err("cyurevive.admin.forbid_already", value(ownerName)));
            return;
        }
        clearWaiting(data, owner);
        data.setDirty();
        audit(source, "forbid", ownerName);
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(owner);
        if (online != null) online.sendSystemMessage(err("cyurevive.admin.forbidden"));
        source.sendSuccess(() -> ok("cyurevive.admin.forbid_done", value(ownerName)), true);
    }

    public static void unforbidPlayer(CommandSourceStack source, UUID owner, String ownerName) {
        PetWorldData data = PetWorldData.get(source.getServer());
        if (!data.bannedOwners.remove(owner)) {
            source.sendFailure(err("cyurevive.admin.unforbid_not", value(ownerName)));
            return;
        }
        data.setDirty();
        audit(source, "unforbid", ownerName);
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(owner);
        if (online != null) online.sendSystemMessage(ok("cyurevive.admin.unbanned"));
        source.sendSuccess(() -> ok("cyurevive.admin.unforbid_done", value(ownerName)), true);
    }

    public static void purgePlayer(CommandSourceStack source, UUID owner, String ownerName) {
        PetWorldData data = PetWorldData.get(source.getServer());
        int purged = clearWaiting(data, owner);
        if (purged == 0) {
            source.sendFailure(err("cyurevive.admin.purge_empty", value(ownerName)));
            return;
        }
        data.setDirty();
        audit(source, "purge", ownerName + " ×" + purged);
        source.sendSuccess(() -> ok("cyurevive.admin.purge_done", value(ownerName), value(purged)), true);
    }

    private static int clearWaiting(PetWorldData data, UUID owner) {
        int count = 0;
        for (UUID petId : new ArrayList<>(data.owned(owner))) {
            if (data.removePending(petId) == null) continue;
            data.arriving.remove(petId);
            count++;
        }
        return count;
    }

    public static void reviveAll(CommandSourceStack source, UUID owner, String ownerName) {
        MinecraftServer server = source.getServer();
        PetWorldData data = PetWorldData.get(server);
        List<UUID> waiting = new ArrayList<>();
        for (UUID petId : data.owned(owner)) {
            PendingRevival pending = data.pending.get(petId);
            if (pending != null && canReviveNow(server, pending)) waiting.add(petId);
        }
        if (waiting.isEmpty()) {
            source.sendFailure(err("cyurevive.admin.revive_empty", value(ownerName)));
            return;
        }
        long now = server.overworld().getGameTime();
        for (UUID petId : waiting) {
            data.retryRevival(petId);
            Arrival previous = data.arriving.get(petId);
            PetRevival.Origin origin = previous != null && previous.origin() == PetRevival.Origin.MANUAL
                ? PetRevival.Origin.MANUAL : PetRevival.Origin.ADMIN;
            data.arriving.put(petId, new Arrival(now, PetRevival.Destination.HOME, origin));
            data.tasks.schedule(petId, PetTasks.Kind.ARRIVE, now);
            activateWaiting(server, data, petId);
        }
        data.setDirty();
        audit(source, "revive", ownerName + " ×" + waiting.size());
        source.sendSuccess(() -> ok("cyurevive.admin.revive_done", value(ownerName), value(waiting.size())), true);
    }

    public static void sendStatus(CommandSourceStack source) {
        PetWorldData data = PetWorldData.get(source.getServer());
        source.sendSuccess(() -> header("cyurevive.status.header"), false);
        source.sendSuccess(() -> status("cyurevive.status.homes", data.homes.size()), false);
        source.sendSuccess(() -> Component.literal("› ").withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT))
            .append(info("cyurevive.status.pending", value(data.pending.size()), value(data.arriving.size()))), false);
        source.sendSuccess(() -> status("cyurevive.status.downed", data.downed.size()), false);
        source.sendSuccess(() -> status("cyurevive.status.banned", data.bannedOwners.size()), false);
        source.sendSuccess(() -> Component.literal("› ").withStyle(style -> style.withColor(CyuRevive.TEXT_BRIGHT))
            .append(info("cyurevive.status.config.capacity", value(CyuRevive.config.bedCapacity)))
            .append(Component.literal(" | ").withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED)))
            .append(info("cyurevive.status.config.cooldown", value(CyuRevive.config.reviveCooldownSeconds)))
            .append(Component.literal(" | ").withStyle(style -> style.withColor(CyuRevive.TEXT_MUTED)))
            .append(info("cyurevive.status.config.types", value(CyuRevive.config.revivableTypes.size()))), false);
    }

    public static void doctor(CommandSourceStack source, boolean fix) {
        MinecraftServer server = source.getServer();
        PetWorldData data = PetWorldData.get(server);
        List<UUID> dual = new ArrayList<>();
        List<UUID> orphanHomes = new ArrayList<>();
        int dimMissing = 0;
        for (Map.Entry<UUID, HomeRecord> e : data.homes.entrySet()) {
            if (data.pending.containsKey(e.getKey())) dual.add(e.getKey());
            ServerLevel level = server.getLevel(e.getValue().bed().dim());
            if (level == null) dimMissing++;
            else if (e.getValue().availability() == ResidenceAvailability.PRESENT && level.hasChunkAt(e.getValue().bed().pos())
                && !CyuRevive.isPetBed(level.getBlockState(e.getValue().bed().pos()))) orphanHomes.add(e.getKey());
        }
        List<UUID> badSnapshots = new ArrayList<>();
        for (Map.Entry<UUID, PendingRevival> e : data.pending.entrySet()) {
            if (server.getLevel(e.getValue().bed().dim()) == null) dimMissing++;
            if (e.getValue().phase() == PetRevival.Phase.UNREADABLE || e.getValue().snapshot().getString("id").isEmpty()) badSnapshots.add(e.getKey());
        }
        List<UUID> orphanArrivals = new ArrayList<>();
        for (UUID petId : data.arriving.keySet()) {
            if (!data.pending.containsKey(petId)) orphanArrivals.add(petId);
        }
        List<BedLoc> orphanBeds = new ArrayList<>();
        for (Map.Entry<BedLoc, UUID> e : data.bedOwners.entrySet()) {
            ServerLevel level = server.getLevel(e.getKey().dim());
            if (level != null && level.hasChunkAt(e.getKey().pos()) && !CyuRevive.isPetBed(level.getBlockState(e.getKey().pos()))) orphanBeds.add(e.getKey());
        }
        int fixable = dual.size() + orphanHomes.size() + orphanArrivals.size() + orphanBeds.size();
        int total = fixable + dimMissing + badSnapshots.size();
        if (total == 0) {
            source.sendSuccess(() -> ok("cyurevive.doctor.ok"), false);
            return;
        }
        if (fix) {
            int fixed = 0;
            for (UUID petId : dual) {
                if (findPet(server, petId) != null) {
                    data.removePending(petId);
                    data.arriving.remove(petId);
                } else {
                    data.removeHome(petId);
                }
                fixed++;
            }
            for (UUID petId : orphanHomes) {
                HomeRecord home = data.homes.get(petId);
                if (home == null) continue;
                data.residenceAvailability(home.bed(), ResidenceAvailability.MISSING);
                fixed++;
            }
            for (UUID petId : orphanArrivals) {
                data.arriving.remove(petId);
                fixed++;
            }
            for (BedLoc bed : orphanBeds) {
                data.bedOwners.remove(bed);
                fixed++;
            }
            data.setDirty();
            audit(source, "doctor fix", "共修复 " + fixed + " 项");
            int count = fixed;
            source.sendSuccess(() -> count > 0
                ? ok("cyurevive.doctor.fixed", value(count))
                : info("cyurevive.doctor.nothing"), true);
            return;
        }
        source.sendSuccess(() -> header("cyurevive.doctor.header"), false);
        if (!dual.isEmpty()) source.sendSuccess(() -> status("cyurevive.doctor.dual", dual.size()), false);
        if (!orphanHomes.isEmpty()) source.sendSuccess(() -> status("cyurevive.doctor.orphan_home", orphanHomes.size()), false);
        if (!badSnapshots.isEmpty()) source.sendSuccess(() -> status("cyurevive.doctor.snapshot_bad", badSnapshots.size()), false);
        if (!orphanArrivals.isEmpty()) source.sendSuccess(() -> status("cyurevive.doctor.arriving_orphan", orphanArrivals.size()), false);
        if (!orphanBeds.isEmpty()) source.sendSuccess(() -> status("cyurevive.doctor.bed_orphan", orphanBeds.size()), false);
        int missing = dimMissing;
        if (missing > 0) source.sendSuccess(() -> status("cyurevive.doctor.dim_missing", missing), false);
        int count = total;
        source.sendSuccess(() -> info("cyurevive.doctor.fix_hint", value(count)), false);
    }

    private static void audit(CommandSourceStack source, String action, String detail) {
        CyuRevive.LOGGER.info("{} 执行 /cyurevive {}：{}", source.getTextName(), action, detail);
    }

    private static SoundEvent downedSound(Animal pet) {
        return switch (BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).getPath()) {
            case "cat" -> SoundEvents.CAT_HURT;
            case "parrot" -> SoundEvents.PARROT_HURT;
            case "donkey" -> SoundEvents.DONKEY_HURT;
            case "mule" -> SoundEvents.MULE_HURT;
            case "llama", "trader_llama" -> SoundEvents.LLAMA_HURT;
            case "skeleton_horse" -> SoundEvents.SKELETON_HORSE_HURT;
            case "zombie_horse" -> SoundEvents.ZOMBIE_HORSE_HURT;
            case "horse" -> SoundEvents.HORSE_HURT;
            default -> SoundEvents.WOLF_WHINE;
        };
    }

    private static SoundEvent reviveSound(Entity entity) {
        return switch (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()) {
            case "minecraft:cat" -> SoundEvents.CAT_AMBIENT;
            case "minecraft:parrot" -> SoundEvents.PARROT_AMBIENT;
            case "minecraft:fox" -> SoundEvents.FOX_AMBIENT;
            case "minecraft:donkey" -> SoundEvents.DONKEY_AMBIENT;
            case "minecraft:mule" -> SoundEvents.MULE_AMBIENT;
            case "minecraft:llama", "minecraft:trader_llama" -> SoundEvents.LLAMA_AMBIENT;
            case "minecraft:skeleton_horse" -> SoundEvents.SKELETON_HORSE_AMBIENT;
            case "minecraft:zombie_horse" -> SoundEvents.ZOMBIE_HORSE_AMBIENT;
            case "minecraft:horse" -> SoundEvents.HORSE_AMBIENT;
            case "minecraft:wolf" -> SoundEvents.WOLF_AMBIENT;
            default -> SoundEvents.UI_BUTTON_CLICK.value();
        };
    }

    private static List<UUID> ownedPetIds(PetWorldData data, MinecraftServer server, UUID owner) {
        List<UUID> ids = new ArrayList<>(data.owned(owner));
        for (UUID petId : data.downed.keySet()) {
            if (ids.contains(petId)) continue;
            Entity live = findPet(server, petId);
            if (live instanceof Animal pet && owner.equals(petOwnerId(pet))) ids.add(petId);
        }
        ids.sort(Comparator.comparing((UUID id) -> petLabel(data, id), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(UUID::toString));
        return ids;
    }

    private static String petLabel(PetWorldData data, UUID petId) {
        HomeRecord home = data.homes.get(petId);
        if (home != null && !home.name().isEmpty()) return home.name();
        PendingRevival pending = data.pending.get(petId);
        return pending != null ? pending.name() : "";
    }

    private static Component dimensionLabel(BedLoc location) {
        if (location.dim().equals(Level.OVERWORLD)) return info("cyurevive.dimension.overworld");
        if (location.dim().equals(Level.NETHER)) return info("cyurevive.dimension.nether");
        if (location.dim().equals(Level.END)) return info("cyurevive.dimension.end");
        return Component.literal(location.dim().location().toString());
    }

    private static String dimSuffix(BedLoc bed) {
        return bed.dim().equals(Level.OVERWORLD) ? "" : " (" + bed.dim().location().getPath() + ")";
    }

    private static boolean canReviveNow(MinecraftServer server, PendingRevival pending) {
        if (server.getPlayerList().getPlayer(pending.owner()) == null) return false;
        ServerLevel level = server.getLevel(pending.bed().dim());
        return pending.availability() == ResidenceAvailability.MISSING || level == null || level.hasChunkAt(pending.bed().pos());
    }

    private static Component rescueItemName(Animal pet) {
        Item preferred = switch (BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).toString()) {
            case "minecraft:wolf" -> Items.BONE;
            case "minecraft:cat" -> Items.COD;
            case "minecraft:parrot" -> Items.WHEAT_SEEDS;
            case "minecraft:fox" -> Items.SWEET_BERRIES;
            case "minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:llama", "minecraft:trader_llama",
                 "minecraft:skeleton_horse", "minecraft:zombie_horse" -> Items.HAY_BLOCK;
            default -> null;
        };
        if (preferred != null && (CyuRevive.config.downedItemSet.contains(preferred) || pet.isFood(preferred.getDefaultInstance()))) {
            return Component.translatable(preferred.getDescriptionId());
        }
        if (!CyuRevive.config.downedItems.isEmpty()) {
            Item configured = BuiltInRegistries.ITEM.getOptional(CyuRevive.config.downedItems.getFirst()).orElseThrow();
            return Component.translatable(configured.getDescriptionId());
        }
        return Component.translatable("cyurevive.downed.anyfood");
    }

    static UUID petOwnerId(Animal pet) {
        return PetRevival.ownerOf(pet);
    }

    private static boolean isOwner(Animal pet, Player player) {
        UUID owner = petOwnerId(pet);
        return owner != null && owner.equals(player.getUUID());
    }

    private static boolean isRevivable(Animal pet) {
        return petOwnerId(pet) != null
            && CyuRevive.config.revivableTypes.contains(BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()));
    }

    private static void killDowned(Animal pet) {
        pet.setNoAi(false);
        pet.setInvulnerable(false);
        pet.hurt(pet.damageSources().genericKill(), Float.MAX_VALUE);
    }

    private static boolean payRecallCost(Player player, PetConfig config) {
        ResourceLocation costItem = config.recallItem;
        if (costItem == null || consumeItem(player, costItem, config.recallItemCount)) return true;
        Item item = BuiltInRegistries.ITEM.getOptional(costItem).orElse(null);
        Component label = item != null
            ? Component.translatable(item.getDescriptionId())
            : Component.literal(costItem.toString());
        player.sendSystemMessage(err("cyurevive.recall.cost", name(label), value(config.recallItemCount)));
        return false;
    }

    private static boolean consumeItem(Player player, ResourceLocation itemId, int count) {
        if (player.isCreative()) return true;
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) return false;
        long available = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) available += stack.getCount();
        }
        ItemStack offhand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (offhand.is(item)) available += offhand.getCount();
        if (available < count) return false;
        int remaining = count;
        for (ItemStack stack : player.getInventory().items) {
            if (!stack.is(item)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            remaining -= take;
            if (remaining == 0) return true;
        }
        offhand.shrink(remaining);
        return true;
    }

    private static Entity findPet(MinecraftServer server, UUID uuid) {
        return PetWorldData.get(server).tasks.loadedEntity(server, uuid);
    }

    private static void hearts(ServerLevel level, Entity entity) {
        level.sendParticles(ParticleTypes.HEART, entity.getX(), entity.getY() + 1.0, entity.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
    }

    private static Component posText(BedLoc bed) {
        return Component.literal("[" + bed.pos().getX() + ", " + bed.pos().getY() + ", " + bed.pos().getZ() + "]");
    }

    private static Component nameText(String name) {
        return name.isEmpty() ? petNameFallback() : Component.literal(name);
    }

    private static Component petNameFallback() {
        return Component.translatable("cyurevive.pet.unknown");
    }
}
