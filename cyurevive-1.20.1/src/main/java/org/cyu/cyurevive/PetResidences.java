package org.cyu.cyurevive;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class PetResidences {
    private static final int OWNER_SEARCH_RADIUS = 3;
    private static final java.util.List<BlockPos> OWNER_OFFSETS = java.util.stream.IntStream
        .rangeClosed(-OWNER_SEARCH_RADIUS, OWNER_SEARCH_RADIUS).boxed()
        .flatMap(x -> java.util.stream.IntStream.rangeClosed(-OWNER_SEARCH_RADIUS, OWNER_SEARCH_RADIUS)
            .mapToObj(z -> new BlockPos(x, 0, z)))
        .filter(offset -> !offset.equals(BlockPos.ZERO))
        .sorted(java.util.Comparator.comparingInt(offset -> Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ()))))
        .toList();
    private PetResidences() { }

    public static int freeSlot(PetWorldData data, BedLoc bed, java.util.UUID candidate, PetBedBlock block) {
        int capacity = Math.min(CyuRevive.config.bedCapacity, block.kind().capacity());
        for (int slot = 0; slot < capacity; slot++) {
            if (!occupied(data, bed, slot, candidate)) return slot;
        }
        return -1;
    }

    private static boolean occupied(PetWorldData data, BedLoc bed, int slot, java.util.UUID except) {
        for (java.util.UUID pet : data.residents(bed)) {
            if (pet.equals(except)) continue;
            HomeRecord home = data.homes.get(pet);
            PendingRevival waiting = data.pending.get(pet);
            if (home != null && home.slot() == slot || waiting != null && waiting.slot() == slot) return true;
        }
        return false;
    }

    public static Vec3 position(ServerLevel level, BedLoc bed, int slot) {
        if (!level.hasChunkAt(bed.pos())) return null;
        BlockState state = level.getBlockState(bed.pos());
        if (!(state.getBlock() instanceof PetBedBlock block) || !block.complete(level, bed.pos())) return null;
        if (slot < 0 || slot >= block.kind().capacity()) return null;
        Vec3 offset = block.kind().slot(slot, state.getValue(PetBedBlock.FACING));
        return new Vec3(bed.pos().getX() + 0.5 + offset.x, bed.pos().getY() + block.settleY(), bed.pos().getZ() + 0.5 + offset.z);
    }

    public static boolean insideBorder(net.minecraft.world.level.Level level, AABB bounds) {
        var border = level.getWorldBorder();
        return bounds.minX >= border.getMinX() && bounds.maxX <= border.getMaxX()
            && bounds.minZ >= border.getMinZ() && bounds.maxZ <= border.getMaxZ();
    }

    public static boolean safe(ServerLevel level, Entity pet, Vec3 position) {
        AABB bounds = pet.getBoundingBox().move(position.subtract(pet.position()));
        BlockPos minimum = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos maximum = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        return level.hasChunksAt(minimum, maximum) && insideBorder(level, bounds)
            && level.isInWorldBounds(minimum) && level.isInWorldBounds(maximum)
            && !level.containsAnyLiquid(bounds) && level.noCollision(pet, bounds)
            && level.getEntities(pet, bounds, entity -> entity instanceof net.minecraft.world.entity.LivingEntity && entity.isAlive()).isEmpty();
    }

    public static boolean safeApproach(ServerLevel level, Entity pet, Vec3 target) {
        AABB swept = pet.getBoundingBox().minmax(pet.getBoundingBox().move(target.subtract(pet.position())))
            .setMinY(Math.max(pet.getY(), target.y)).deflate(0.001);
        return safe(level, pet, target) && level.noCollision(pet, swept);
    }

    public static Vec3 ownerPosition(ServerLevel level, Entity pet, BlockPos owner) {
        for (BlockPos offset : OWNER_OFFSETS) {
            Vec3 position = supportedPosition(level, pet, owner.offset(offset));
            if (position != null) return position;
        }
        return null;
    }

    private static Vec3 supportedPosition(ServerLevel level, Entity pet, BlockPos floor) {
        for (int y = 0; y <= 2; y++) {
            BlockPos feet = floor.above(y);
            if (!level.hasChunkAt(feet) || !level.getBlockState(feet.below())
                .isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) continue;
            Vec3 position = new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
            if (safe(level, pet, position)) return position;
        }
        return null;
    }

    public static boolean settle(Animal pet, BedLoc bed, int slot) {
        if (!(pet.level() instanceof ServerLevel level) || !level.dimension().equals(bed.dim())) return false;
        return ((PetHomeAccess) pet).cyurevive$homeGoal().request(bed, slot, PetHomeGoal.Intent.CALL) == PetHomeGoal.Outcome.ARRIVED;
    }
}
