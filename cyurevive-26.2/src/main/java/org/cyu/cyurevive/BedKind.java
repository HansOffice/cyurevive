package org.cyu.cyurevive;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public enum BedKind {
    PEN("pet_bed", "any", 3, 0.125, 2.5, List.of(Vec3.ZERO)),
    STABLE("horse_stable", "equine", 3, 0.125, 2.5, List.of(new Vec3(0, 0, 0.5))),
    DOG("dog_bed", "wolf", 1, 0.3125, 0.75, List.of(Vec3.ZERO)),
    CAT("cat_bed", "cat", 1, 0.25, 0.75, List.of(Vec3.ZERO)),
    PARROT("parrot_bed", "parrot", 1, 1.0, 0.5,
        List.of(new Vec3(-0.25, 0, 0), new Vec3(0.25, 0, 0)));

    private final PetLayout layout;
    private final String id;
    private final String species;
    private final int width;
    private final double height;
    private final double clearance;
    private final List<Vec3> slots;

    BedKind(String id, String species, int width, double height, double clearance, List<Vec3> slots) {
        this.id = id;
        this.layout = PetLayout.load(id);
        this.species = species;
        this.width = width;
        this.height = height;
        this.clearance = clearance;
        this.slots = slots;
    }

    public PetLayout layout() { return layout; }
    public int placementHeight() { return Math.max(3, layout.height()); }

    public String id() { return id; }
    public String species() { return species; }
    public int width() { return width; }
    public double height() { return height; }
    public int capacity() { return slots.size(); }

    public boolean accepts(Animal pet) {
        return switch (this) {
            case PEN -> true;
            case STABLE -> pet.getType() == net.minecraft.world.entity.EntityTypes.HORSE
                || pet.getType() == net.minecraft.world.entity.EntityTypes.DONKEY
                || pet.getType() == net.minecraft.world.entity.EntityTypes.MULE;
            case DOG, CAT, PARROT -> BuiltInRegistries.ENTITY_TYPE.getKey(pet.getType()).equals(CyuRevive.idForSpecies(species));
        };
    }

    public boolean fits(Animal pet) {
        return pet.getBbWidth() <= clearance && pet.getBbHeight() <= 3.0;
    }

    public Vec3 slot(int index, Direction facing) {
        if (index < 0 || index >= slots.size()) throw new IllegalArgumentException("Invalid pet slot " + index);
        Vec3 offset = slots.get(index);
        return switch (facing) {
            case NORTH -> offset;
            case EAST -> new Vec3(-offset.z, 0, offset.x);
            case SOUTH -> new Vec3(-offset.x, 0, -offset.z);
            case WEST -> new Vec3(offset.z, 0, -offset.x);
            default -> throw new IllegalArgumentException("Pet homes require a horizontal facing");
        };
    }
}
