package org.cyu.cyurevive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PetLayout {
    public static final int PART_LIMIT = 36;
    private final EnumProperty<FacilityPart> parts;
    private final List<BlockPos> offsets;
    private final VoxelShape[][] shapes;
    private final BlockPos[][] rotatedOffsets;
    private final int height;
    private final int radius;

    private PetLayout(List<BlockPos> offsets, List<VoxelShape> north) {
        if (offsets.isEmpty() || offsets.size() > PART_LIMIT || !offsets.get(0).equals(BlockPos.ZERO)
            || offsets.stream().distinct().count() != offsets.size()) {
            throw new IllegalArgumentException("Invalid pet facility cells");
        }
        this.offsets = List.copyOf(offsets);
        parts = EnumProperty.create("part", FacilityPart.class,
            java.util.Arrays.asList(FacilityPart.values()).subList(0, offsets.size()));
        height = offsets.stream().mapToInt(BlockPos::getY).max().orElseThrow() + 1;
        radius = offsets.stream().mapToInt(p -> Math.max(Math.abs(p.getX()), Math.abs(p.getZ()))).max().orElseThrow();
        shapes = new VoxelShape[4][north.size()];
        rotatedOffsets = new BlockPos[4][north.size()];
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (int part = 0; part < north.size(); part++) {
                shapes[facing.get2DDataValue()][part] = rotateShape(north.get(part), facing);
                rotatedOffsets[facing.get2DDataValue()][part] = rotateOffset(offsets.get(part), facing);
            }
        }
    }

    public static PetLayout load(String id) {
        var stream = Objects.requireNonNull(PetLayout.class.getResourceAsStream("/facilities/" + id + ".json"), "Missing facility " + id);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonArray cells = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("cells");
            List<BlockPos> offsets = new ArrayList<>();
            List<VoxelShape> shapes = new ArrayList<>();
            for (JsonElement element : cells) {
                var cell = element.getAsJsonObject();
                JsonArray offset = cell.getAsJsonArray("offset");
                offsets.add(new BlockPos(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt()));
                shapes.add(readShape(cell.getAsJsonArray("collision")));
            }
            return new PetLayout(offsets, shapes);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read facility " + id, failure);
        }
    }

    private static VoxelShape readShape(JsonArray boxes) {
        VoxelShape shape = Shapes.empty();
        for (JsonElement element : boxes) {
            JsonArray box = element.getAsJsonArray();
            if (box.size() != 6) throw new IllegalArgumentException("Invalid facility collision box");
            shape = Shapes.or(shape, Block.box(box.get(0).getAsDouble(), box.get(1).getAsDouble(), box.get(2).getAsDouble(),
                box.get(3).getAsDouble(), box.get(4).getAsDouble(), box.get(5).getAsDouble()));
        }
        return shape.optimize();
    }

    public boolean walkable(int part) {
        return offsets.get(part).getY() == 0;
    }

    public EnumProperty<FacilityPart> parts() { return parts; }

    public int count() { return offsets.size(); }
    public int height() { return height; }
    public int radius() { return radius; }
    public BlockPos offset(int part) { return offsets.get(part); }
    public VoxelShape shape(int part, Direction facing) { return shapes[facing.get2DDataValue()][part]; }

    public BlockPos position(BlockPos anchor, int part, Direction facing) {
        return anchor.offset(rotatedOffsets[facing.get2DDataValue()][part]);
    }

    public void position(BlockPos.MutableBlockPos cursor, BlockPos anchor, int part, Direction facing) {
        cursor.setWithOffset(anchor, rotatedOffsets[facing.get2DDataValue()][part]);
    }

    private static BlockPos rotateOffset(BlockPos offset, Direction facing) {
        return switch (facing) {
            case NORTH -> offset;
            case EAST -> new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
            case SOUTH -> new BlockPos(-offset.getX(), offset.getY(), -offset.getZ());
            case WEST -> new BlockPos(offset.getZ(), offset.getY(), -offset.getX());
            default -> throw new IllegalArgumentException("Pet homes require a horizontal facing");
        };
    }

    private static VoxelShape rotateShape(VoxelShape shape, Direction facing) {
        VoxelShape[] rotated = {Shapes.empty()};
        shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            VoxelShape box = switch (facing) {
                case NORTH -> Shapes.box(minX, minY, minZ, maxX, maxY, maxZ);
                case EAST -> Shapes.box(1 - maxZ, minY, minX, 1 - minZ, maxY, maxX);
                case SOUTH -> Shapes.box(1 - maxX, minY, 1 - maxZ, 1 - minX, maxY, 1 - minZ);
                case WEST -> Shapes.box(minZ, minY, 1 - maxX, maxZ, maxY, 1 - minX);
                default -> throw new IllegalArgumentException("Invalid pet home facing");
            };
            rotated[0] = Shapes.or(rotated[0], box);
        });
        return rotated[0].optimize();
    }
}
