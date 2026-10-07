package org.cyu.cyurevive;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import java.util.List;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class PetBedBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final int CENTER = 0;

    public PetBedBlock(BedKind kind) {
        super(properties(kind));
        registerDefaultState(withPart(stateDefinition.any().setValue(FACING, Direction.NORTH), CENTER));
    }

    private static BlockBehaviour.Properties properties(BedKind kind) {
        return BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.5F)
            .sound(SoundType.WOOL).noOcclusion().pushReaction(PushReaction.BLOCK);
    }

    public abstract BedKind kind();
    public int part(BlockState state) {
        return kind().layout().count() == 1 ? CENTER : state.getValue(kind().layout().parts()).ordinal();
    }
    public BlockState withPart(BlockState state, int part) {
        if (kind().layout().count() == 1 && part == CENTER) return state;
        return state.setValue(kind().layout().parts(), FacilityPart.at(part));
    }
    public String bedName() { return kind().id(); }
    public String species() { return kind().species(); }
    public double settleY() { return kind().height(); }
    public boolean accepts(Animal pet) { return kind().accepts(pet); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        if (kind().layout().count() > 1) builder.add(kind().layout().parts());
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind().layout().shape(part(state), state.getValue(FACING));
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder context) {
        ServerLevel level = context.getLevel();
        BlockPos position = BlockPos.containing(context.getParameter(LootContextParams.ORIGIN));
        BedLoc bed = new BedLoc(level.dimension(), center(position, state));
        BrokenStructure dismantling = PetWorldData.get(level.getServer()).brokenStructures.get(bed);
        return dismantling != null && !dismantling.dropOrigin().equals(position)
            ? List.of() : super.getDrops(state, context);
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return type == net.minecraft.world.level.pathfinder.PathComputationType.LAND && kind().layout().walkable(part(state));
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) { return true; }

    public BlockPos partPosition(BlockPos center, BlockState state, int part) {
        return kind().layout().position(center, part, state.getValue(FACING));
    }

    public BlockPos center(BlockPos position, BlockState state) {
        BlockPos offset = partPosition(BlockPos.ZERO, state, part(state));
        return position.subtract(offset);
    }

    public boolean loaded(Level level, BlockPos center, BlockState state) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int part = 0; part < kind().layout().count(); part++) {
            kind().layout().position(cursor, center, part, state.getValue(FACING));
            if (!level.hasChunkAt(cursor)) return false;
        }
        return true;
    }

    public boolean complete(Level level, BlockPos center) {
        if (!level.hasChunkAt(center)) return false;
        BlockState anchor = level.getBlockState(center);
        if (!anchor.is(this) || part(anchor) != CENTER) return false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int part = 0; part < kind().layout().count(); part++) {
            kind().layout().position(cursor, center, part, anchor.getValue(FACING));
            if (!level.hasChunkAt(cursor) || !level.getBlockState(cursor).equals(withPart(anchor, part))) return false;
        }
        return true;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player) {
            PetService.onBedPlaced(player, serverLevel, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock()) && level instanceof ServerLevel serverLevel) {
            PetStructures.remove(serverLevel, pos, state);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    public static final class PenBlock extends PetBedBlock {
        public PenBlock() { super(BedKind.PEN); }
        @Override public BedKind kind() { return BedKind.PEN; }
    }

    public static final class StableBlock extends PetBedBlock {
        public StableBlock() { super(BedKind.STABLE); }
        @Override public BedKind kind() { return BedKind.STABLE; }
    }

    public static final class DogBlock extends PetBedBlock {
        public DogBlock() { super(BedKind.DOG); }
        @Override public BedKind kind() { return BedKind.DOG; }
    }

    public static final class CatBlock extends PetBedBlock {
        public CatBlock() { super(BedKind.CAT); }
        @Override public BedKind kind() { return BedKind.CAT; }
    }

    public static final class ParrotBlock extends PetBedBlock {
        public ParrotBlock() { super(BedKind.PARROT); }
        @Override public BedKind kind() { return BedKind.PARROT; }
    }

}
