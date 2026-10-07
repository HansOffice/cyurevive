package org.cyu.cyurevive;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public final class PetBedItem extends BlockItem {
    public PetBedItem(PetBedBlock block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        if (!super.canPlace(context, state)) return false;
        PetBedBlock block = (PetBedBlock) getBlock();
        Level level = context.getLevel();
        BlockPos center = context.getClickedPos();
        int count = block.kind().layout().count();
        for (int part = 0; part < count; part++) {
            BlockPos position = block.partPosition(center, state, part);
            if (!spaceAvailable(level, position, context.getPlayer(), block.kind().layout().offset(part).getY() == 0)) return denied(context);
            if (block.kind().layout().offset(part).getY() != 0) continue;
            AABB volume = new AABB(position).setMaxY(position.getY() + block.kind().placementHeight());
            if (!level.isInWorldBounds(position.above(block.kind().placementHeight() - 1))
                || !PetResidences.insideBorder(level, volume) || level.containsAnyLiquid(volume)
                || !level.noCollision(volume) || !level.getEntities((net.minecraft.world.entity.Entity) null, volume, entity -> entity.isAlive()).isEmpty()) return denied(context);
        }
        return true;
    }

    private static boolean spaceAvailable(Level level, BlockPos position, Player player, boolean ground) {
        return level.hasChunkAt(position) && level.isInWorldBounds(position)
            && level.getWorldBorder().isWithinBounds(position)
            && level.getBlockState(position).isAir()
            && level.getFluidState(position).isEmpty()
            && (!ground || level.getBlockState(position.below()).isFaceSturdy(level, position.below(), net.minecraft.core.Direction.UP))
            && (player == null || level.mayInteract(player, position));
    }

    private static boolean denied(BlockPlaceContext context) {
        if (context.getPlayer() != null && !context.getLevel().isClientSide()) {
            context.getPlayer().sendSystemMessage(Component.translatable("cyurevive.place.no_space")
                .withColor(CyuRevive.TEXT_ERROR));
        }
        return false;
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        PetBedBlock block = (PetBedBlock) getBlock();
        Level level = context.getLevel();
        BlockPos center = context.getClickedPos();
        List<BlockPos> placed = new ArrayList<>(block.kind().layout().count());
        for (int part = 0; part < block.kind().layout().count(); part++) {
            BlockPos position = block.partPosition(center, state, part);
            if (!level.setBlock(position, block.withPart(state, part), Block.UPDATE_CLIENTS)) {
                for (BlockPos rollback : placed) level.removeBlock(rollback, false);
                return false;
            }
            placed.add(position);
        }
        for (BlockPos position : placed) level.updateNeighborsAt(position, block);
        if (block.complete(level, center)) return true;
        for (int part = 0; part < block.kind().layout().count(); part++) {
            BlockPos position = block.partPosition(center, state, part);
            if (level.getBlockState(position).equals(block.withPart(state, part))) level.removeBlock(position, false);
        }
        return false;
    }
}
