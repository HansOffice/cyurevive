package org.cyu.cyurevive;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;

public final class PetStructures {
    private PetStructures() { }

    public static void remove(ServerLevel level, BlockPos position, BlockState state) {
        PetBedBlock block = (PetBedBlock) state.getBlock();
        BlockPos center = block.center(position, state);
        PetWorldData data = PetWorldData.get(level.getServer());
        BedLoc bed = new BedLoc(level.dimension(), center);
        if (!data.beginDismantling(bed, new BrokenStructure(block.kind(), state.getValue(PetBedBlock.FACING), position.immutable()))) return;
        data.setDirty();
        PetService.onBedRemoved(level, center);
        clear(level, bed, data.brokenStructures.get(bed));
    }

    public static void onChunkLoad(ServerLevel level, int chunkX, int chunkZ) {
        PetWorldData data = PetWorldData.get(level.getServer());
        for (BedLoc bed : new ArrayList<>(data.dismantlingInChunk(level.dimension(), chunkX, chunkZ))) {
            clear(level, bed, data.brokenStructures.get(bed));
        }
        PetService.onFacilityChunkLoaded(level, data, chunkX, chunkZ);
    }

    private static void clear(ServerLevel level, BedLoc bed, BrokenStructure structure) {
        PetBedBlock block = CyuRevive.facility(structure.kind());
        BlockState anchor = block.defaultBlockState().setValue(PetBedBlock.FACING, structure.facing());
        boolean fullyLoaded = true;
        for (int part = 0; part < block.kind().layout().count(); part++) {
            BlockPos position = block.partPosition(bed.pos(), anchor, part);
            if (!level.hasChunkAt(position)) {
                fullyLoaded = false;
                continue;
            }
            if (level.getBlockState(position).equals(block.withPart(anchor, part))) {
                level.removeBlock(position, false);
            }
        }
        if (fullyLoaded) {
            PetWorldData data = PetWorldData.get(level.getServer());
            data.finishDismantling(bed);
        }
    }
}
